package com.dailymoney.feature.auth.signin

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dailymoney.core.domain.auth.AuthError
import com.dailymoney.core.domain.auth.AuthResult
import com.dailymoney.core.domain.auth.CredentialRules
import com.dailymoney.core.domain.auth.FieldError
import com.dailymoney.core.domain.auth.SignInWithEmailUseCase
import com.dailymoney.feature.auth.google.GoogleSignInHandler
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SignInUiState(
    val email: String = "",
    val password: String = "",
    val emailError: FieldError? = null,
    val passwordError: FieldError? = null,
    val formError: AuthError? = null,
    val submitting: Boolean = false,
    val googleLoading: Boolean = false,
) {
    val busy get() = submitting || googleLoading
}

/**
 * Success needs no navigation event: Firebase's session changes and the app root switches to
 * Home (or to "Verify your email") on its own.
 */
@HiltViewModel
class SignInViewModel @Inject constructor(
    private val signInWithEmail: SignInWithEmailUseCase,
    private val google: GoogleSignInHandler,
) : ViewModel() {

    private val _state = MutableStateFlow(SignInUiState())
    val state: StateFlow<SignInUiState> = _state.asStateFlow()

    fun onEmailChange(value: String) = _state.update { it.copy(email = value, emailError = null, formError = null) }

    fun onPasswordChange(value: String) =
        _state.update { it.copy(password = value, passwordError = null, formError = null) }

    fun submit() {
        val current = _state.value
        if (current.busy) return
        val emailError = CredentialRules.emailError(current.email)
        val passwordError = if (current.password.isEmpty()) FieldError.REQUIRED else null
        if (emailError != null || passwordError != null) {
            _state.update { it.copy(emailError = emailError, passwordError = passwordError) }
            return
        }
        _state.update { it.copy(submitting = true, formError = null) }
        viewModelScope.launch {
            val result = signInWithEmail(current.email, current.password)
            _state.update {
                it.copy(submitting = false, formError = (result as? AuthResult.Failure)?.error)
            }
        }
    }

    fun signInWithGoogle(activity: Activity) {
        if (_state.value.busy) return
        _state.update { it.copy(googleLoading = true, formError = null) }
        viewModelScope.launch {
            val result = google.signIn(activity)
            _state.update { it.copy(googleLoading = false, formError = (result as? AuthResult.Failure)?.error) }
        }
    }
}

data class GoogleOnlyUiState(val loading: Boolean = false, val error: AuthError? = null)

/** Welcome screen: only the Google button needs logic. */
@HiltViewModel
class GoogleOnlyViewModel @Inject constructor(private val google: GoogleSignInHandler) : ViewModel() {

    private val _state = MutableStateFlow(GoogleOnlyUiState())
    val state: StateFlow<GoogleOnlyUiState> = _state.asStateFlow()

    fun signInWithGoogle(activity: Activity) {
        if (_state.value.loading) return
        _state.value = GoogleOnlyUiState(loading = true)
        viewModelScope.launch {
            val result = google.signIn(activity)
            _state.value = GoogleOnlyUiState(error = (result as? AuthResult.Failure)?.error)
        }
    }
}
