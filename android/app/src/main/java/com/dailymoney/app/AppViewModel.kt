package com.dailymoney.app

import android.net.Uri
import android.os.Bundle
import com.dailymoney.core.data.reminders.AppLinks
import com.dailymoney.core.domain.money.ObserveBudgetProgressUseCase
import com.dailymoney.core.domain.reminders.CheckBudgetAlertsUseCase
import com.dailymoney.core.domain.reminders.ReminderScheduler
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dailymoney.core.domain.auth.AuthRepository
import com.dailymoney.core.domain.auth.AuthResult
import com.dailymoney.core.domain.auth.BackendSession
import com.dailymoney.core.domain.auth.ObserveSessionUseCase
import com.dailymoney.core.domain.auth.SessionState
import com.dailymoney.core.domain.money.BackgroundJobs
import com.dailymoney.core.domain.money.EnsureDefaultDataUseCase
import com.dailymoney.core.domain.money.PostDueRecurringUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class LinkMessage { EMAIL_VERIFIED, LINK_FAILED }

/** Where a tapped notification wants to go. */
sealed interface OpenRequest {
    data class Reminder(val id: String, val epochDay: Long?, val complete: Boolean) : OpenRequest
    data object Subscriptions : OpenRequest
    data object Budgets : OpenRequest
    data class Todo(val id: String) : OpenRequest
}

@OptIn(FlowPreview::class)
@HiltViewModel
class AppViewModel @Inject constructor(
    observeSession: ObserveSessionUseCase,
    private val auth: AuthRepository,
    private val backend: BackendSession,
    private val ensureDefaultData: EnsureDefaultDataUseCase,
    private val postDueRecurring: PostDueRecurringUseCase,
    private val backgroundJobs: BackgroundJobs,
    private val reminderScheduler: ReminderScheduler,
    private val observeBudgetProgress: ObserveBudgetProgressUseCase,
    private val checkBudgetAlerts: CheckBudgetAlertsUseCase,
) : ViewModel() {

    val session: StateFlow<SessionState> = observeSession()
        .stateIn(viewModelScope, SharingStarted.Eagerly, SessionState.Loading)

    private val _pendingResetCode = MutableStateFlow<String?>(null)
    val pendingResetCode: StateFlow<String?> = _pendingResetCode.asStateFlow()

    private val _linkMessage = MutableStateFlow<LinkMessage?>(null)
    val linkMessage: StateFlow<LinkMessage?> = _linkMessage.asStateFlow()

    private val _openRequest = MutableStateFlow<OpenRequest?>(null)
    val openRequest: StateFlow<OpenRequest?> = _openRequest.asStateFlow()

    private var bootstrappedUid: String? = null
    private var budgetWatch: Job? = null

    init {
        // Once per signed-in user per launch: default categories + Cash account, post recurring
        // transactions that fell due, schedule the daily job, then register with the backend (no-op in R1).
        viewModelScope.launch {
            session.collect { state ->
                when (state) {
                    // Sign-out wipes the database, so the next sign-in (even the same user) must re-seed it.
                    SessionState.SignedOut -> {
                        bootstrappedUid = null
                        budgetWatch?.cancel()
                    }
                    is SessionState.SignedIn -> if (bootstrappedUid != state.user.uid) {
                        bootstrappedUid = state.user.uid
                        ensureDefaultData()
                        postDueRecurring()
                        backgroundJobs.scheduleRecurring()
                        reminderScheduler.reschedule()
                        watchBudgets()
                        backend.bootstrap()
                    }
                    else -> Unit
                }
            }
        }
    }

    /** Firebase email action link: https://<host>/auth/action?mode=…&oobCode=… */
    fun handleLink(uri: Uri?) {
        if (uri?.path?.startsWith("/auth/action") != true) return
        val code = uri.getQueryParameter("oobCode") ?: return
        when (uri.getQueryParameter("mode")) {
            "resetPassword" -> _pendingResetCode.value = code
            "verifyEmail", "recoverEmail" -> viewModelScope.launch {
                val result = auth.applyEmailVerification(code)
                _linkMessage.value = if (result is AuthResult.Success) LinkMessage.EMAIL_VERIFIED else LinkMessage.LINK_FAILED
            }
        }
    }

    /** Budget alerts while the app is open; the daily job covers the rest. */
    private fun watchBudgets() {
        budgetWatch?.cancel()
        budgetWatch = viewModelScope.launch {
            observeBudgetProgress()
                .debounce(1_500)
                .catch { }
                .collect { progress -> runCatching { checkBudgetAlerts(progress) } }
        }
    }

    /** Extras added by notifications (see AppLinks in :core:data). */
    fun handleOpen(extras: Bundle?) {
        val what = extras?.getString(AppLinks.EXTRA_OPEN) ?: return
        _openRequest.value = when (what) {
            AppLinks.OPEN_REMINDER, AppLinks.OPEN_COMPLETE -> extras.getString(AppLinks.EXTRA_REMINDER_ID)?.let { id ->
                OpenRequest.Reminder(
                    id = id,
                    epochDay = extras.getLong(AppLinks.EXTRA_ORIGINAL_DATE, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE },
                    complete = what == AppLinks.OPEN_COMPLETE,
                )
            }
            AppLinks.OPEN_SUBSCRIPTIONS -> OpenRequest.Subscriptions
            AppLinks.OPEN_BUDGETS -> OpenRequest.Budgets
            AppLinks.OPEN_TODO -> extras.getString(AppLinks.EXTRA_TODO_ID)?.let { OpenRequest.Todo(it) }
            else -> null
        }
    }

    fun openHandled() {
        _openRequest.value = null
    }

    fun resetHandled() {
        _pendingResetCode.value = null
    }

    fun linkMessageShown() {
        _linkMessage.value = null
    }
}
