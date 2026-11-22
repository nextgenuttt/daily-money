package com.dailymoney.feature.auth.welcome

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dailymoney.core.designsystem.component.ErrorBanner
import com.dailymoney.core.designsystem.component.GoogleSignInButton
import com.dailymoney.core.designsystem.component.PrimaryButton
import com.dailymoney.core.designsystem.theme.Spacing
import com.dailymoney.feature.auth.R
import com.dailymoney.feature.auth.common.message
import com.dailymoney.feature.auth.signin.GoogleOnlyViewModel

@Composable
internal fun WelcomeScreen(
    onSignInWithEmail: () -> Unit,
    onCreateAccount: () -> Unit,
    viewModel: GoogleOnlyViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activity = LocalContext.current as Activity

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().systemBarsPadding(), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier.widthIn(max = 480.dp).padding(Spacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Box(
                    modifier = Modifier
                        .size(88.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(24.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.AccountBalanceWallet,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(44.dp),
                    )
                }
                Text(
                    stringResource(R.string.auth_app_tagline),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(Spacing.lg))
                state.error?.message()?.let { ErrorBanner(it) }
                GoogleSignInButton(
                    text = stringResource(R.string.auth_continue_google),
                    onClick = { viewModel.signInWithGoogle(activity) },
                    loading = state.loading,
                )
                PrimaryButton(
                    text = stringResource(R.string.auth_sign_in_email),
                    onClick = onSignInWithEmail,
                    enabled = !state.loading,
                )
                TextButton(onClick = onCreateAccount, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.auth_new_here))
                }
            }
        }
    }
}
