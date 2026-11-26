package com.dailymoney.feature.home.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.Policy
import androidx.compose.ui.platform.LocalUriHandler
import com.dailymoney.core.domain.auth.DeleteAccountResult
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dailymoney.core.designsystem.component.ErrorBanner
import com.dailymoney.core.designsystem.component.PasswordField
import com.dailymoney.core.designsystem.theme.Spacing
import com.dailymoney.core.domain.auth.AuthUser
import com.dailymoney.core.domain.backup.BackupRules
import com.dailymoney.feature.home.R
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    user: AuthUser,
    onBack: () -> Unit,
    onNotifications: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BACKUP_MIME)) { uri ->
        viewModel.onExportTarget(uri?.toString())
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        viewModel.onRestoreSource(uri?.toString())
    }

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
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
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
            UpiIdItem()
            PhoneItem()
            ListItem(
                headlineContent = { Text(stringResource(R.string.notif_settings_title)) },
                supportingContent = { Text(stringResource(R.string.notif_settings_entry_hint)) },
                leadingContent = { Icon(Icons.Filled.Notifications, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onNotifications),
            )
            SmsReaderSetting()
            HorizontalDivider()
            Text(
                stringResource(R.string.settings_your_data),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.backup_create)) },
                supportingContent = { Text(stringResource(R.string.backup_create_hint)) },
                leadingContent = { Icon(Icons.Filled.Backup, contentDescription = null) },
                modifier = Modifier.clickable(enabled = !state.working) { viewModel.onBackupClick() },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.backup_restore)) },
                supportingContent = { Text(stringResource(R.string.backup_restore_hint)) },
                leadingContent = { Icon(Icons.Filled.SettingsBackupRestore, contentDescription = null) },
                modifier = Modifier.clickable(enabled = !state.working) { restoreLauncher.launch(arrayOf("*/*")) },
            )
            state.backupMessage?.let { message ->
                val text = stringResource(message.label())
                if (message == BackupMessage.BACKED_UP || message == BackupMessage.RESTORED) {
                    Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(Spacing.md))
                } else {
                    ErrorBanner(text, modifier = Modifier.padding(Spacing.md))
                }
            }
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
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_delete_account), color = MaterialTheme.colorScheme.error) },
                leadingContent = { Icon(Icons.Filled.PersonRemove, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                modifier = Modifier.clickable(enabled = !state.working, onClick = viewModel::onDeleteAccountClick),
            )
            state.deleteProblem?.let { problem ->
                ErrorBanner(
                    when (problem) {
                        is DeleteAccountResult.TransferOwnershipFirst -> stringResource(R.string.delete_account_owner, problem.groups)
                        DeleteAccountResult.SignInAgain -> stringResource(R.string.delete_account_sign_in_again)
                        else -> stringResource(R.string.delete_account_failed)
                    },
                    modifier = Modifier.padding(Spacing.md),
                )
            }
            if (state.privacyUrl.isNotBlank()) {
                val uriHandler = LocalUriHandler.current
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_privacy)) },
                    leadingContent = { Icon(Icons.Filled.Policy, contentDescription = null) },
                    modifier = Modifier.clickable { runCatching { uriHandler.openUri(state.privacyUrl) } },
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
                Row {
                    TextButton(onClick = viewModel::dismissDialog) { Text(stringResource(R.string.logout_cancel)) }
                    TextButton(onClick = { viewModel.onBackupClick(fromSignOut = true) }) {
                        Text(stringResource(R.string.logout_backup_first))
                    }
                }
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
        LogoutDialog.ConfirmDelete -> AlertDialog(
            onDismissRequest = viewModel::dismissDialog,
            title = { Text(stringResource(R.string.delete_account_title)) },
            text = { Text(stringResource(R.string.delete_account_body)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirm) {
                    Text(stringResource(R.string.delete_account_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = viewModel::dismissDialog) { Text(stringResource(R.string.logout_cancel)) }
                    TextButton(onClick = { viewModel.onBackupClick() }) { Text(stringResource(R.string.logout_backup_first)) }
                }
            },
        )
        null -> Unit
    }

    when (val dialog = state.backupDialog) {
        BackupDialog.ExportPassword -> BackupPasswordDialog(
            title = stringResource(R.string.backup_create),
            body = stringResource(R.string.backup_password_body),
            confirmPassword = true,
            onDismiss = viewModel::dismissBackupDialog,
            onConfirm = { password ->
                viewModel.onExportPassword(password)
                exportLauncher.launch("dailymoney-${LocalDate.now()}.dmbackup")
            },
        )
        is BackupDialog.RestorePassword -> BackupPasswordDialog(
            title = stringResource(R.string.backup_restore),
            body = stringResource(R.string.backup_restore_body),
            confirmPassword = false,
            onDismiss = viewModel::dismissBackupDialog,
            onConfirm = viewModel::onRestorePassword,
        )
        null -> Unit
    }
}

@Composable
private fun BackupPasswordDialog(
    title: String,
    body: String,
    confirmPassword: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var showErrors by remember { mutableStateOf(false) }
    val tooShort = confirmPassword && !BackupRules.passwordOk(password)
    val mismatch = confirmPassword && password != repeat
    val canSubmit = password.isNotEmpty() && !tooShort && !mismatch
    val submit = { if (canSubmit) onConfirm(password) else showErrors = true }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(body, style = MaterialTheme.typography.bodyMedium)
                PasswordField(
                    value = password,
                    onValueChange = { password = it },
                    label = stringResource(R.string.backup_password),
                    showLabel = stringResource(R.string.backup_show_password),
                    hideLabel = stringResource(R.string.backup_hide_password),
                    error = if (showErrors && tooShort) stringResource(R.string.backup_password_short, BackupRules.MIN_PASSWORD) else null,
                    imeAction = if (confirmPassword) ImeAction.Next else ImeAction.Done,
                    onImeAction = { if (!confirmPassword) submit() },
                )
                if (confirmPassword) {
                    PasswordField(
                        value = repeat,
                        onValueChange = { repeat = it },
                        label = stringResource(R.string.backup_password_repeat),
                        showLabel = stringResource(R.string.backup_show_password),
                        hideLabel = stringResource(R.string.backup_hide_password),
                        error = if (showErrors && !tooShort && mismatch) stringResource(R.string.backup_password_mismatch) else null,
                        onImeAction = submit,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = submit) { Text(stringResource(R.string.backup_continue)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.logout_cancel)) } },
    )
}

private const val BACKUP_MIME = "application/octet-stream"

private fun BackupMessage.label() = when (this) {
    BackupMessage.BACKED_UP -> R.string.backup_done
    BackupMessage.BACKUP_FAILED -> R.string.backup_failed
    BackupMessage.RESTORED -> R.string.restore_done
    BackupMessage.WRONG_PASSWORD -> R.string.restore_wrong_password
    BackupMessage.NOT_A_BACKUP -> R.string.restore_not_backup
    BackupMessage.NEWER_VERSION -> R.string.restore_newer_version
    BackupMessage.RESTORE_FAILED -> R.string.restore_failed
}
