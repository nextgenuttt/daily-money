package com.dailymoney.core.data.session

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.work.WorkManager
import com.dailymoney.core.data.planning.AttachmentStore
import com.dailymoney.core.data.reminders.AndroidReminderScheduler
import com.dailymoney.core.database.DatabaseManager
import com.dailymoney.core.domain.auth.LocalDataWiper
import com.dailymoney.core.domain.auth.PendingChangesRepository
import com.dailymoney.core.security.DeviceIdentity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sign-out wipe (doc 04 §4.6): the next person using this phone must not see the previous
 * user's money: reminder alarms, background jobs, notifications, database, receipts and caches.
 */
@Singleton
class LocalDataWiperImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: DatabaseManager,
    private val deviceIdentity: DeviceIdentity,
    private val reminderScheduler: AndroidReminderScheduler,
) : LocalDataWiper {

    override suspend fun wipe() = withContext(Dispatchers.IO) {
        runCatching { WorkManager.getInstance(context).cancelAllWork() }
        reminderScheduler.cancelAll()
        NotificationManagerCompat.from(context).cancelAll()
        database.closeAndDelete()
        deviceIdentity.clear()
        context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        java.io.File(context.filesDir, AttachmentStore.DIR).deleteRecursively()
        Unit
    }
}

@Singleton
class PendingChangesRepositoryImpl @Inject constructor(
    private val database: DatabaseManager,
) : PendingChangesRepository {

    override suspend fun pendingCount(): Int = withContext(Dispatchers.IO) {
        database.get().pendingOpDao().count()
    }
}
