package com.dailymoney.feature.home.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dailymoney.core.domain.auth.PendingChangesRepository
import com.dailymoney.core.domain.auth.SignOutEverywhereUseCase
import com.dailymoney.core.domain.auth.SignOutUseCase
import com.dailymoney.core.domain.backup.BackupResult
import com.dailymoney.core.domain.backup.BackupService
import com.dailymoney.core.domain.backup.RestoreBackupUseCase
import com.dailymoney.core.domain.backup.RestoreResult
import com.dailymoney.core.domain.config.AppConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface LogoutDialog {
    data class Confirm(val unsyncedChanges: Int) : LogoutDialog
    data object ConfirmEverywhere : LogoutDialog
}

sealed interface BackupDialog {
    data object ExportPassword : BackupDialog
    data class RestorePassword(val sourceUri: String) : BackupDialog
}

enum class BackupMessage { BACKED_UP, BACKUP_FAILED, RESTORED, WRONG_PASSWORD, NOT_A_BACKUP, NEWER_VERSION, RESTORE_FAILED }

data class SettingsUiState(
    val appVersion: String,
    val canSignOutEverywhere: Boolean,
    val dialog: LogoutDialog? = null,
    val working: Boolean = false,
    val everywhereFailed: Boolean = false,
    val backupDialog: BackupDialog? = null,
    val backupMessage: BackupMessage? = null,
)

/** Sign-out flow from doc 04 §4.6. The sign-out itself moves the app back to Welcome via the session. */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    config: AppConfig,
    private val pendingChanges: PendingChangesRepository,
    private val signOut: SignOutUseCase,
    private val signOutEverywhere: SignOutEverywhereUseCase,
    private val backups: BackupService,
    private val restoreBackup: RestoreBackupUseCase,
) : ViewModel() {

    private var exportPassword: CharArray? = null
    private var signOutAfterBackup = false

    private val _state = MutableStateFlow(
        SettingsUiState(appVersion = config.appVersion, canSignOutEverywhere = config.backendEnabled),
    )
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    fun onSignOutClick() {
        viewModelScope.launch {
            val pending = runCatching { pendingChanges.pendingCount() }.getOrDefault(0)
            _state.update { it.copy(dialog = LogoutDialog.Confirm(pending), everywhereFailed = false) }
        }
    }

    fun onSignOutEverywhereClick() = _state.update { it.copy(dialog = LogoutDialog.ConfirmEverywhere, everywhereFailed = false) }

    fun dismissDialog() = _state.update { it.copy(dialog = null) }

    fun confirm() {
        val dialog = _state.value.dialog ?: return
        _state.update { it.copy(dialog = null, working = true) }
        viewModelScope.launch {
            when (dialog) {
                is LogoutDialog.Confirm -> signOut()
                LogoutDialog.ConfirmEverywhere -> {
                    val ok = signOutEverywhere()
                    _state.update { it.copy(working = false, everywhereFailed = !ok) }
                }
            }
        }
    }

    fun onBackupClick(fromSignOut: Boolean = false) {
        signOutAfterBackup = fromSignOut
        _state.update { it.copy(dialog = null, backupDialog = BackupDialog.ExportPassword, backupMessage = null) }
    }

    /** Held until the user picks where to save; the screen opens the file picker right after this. */
    fun onExportPassword(password: String) {
        exportPassword = password.toCharArray()
        _state.update { it.copy(backupDialog = null) }
    }

    fun onExportTarget(uri: String?) {
        val password = exportPassword ?: return
        exportPassword = null
        if (uri == null) {
            password.fill('\u0000')
            signOutAfterBackup = false
            return
        }
        _state.update { it.copy(working = true) }
        viewModelScope.launch {
            val ok = backups.export(uri, password) is BackupResult.Done
            val reopenSignOut = ok && signOutAfterBackup
            signOutAfterBackup = false
            _state.update {
                it.copy(
                    working = false,
                    backupMessage = if (ok) BackupMessage.BACKED_UP else BackupMessage.BACKUP_FAILED,
                    dialog = if (reopenSignOut) LogoutDialog.Confirm(0) else null,
                )
            }
        }
    }

    fun onRestoreSource(uri: String?) {
        if (uri == null) return
        _state.update { it.copy(backupDialog = BackupDialog.RestorePassword(uri), backupMessage = null) }
    }

    fun onRestorePassword(password: String) {
        val dialog = _state.value.backupDialog as? BackupDialog.RestorePassword ?: return
        _state.update { it.copy(backupDialog = null, working = true) }
        viewModelScope.launch {
            val message = when (restoreBackup(dialog.sourceUri, password.toCharArray())) {
                is RestoreResult.Done -> BackupMessage.RESTORED
                RestoreResult.WrongPassword -> BackupMessage.WRONG_PASSWORD
                RestoreResult.NotABackup -> BackupMessage.NOT_A_BACKUP
                RestoreResult.NewerVersion -> BackupMessage.NEWER_VERSION
                RestoreResult.Failed -> BackupMessage.RESTORE_FAILED
            }
            _state.update { it.copy(working = false, backupMessage = message) }
        }
    }

    fun dismissBackupDialog() {
        signOutAfterBackup = false
        _state.update { it.copy(backupDialog = null) }
    }

    override fun onCleared() {
        exportPassword?.fill('\u0000')
    }
}
