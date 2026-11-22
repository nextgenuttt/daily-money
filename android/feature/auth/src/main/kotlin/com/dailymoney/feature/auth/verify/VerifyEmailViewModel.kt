package com.dailymoney.feature.auth.verify

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dailymoney.core.domain.auth.AuthError
import com.dailymoney.core.domain.auth.AuthRepository
import com.dailymoney.core.domain.auth.AuthResult
import com.dailymoney.core.domain.auth.SignOutUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class VerifyEmailUiState(
    val resendCooldown: Int = VerifyEmailViewModel.COOLDOWN_SECONDS,
    val checking: Boolean = false,
    val notYetVerified: Boolean = false,
    val sent: Boolean = false,
    val error: AuthError? = null,
)

/**
 * When verified, Firebase's session flips to SignedIn and the app root leaves this screen,
 * so the screen only needs to refresh the user (on resume and on "I've verified").
 */
@HiltViewModel
class VerifyEmailViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val signOut: SignOutUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(VerifyEmailUiState())
    val state: StateFlow<VerifyEmailUiState> = _state.asStateFlow()
    private var cooldownJob: Job? = null

    init {
        startCooldown()
    }

    /** Silent refresh when the user comes back from the mail app or browser. */
    fun refresh() {
        viewModelScope.launch { auth.reloadUser() }
    }

    fun checkNow() {
        if (_state.value.checking) return
        _state.update { it.copy(checking = true, notYetVerified = false, error = null) }
        viewModelScope.launch {
            val result = auth.reloadUser()
            // Still on this screen after a successful reload means the email isn't verified yet.
            _state.update {
                it.copy(
                    checking = false,
                    notYetVerified = result is AuthResult.Success,
                    error = (result as? AuthResult.Failure)?.error,
                )
            }
        }
    }

    fun resend() {
        if (_state.value.resendCooldown > 0) return
        viewModelScope.launch {
            val result = auth.resendVerification()
            _state.update { it.copy(sent = result is AuthResult.Success, error = (result as? AuthResult.Failure)?.error) }
            startCooldown()
        }
    }

    fun signOut() {
        viewModelScope.launch { signOut.invoke() }
    }

    private fun startCooldown() {
        cooldownJob?.cancel()
        cooldownJob = viewModelScope.launch {
            for (left in COOLDOWN_SECONDS downTo 0) {
                _state.update { it.copy(resendCooldown = left) }
                if (left > 0) delay(1_000)
            }
        }
    }

    companion object {
        const val COOLDOWN_SECONDS = 60
    }
}
