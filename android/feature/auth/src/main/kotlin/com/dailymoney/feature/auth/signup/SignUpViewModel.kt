package com.dailymoney.feature.auth.signup

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dailymoney.core.domain.auth.AuthError
import com.dailymoney.core.domain.auth.AuthResult
import com.dailymoney.core.domain.auth.CredentialRules
import com.dailymoney.core.domain.auth.FieldError
import com.dailymoney.core.domain.auth.PasswordRule
import com.dailymoney.core.domain.auth.PasswordStrength
import com.dailymoney.core.domain.auth.SignUpWithEmailUseCase
import com.dailymoney.feature.auth.google.GoogleSignInHandler
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SignUpUiState(
    val name: String = "",
    val email: String = "",
    val password: String = "",
    val confirm: String = "",
    val termsAccepted: Boolean = false,
    val nameError: FieldError? = null,
    val emailError: FieldError? = null,
    val passwordRules: Set<PasswordRule> = emptySet(),
    val confirmError: FieldError? = null,
    val termsError: FieldError? = null,
    val formError: AuthError? = null,
    val submitting: Boolean = false,
    val googleLoading: Boolean = false,
) {
    val busy get() = submitting || googleLoading
    val strength: PasswordStrength? get() = if (password.isEmpty()) null else CredentialRules.strength(password)
}

@HiltViewModel
class SignUpViewModel @Inject constructor(
    private val signUp: SignUpWithEmailUseCase,
    private val google: GoogleSignInHandler,
) : ViewModel() {

    private val _state = MutableStateFlow(SignUpUiState())
    val state: StateFlow<SignUpUiState> = _state.asStateFlow()

    fun onNameChange(v: String) = _state.update { it.copy(name = v, nameError = null, formError = null) }
    fun onEmailChange(v: String) = _state.update { it.copy(email = v, emailError = null, formError = null) }
    fun onPasswordChange(v: String) = _state.update { it.copy(password = v, passwordRules = emptySet(), formError = null) }
    fun onConfirmChange(v: String) = _state.update { it.copy(confirm = v, confirmError = null) }
    fun onTermsChange(v: Boolean) = _state.update { it.copy(termsAccepted = v, termsError = null) }

    fun submit() {
        val s = _state.value
        if (s.busy) return
        val validated = s.copy(
            nameError = CredentialRules.nameError(s.name),
            emailError = CredentialRules.emailError(s.email),
            passwordRules = CredentialRules.brokenPasswordRules(s.password, s.email),
            confirmError = if (s.confirm != s.password) FieldError.PASSWORDS_DIFFERENT else null,
            termsError = if (!s.termsAccepted) FieldError.TERMS_REQUIRED else null,
        )
        val invalid = validated.nameError != null || validated.emailError != null ||
            validated.passwordRules.isNotEmpty() || validated.confirmError != null || validated.termsError != null
        if (invalid) {
            _state.value = validated
            return
        }
        _state.update { it.copy(submitting = true, formError = null) }
        viewModelScope.launch {
            val result = signUp(s.name, s.email, s.password)
            _state.update { it.copy(submitting = false, formError = (result as? AuthResult.Failure)?.error) }
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
