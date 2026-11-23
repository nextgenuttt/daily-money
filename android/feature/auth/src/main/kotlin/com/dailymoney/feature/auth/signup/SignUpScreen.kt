package com.dailymoney.feature.auth.signup

import androidx.compose.ui.autofill.ContentType
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dailymoney.core.designsystem.component.AppTextField
import com.dailymoney.core.designsystem.component.ErrorBanner
import com.dailymoney.core.designsystem.component.GoogleSignInButton
import com.dailymoney.core.designsystem.component.PasswordField
import com.dailymoney.core.designsystem.component.PasswordStrengthMeter
import com.dailymoney.core.designsystem.component.PrimaryButton
import com.dailymoney.core.domain.auth.PasswordStrength
import com.dailymoney.feature.auth.R
import com.dailymoney.feature.auth.common.AuthScaffold
import com.dailymoney.feature.auth.common.message
import com.dailymoney.feature.auth.common.passwordRulesMessage
import com.dailymoney.feature.auth.signin.OrDivider

@Composable
internal fun SignUpScreen(
    onBack: () -> Unit,
    onHaveAccount: () -> Unit,
    viewModel: SignUpViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activity = requireNotNull(LocalActivity.current)
    val focus = LocalFocusManager.current
    val enabled = !state.busy

    AuthScaffold(title = stringResource(R.string.auth_sign_up_title), onBack = onBack) {
        AppTextField(
            value = state.name,
            onValueChange = viewModel::onNameChange,
            label = stringResource(R.string.auth_full_name),
            contentType = ContentType.PersonFullName,
            error = state.nameError?.message(),
            capitalization = KeyboardCapitalization.Words,
            enabled = enabled,
        )
        AppTextField(
            value = state.email,
            onValueChange = viewModel::onEmailChange,
            label = stringResource(R.string.auth_email),
            contentType = ContentType.EmailAddress + ContentType.Username,
            error = state.emailError?.message(),
            keyboardType = KeyboardType.Email,
            enabled = enabled,
        )
        PasswordField(
            value = state.password,
            onValueChange = viewModel::onPasswordChange,
            label = stringResource(R.string.auth_password),
            contentType = ContentType.NewPassword,
            showLabel = stringResource(R.string.auth_show_password),
            hideLabel = stringResource(R.string.auth_hide_password),
            error = state.passwordRules.takeIf { it.isNotEmpty() }?.let { passwordRulesMessage(it) },
            imeAction = ImeAction.Next,
            enabled = enabled,
        )
        state.strength?.let { strength ->
            PasswordStrengthMeter(
                strength = strength,
                label = stringResource(
                    when (strength) {
                        PasswordStrength.WEAK -> R.string.auth_strength_weak
                        PasswordStrength.MEDIUM -> R.string.auth_strength_medium
                        PasswordStrength.STRONG -> R.string.auth_strength_strong
                    },
                ),
            )
        }
        PasswordField(
            value = state.confirm,
            onValueChange = viewModel::onConfirmChange,
            label = stringResource(R.string.auth_confirm_password),
            contentType = ContentType.NewPassword,
            showLabel = stringResource(R.string.auth_show_password),
            hideLabel = stringResource(R.string.auth_hide_password),
            error = state.confirmError?.message(),
            imeAction = ImeAction.Done,
            onImeAction = { focus.clearFocus() },
            enabled = enabled,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(state.termsAccepted, enabled = enabled, role = Role.Checkbox, onValueChange = viewModel::onTermsChange),
        ) {
            Checkbox(checked = state.termsAccepted, onCheckedChange = null, enabled = enabled)
            Text(stringResource(R.string.auth_terms), style = MaterialTheme.typography.bodyMedium)
        }
        state.termsError?.let {
            Text(it.message(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        state.formError?.message()?.let { ErrorBanner(it) }
        PrimaryButton(
            text = stringResource(R.string.auth_create_account),
            onClick = {
                focus.clearFocus()
                viewModel.submit()
            },
            loading = state.submitting,
            enabled = !state.googleLoading,
        )
        OrDivider()
        GoogleSignInButton(
            text = stringResource(R.string.auth_continue_google),
            onClick = { viewModel.signInWithGoogle(activity) },
            loading = state.googleLoading,
            enabled = !state.submitting,
        )
        TextButton(onClick = onHaveAccount, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.auth_have_account))
        }
    }
}
