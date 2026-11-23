package com.dailymoney.core.data.backup

import android.content.Context
import android.net.Uri
import com.dailymoney.core.data.money.io
import com.dailymoney.core.database.DatabaseManager
import com.dailymoney.core.domain.backup.BackupResult
import com.dailymoney.core.domain.backup.BackupService
import com.dailymoney.core.domain.backup.RestoreResult
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Writes/reads backups to files the user picks with the system file screens (no storage permission). */
@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: DatabaseManager,
    private val clock: Clock,
) : BackupService {

    private val codec = BackupCodec()

    override suspend fun export(targetUri: String, password: CharArray): BackupResult = io {
        try {
            val db = database.get()
            val snapshot = TableSnapshot.dump(db.openHelper.readableDatabase, db.openHelper.readableDatabase.version, clock.millis())
            val sealed = codec.seal(snapshot.toString().toByteArray(Charsets.UTF_8), password)
            context.contentResolver.openOutputStream(Uri.parse(targetUri), "wt").use { out ->
                requireNotNull(out).write(sealed)
            }
            BackupResult.Done(TableSnapshot.rowCount(snapshot))
        } catch (e: Exception) {
            BackupResult.Failed
        } finally {
            password.fill('\u0000')
        }
    }

    override suspend fun restore(sourceUri: String, password: CharArray): RestoreResult = io {
        try {
            val file = context.contentResolver.openInputStream(Uri.parse(sourceUri)).use { requireNotNull(it).readBytes() }
            val payload = codec.open(file, password)
            val snapshot: JsonObject = Json.parseToJsonElement(payload.toString(Charsets.UTF_8)).jsonObject
            val db = database.get().openHelper.writableDatabase
            if (TableSnapshot.schemaOf(snapshot) > db.version) return@io RestoreResult.NewerVersion
            TableSnapshot.restore(db, snapshot)
            RestoreResult.Done(TableSnapshot.rowCount(snapshot))
        } catch (e: BackupCodec.WrongPasswordException) {
            RestoreResult.WrongPassword
        } catch (e: BackupCodec.NotABackupException) {
            RestoreResult.NotABackup
        } catch (e: Exception) {
            RestoreResult.Failed
        } finally {
            password.fill('\u0000')
        }
    }
}
