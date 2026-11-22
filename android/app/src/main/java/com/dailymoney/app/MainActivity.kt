package com.dailymoney.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dailymoney.core.designsystem.theme.DailyMoneyTheme
import com.dailymoney.core.designsystem.theme.Spacing
import com.dailymoney.core.domain.auth.SessionState
import com.dailymoney.feature.auth.navigation.AuthFlow
import com.dailymoney.feature.auth.reset.ResetPasswordScreen
import com.dailymoney.feature.auth.verify.VerifyEmailScreen
import com.dailymoney.feature.home.navigation.MainFlow
import com.google.firebase.FirebaseApp
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (FirebaseApp.getApps(this).isEmpty()) {
            setContent { DailyMoneyTheme { FirebaseNotConfigured() } }
            return
        }

        splash.setKeepOnScreenCondition { viewModel.session.value == SessionState.Loading }
        if (savedInstanceState == null) viewModel.handleLink(intent?.data)
        setContent { DailyMoneyTheme { AppRoot(viewModel) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (FirebaseApp.getApps(this).isNotEmpty()) viewModel.handleLink(intent.data)
    }
}

/** Top-level switch: the session decides which flow is shown, so Back never crosses it. */
@Composable
private fun AppRoot(viewModel: AppViewModel) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val resetCode by viewModel.pendingResetCode.collectAsStateWithLifecycle()
    val linkMessage by viewModel.linkMessage.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    val verifiedText = stringResource(R.string.link_email_verified)
    val failedText = stringResource(R.string.link_failed)
    LaunchedEffect(linkMessage) {
        val message = linkMessage ?: return@LaunchedEffect
        snackbar.showSnackbar(if (message == LinkMessage.EMAIL_VERIFIED) verifiedText else failedText)
        viewModel.linkMessageShown()
    }

    Box(Modifier.fillMaxSize()) {
        val code = resetCode
        when {
            code != null -> ResetPasswordScreen(oobCode = code, onClose = viewModel::resetHandled)
            else -> when (val s = session) {
                SessionState.Loading -> Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {}
                SessionState.SignedOut -> AuthFlow()
                is SessionState.NeedsVerification -> VerifyEmailScreen(email = s.email)
                is SessionState.SignedIn -> MainFlow(user = s.user)
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(Spacing.md))
    }
}

@Composable
private fun FirebaseNotConfigured() {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().padding(Spacing.lg),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.config_missing_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Text(
                stringResource(R.string.config_missing_body),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = Spacing.sm),
            )
        }
    }
}
