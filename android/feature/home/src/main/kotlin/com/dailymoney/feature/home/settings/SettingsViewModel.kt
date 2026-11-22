package com.dailymoney.feature.home.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dailymoney.core.domain.auth.PendingChangesRepository
import com.dailymoney.core.domain.auth.SignOutEverywhereUseCase
import com.dailymoney.core.domain.auth.SignOutUseCase
import com.dailymoney.core.domain.config.AppConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface LogoutDialog {
    data class Confirm(val unsyncedChanges: Int) : LogoutDialog
    data object ConfirmEverywhere : LogoutDialog
}

data class SettingsUiState(
    val appVersion: String,
    val canSignOutEverywhere: Boolean,
    val dialog: LogoutDialog? = null,
    val working: Boolean = false,
    val everywhereFailed: Boolean = false,
)

/** Sign-out flow from doc 04 §4.6. The sign-out itself moves the app back to Welcome via the session. */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    config: AppConfig,
    private val pendingChanges: PendingChangesRepository,
    private val signOut: SignOutUseCase,
    private val signOutEverywhere: SignOutEverywhereUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(
        SettingsUiState(appVersion = config.appVersion, canSignOutEverywhere = config.backendEnabled),
    )
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    fun onSignOutClick() {
        viewModelScope.launch {
            val pending = runCatching { pendingChanges.pendingCount() }.getOrDefault(0)
            _state.update { it.copy(dialog = LogoutDialog.Confirm(pending), everywhereFailed = false) }
        }
    }

    fun onSignOutEverywhereClick() = _state.update { it.copy(dialog = LogoutDialog.ConfirmEverywhere, everywhereFailed = false) }

    fun dismissDialog() = _state.update { it.copy(dialog = null) }

    fun confirm() {
        val dialog = _state.value.dialog ?: return
        _state.update { it.copy(dialog = null, working = true) }
        viewModelScope.launch {
            when (dialog) {
                is LogoutDialog.Confirm -> signOut()
                LogoutDialog.ConfirmEverywhere -> {
                    val ok = signOutEverywhere()
                    _state.update { it.copy(working = false, everywhereFailed = !ok) }
                }
            }
        }
    }
}
