package com.dailymoney.core.domain.backup

import com.dailymoney.core.domain.reminders.ReminderScheduler
import javax.inject.Inject

sealed interface BackupResult {
    data class Done(val rows: Int) : BackupResult
    data object Failed : BackupResult
}

sealed interface RestoreResult {
    data class Done(val rows: Int) : RestoreResult
    data object WrongPassword : RestoreResult
    data object NotABackup : RestoreResult
    /** Made by a newer app version; update the app first. */
    data object NewerVersion : RestoreResult
    data object Failed : RestoreResult
}

/**
 * Encrypted backup of everything personal on the phone (receipt files are not included).
 * Spec §22 data export + Phase 12 backup/restore; offered before sign-out because R1 data lives only here.
 */
interface BackupService {
    suspend fun export(targetUri: String, password: CharArray): BackupResult
    suspend fun restore(sourceUri: String, password: CharArray): RestoreResult
}

object BackupRules {
    const val MIN_PASSWORD = 8

    fun passwordOk(password: String) = password.length >= MIN_PASSWORD
}

class RestoreBackupUseCase @Inject constructor(
    private val backups: BackupService,
    private val scheduler: ReminderScheduler,
) {
    suspend operator fun invoke(sourceUri: String, password: CharArray): RestoreResult {
        val result = backups.restore(sourceUri, password)
        // Restored reminders and tasks need their alarms planned again.
        if (result is RestoreResult.Done) scheduler.reschedule()
        return result
    }
}
