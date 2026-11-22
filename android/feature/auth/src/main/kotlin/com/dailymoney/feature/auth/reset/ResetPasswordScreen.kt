package com.dailymoney.feature.auth.reset

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dailymoney.core.designsystem.component.ErrorBanner
import com.dailymoney.core.designsystem.component.PasswordField
import com.dailymoney.core.designsystem.component.PrimaryButton
import com.dailymoney.core.domain.auth.AuthError
import com.dailymoney.core.domain.auth.AuthRepository
import com.dailymoney.core.domain.auth.AuthResult
import com.dailymoney.core.domain.auth.ConfirmPasswordResetUseCase
import com.dailymoney.core.domain.auth.FieldError
import com.dailymoney.core.domain.auth.SignInWithEmailUseCase
import com.dailymoney.feature.auth.R
import com.dailymoney.feature.auth.common.AuthScaffold
import com.dailymoney.feature.auth.common.message
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ResetPasswordUiState(
    val checkingLink: Boolean = true,
    val email: String? = null,
    val password: String = "",
    val confirm: String = "",
    val confirmError: FieldError? = null,
    val error: AuthError? = null,
    val submitting: Boolean = false,
    val done: Boolean = false,
)

@HiltViewModel
class ResetPasswordViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val confirmReset: ConfirmPasswordResetUseCase,
    private val signIn: SignInWithEmailUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(ResetPasswordUiState())
    val state: StateFlow<ResetPasswordUiState> = _state.asStateFlow()
    private var oobCode: String? = null

    fun load(code: String) {
        if (oobCode == code) return
        oobCode = code
        viewModelScope.launch {
            when (val result = auth.verifyPasswordResetCode(code)) {
                is AuthResult.Success -> _state.update { it.copy(checkingLink = false, email = result.value) }
                is AuthResult.Failure -> _state.update { it.copy(checkingLink = false, error = result.error) }
            }
        }
    }

    fun onPasswordChange(v: String) = _state.update { it.copy(password = v, error = null) }
    fun onConfirmChange(v: String) = _state.update { it.copy(confirm = v, confirmError = null) }

    fun submit() {
        val s = _state.value
        val code = oobCode ?: return
        val email = s.email ?: return
        if (s.submitting) return
        if (s.confirm != s.password) {
            _state.update { it.copy(confirmError = FieldError.PASSWORDS_DIFFERENT) }
            return
        }
        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            when (val result = confirmReset(code, email, s.password)) {
                is AuthResult.Failure -> _state.update { it.copy(submitting = false, error = result.error) }
                is AuthResult.Success -> {
                    signIn(email, s.password)
                    _state.update { it.copy(submitting = false, done = true) }
                }
            }
        }
    }
}

/** Opened from the password-reset email link (App Link → MainActivity). */
@Composable
fun ResetPasswordScreen(
    oobCode: String,
    onClose: () -> Unit,
    viewModel: ResetPasswordViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    LaunchedEffect(oobCode) { viewModel.load(oobCode) }
    LaunchedEffect(state.done) { if (state.done) onClose() }

    AuthScaffold(title = stringResource(R.string.auth_reset_title), onBack = onClose) {
        if (state.checkingLink) {
            Text(stringResource(R.string.auth_reset_checking), style = MaterialTheme.typography.bodyLarge)
            LinearProgressIndicator(Modifier.fillMaxWidth())
            return@AuthScaffold
        }
        val email = state.email
        if (email == null) {
            state.error?.message()?.let { ErrorBanner(it) }
            return@AuthScaffold
        }
        Text(
            stringResource(R.string.auth_reset_for, email),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PasswordField(
            value = state.password,
            onValueChange = viewModel::onPasswordChange,
            label = stringResource(R.string.auth_new_password),
            showLabel = stringResource(R.string.auth_show_password),
            hideLabel = stringResource(R.string.auth_hide_password),
            imeAction = ImeAction.Next,
            enabled = !state.submitting,
        )
        PasswordField(
            value = state.confirm,
            onValueChange = viewModel::onConfirmChange,
            label = stringResource(R.string.auth_confirm_password),
            showLabel = stringResource(R.string.auth_show_password),
            hideLabel = stringResource(R.string.auth_hide_password),
            error = state.confirmError?.message(),
            onImeAction = {
                focus.clearFocus()
                viewModel.submit()
            },
            enabled = !state.submitting,
        )
        state.error?.message()?.let { ErrorBanner(it) }
        PrimaryButton(
            text = stringResource(R.string.auth_reset_save),
            onClick = {
                focus.clearFocus()
                viewModel.submit()
            },
            loading = state.submitting,
        )
    }
}
