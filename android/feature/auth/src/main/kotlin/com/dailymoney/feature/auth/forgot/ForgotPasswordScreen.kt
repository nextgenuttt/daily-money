package com.dailymoney.feature.auth.forgot

import androidx.compose.ui.autofill.ContentType
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dailymoney.core.designsystem.component.AppTextField
import com.dailymoney.core.designsystem.component.ErrorBanner
import com.dailymoney.core.designsystem.component.PrimaryButton
import com.dailymoney.core.designsystem.component.SecondaryButton
import com.dailymoney.core.domain.auth.AuthError
import com.dailymoney.core.domain.auth.AuthResult
import com.dailymoney.core.domain.auth.CredentialRules
import com.dailymoney.core.domain.auth.FieldError
import com.dailymoney.core.domain.auth.SendPasswordResetUseCase
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

data class ForgotPasswordUiState(
    val email: String = "",
    val emailError: FieldError? = null,
    val error: AuthError? = null,
    val submitting: Boolean = false,
    val sentTo: String? = null,
)

@HiltViewModel
class ForgotPasswordViewModel @Inject constructor(
    private val sendReset: SendPasswordResetUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(ForgotPasswordUiState())
    val state: StateFlow<ForgotPasswordUiState> = _state.asStateFlow()

    fun onEmailChange(v: String) = _state.update { it.copy(email = v, emailError = null, error = null) }

    fun submit() {
        val s = _state.value
        if (s.submitting) return
        val emailError = CredentialRules.emailError(s.email)
        if (emailError != null) {
            _state.update { it.copy(emailError = emailError) }
            return
        }
        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            val result = sendReset(s.email)
            _state.update {
                it.copy(
                    submitting = false,
                    sentTo = if (result is AuthResult.Success) CredentialRules.normalizeEmail(s.email) else null,
                    error = (result as? AuthResult.Failure)?.error,
                )
            }
        }
    }
}

@Composable
internal fun ForgotPasswordScreen(
    onBack: () -> Unit,
    viewModel: ForgotPasswordViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current

    AuthScaffold(title = stringResource(R.string.auth_forgot_title), onBack = onBack) {
        val sentTo = state.sentTo
        if (sentTo != null) {
            Text(stringResource(R.string.auth_forgot_sent, sentTo), style = MaterialTheme.typography.bodyLarge)
            SecondaryButton(text = stringResource(R.string.auth_back_to_sign_in), onClick = onBack)
            return@AuthScaffold
        }
        Text(
            stringResource(R.string.auth_forgot_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AppTextField(
            value = state.email,
            onValueChange = viewModel::onEmailChange,
            label = stringResource(R.string.auth_email),
            contentType = ContentType.EmailAddress + ContentType.Username,
            error = state.emailError?.message(),
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Done,
            onImeAction = {
                focus.clearFocus()
                viewModel.submit()
            },
            enabled = !state.submitting,
        )
        state.error?.message()?.let { ErrorBanner(it) }
        PrimaryButton(
            text = stringResource(R.string.auth_forgot_send),
            onClick = {
                focus.clearFocus()
                viewModel.submit()
            },
            loading = state.submitting,
        )
    }
}
