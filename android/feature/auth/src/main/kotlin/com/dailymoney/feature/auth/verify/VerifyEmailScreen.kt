package com.dailymoney.feature.auth.verify

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dailymoney.core.designsystem.component.ErrorBanner
import com.dailymoney.core.designsystem.component.PrimaryButton
import com.dailymoney.core.designsystem.component.SecondaryButton
import com.dailymoney.feature.auth.R
import com.dailymoney.feature.auth.common.AuthScaffold
import com.dailymoney.feature.auth.common.message

@Composable
fun VerifyEmailScreen(
    email: String,
    viewModel: VerifyEmailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    AuthScaffold(title = null, onBack = null) {
        Icon(
            Icons.Filled.MarkEmailUnread,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp).align(Alignment.CenterHorizontally),
        )
        Text(
            stringResource(R.string.auth_verify_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(R.string.auth_verify_body, email),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        state.error?.message()?.let { ErrorBanner(it) }
        if (state.notYetVerified) ErrorBanner(stringResource(R.string.auth_verify_not_yet))
        if (state.sent) {
            Text(
                stringResource(R.string.auth_verify_sent),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        PrimaryButton(
            text = stringResource(R.string.auth_verify_open_mail),
            onClick = {
                val intent = Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_APP_EMAIL)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                    // No mail app installed: the user can open the link from any device.
                }
            },
        )
        SecondaryButton(
            text = stringResource(R.string.auth_verify_done),
            onClick = viewModel::checkNow,
            enabled = !state.checking,
        )
        TextButton(
            onClick = viewModel::resend,
            enabled = state.resendCooldown == 0,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (state.resendCooldown > 0) {
                    stringResource(R.string.auth_verify_resend_in, state.resendCooldown)
                } else {
                    stringResource(R.string.auth_verify_resend)
                },
            )
        }
        TextButton(onClick = viewModel::signOut, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.auth_wrong_account))
        }
    }
}
