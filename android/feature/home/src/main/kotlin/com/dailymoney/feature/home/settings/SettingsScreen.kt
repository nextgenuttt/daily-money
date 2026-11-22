package com.dailymoney.feature.home.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dailymoney.core.designsystem.component.ErrorBanner
import com.dailymoney.core.designsystem.theme.Spacing
import com.dailymoney.core.domain.auth.AuthUser
import com.dailymoney.feature.home.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    user: AuthUser,
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.working) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(
                stringResource(R.string.settings_account),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
            )
            ListItem(
                headlineContent = { Text(user.displayName ?: user.email.orEmpty()) },
                supportingContent = { if (user.displayName != null) Text(user.email.orEmpty()) },
                leadingContent = { Icon(Icons.Filled.AccountCircle, contentDescription = null) },
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_sign_out)) },
                leadingContent = { Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null) },
                modifier = Modifier.clickable(enabled = !state.working, onClick = viewModel::onSignOutClick),
            )
            if (state.canSignOutEverywhere) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_sign_out_everywhere)) },
                    leadingContent = { Icon(Icons.Filled.Devices, contentDescription = null) },
                    modifier = Modifier.clickable(enabled = !state.working, onClick = viewModel::onSignOutEverywhereClick),
                )
            }
            if (state.everywhereFailed) {
                ErrorBanner(
                    stringResource(R.string.settings_sign_out_everywhere_failed),
                    modifier = Modifier.padding(Spacing.md),
                )
            }
            Text(
                stringResource(R.string.settings_version, state.appVersion),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(Spacing.md),
            )
        }
    }

    when (val dialog = state.dialog) {
        is LogoutDialog.Confirm -> AlertDialog(
            onDismissRequest = viewModel::dismissDialog,
            title = { Text(stringResource(R.string.logout_title)) },
            text = {
                Text(
                    if (dialog.unsyncedChanges > 0) {
                        pluralStringResource(R.plurals.logout_body_unsynced, dialog.unsyncedChanges, dialog.unsyncedChanges)
                    } else {
                        stringResource(R.string.logout_body_local)
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirm) {
                    Text(
                        stringResource(
                            if (dialog.unsyncedChanges > 0) R.string.logout_anyway else R.string.logout_confirm,
                        ),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDialog) { Text(stringResource(R.string.logout_cancel)) }
            },
        )
        LogoutDialog.ConfirmEverywhere -> AlertDialog(
            onDismissRequest = viewModel::dismissDialog,
            title = { Text(stringResource(R.string.logout_everywhere_title)) },
            text = { Text(stringResource(R.string.logout_everywhere_body)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirm) {
                    Text(stringResource(R.string.logout_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDialog) { Text(stringResource(R.string.logout_cancel)) }
            },
        )
        null -> Unit
    }
}
