package com.dailymoney.feature.auth.signin

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dailymoney.core.designsystem.component.AppTextField
import com.dailymoney.core.designsystem.component.ErrorBanner
import com.dailymoney.core.designsystem.component.GoogleSignInButton
import com.dailymoney.core.designsystem.component.PasswordField
import com.dailymoney.core.designsystem.component.PrimaryButton
import com.dailymoney.core.designsystem.theme.Spacing
import com.dailymoney.feature.auth.R
import com.dailymoney.feature.auth.common.AuthScaffold
import com.dailymoney.feature.auth.common.message

@Composable
internal fun SignInScreen(
    onBack: () -> Unit,
    onForgotPassword: () -> Unit,
    onCreateAccount: () -> Unit,
    viewModel: SignInViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activity = LocalContext.current as Activity
    val focus = LocalFocusManager.current

    AuthScaffold(title = stringResource(R.string.auth_sign_in_title), onBack = onBack) {
        AppTextField(
            value = state.email,
            onValueChange = viewModel::onEmailChange,
            label = stringResource(R.string.auth_email),
            error = state.emailError?.message(),
            keyboardType = KeyboardType.Email,
            enabled = !state.busy,
        )
        PasswordField(
            value = state.password,
            onValueChange = viewModel::onPasswordChange,
            label = stringResource(R.string.auth_password),
            showLabel = stringResource(R.string.auth_show_password),
            hideLabel = stringResource(R.string.auth_hide_password),
            error = state.passwordError?.message(),
            imeAction = ImeAction.Done,
            onImeAction = {
                focus.clearFocus()
                viewModel.submit()
            },
            enabled = !state.busy,
        )
        TextButton(onClick = onForgotPassword, modifier = Modifier.align(Alignment.End)) {
            Text(stringResource(R.string.auth_forgot_password))
        }
        state.formError?.message()?.let { ErrorBanner(it) }
        PrimaryButton(
            text = stringResource(R.string.auth_sign_in_button),
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
        TextButton(onClick = onCreateAccount, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.auth_no_account))
        }
    }
}

@Composable
internal fun OrDivider() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth(),
    ) {
        HorizontalDivider(Modifier.weight(1f))
        Text(
            stringResource(R.string.auth_or),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.md),
        )
        HorizontalDivider(Modifier.weight(1f))
    }
}
