package com.dailymoney.app

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dailymoney.core.domain.auth.AuthRepository
import com.dailymoney.core.domain.auth.AuthResult
import com.dailymoney.core.domain.auth.BackendSession
import com.dailymoney.core.domain.auth.ObserveSessionUseCase
import com.dailymoney.core.domain.auth.SessionState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class LinkMessage { EMAIL_VERIFIED, LINK_FAILED }

@HiltViewModel
class AppViewModel @Inject constructor(
    observeSession: ObserveSessionUseCase,
    private val auth: AuthRepository,
    private val backend: BackendSession,
) : ViewModel() {

    val session: StateFlow<SessionState> = observeSession()
        .stateIn(viewModelScope, SharingStarted.Eagerly, SessionState.Loading)

    private val _pendingResetCode = MutableStateFlow<String?>(null)
    val pendingResetCode: StateFlow<String?> = _pendingResetCode.asStateFlow()

    private val _linkMessage = MutableStateFlow<LinkMessage?>(null)
    val linkMessage: StateFlow<LinkMessage?> = _linkMessage.asStateFlow()

    private var bootstrappedUid: String? = null

    init {
        // Register this device with the backend once per signed-in user per app launch (no-op in R1).
        viewModelScope.launch {
            session.filterIsInstance<SessionState.SignedIn>().collect { signedIn ->
                if (bootstrappedUid != signedIn.user.uid) {
                    bootstrappedUid = signedIn.user.uid
                    backend.bootstrap()
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

    fun resetHandled() {
        _pendingResetCode.value = null
    }

    fun linkMessageShown() {
        _linkMessage.value = null
    }
}
