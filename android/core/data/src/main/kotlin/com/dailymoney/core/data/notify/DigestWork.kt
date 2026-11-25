package com.dailymoney.core.data.notify

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.dailymoney.core.data.R
import com.dailymoney.core.data.reminders.AppLinks
import com.dailymoney.core.data.reminders.Channels
import com.dailymoney.core.data.reminders.canPostNotifications
import com.dailymoney.core.database.DatabaseManager
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Evening summary: "You have N unread updates" when the person opted in (spec §17 digests). */
class DigestWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun databaseManager(): DatabaseManager
        fun notificationRepository(): RoomNotificationRepository
        fun auth(): FirebaseAuth
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java)
        if (deps.auth().currentUser == null || !deps.notificationRepository().prefs().dailyDigest) return Result.success()
        val unread = deps.databaseManager().get().syncDao().unreadCount()
        if (unread == 0 || !canPostNotifications(applicationContext)) return Result.success()
        Channels.ensure(applicationContext)
        val notification = NotificationCompat.Builder(applicationContext, Channels.GROUP_ACTIVITY)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(applicationContext.getString(R.string.notif_digest_title))
            .setContentText(applicationContext.resources.getQuantityString(R.plurals.notif_digest_body, unread, unread))
            .setContentIntent(AppLinks.openNotification(applicationContext, null))
            .setAutoCancel(true)
            .build()
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(applicationContext).notify(DIGEST_ID, notification)
        return Result.success()
    }

    private companion object {
        const val DIGEST_ID = 7_000_001
    }
}

@Singleton
class DigestScheduler @Inject constructor(@ApplicationContext private val context: Context) {

    fun schedule(enabled: Boolean) {
        val work = WorkManager.getInstance(context)
        if (!enabled) {
            work.cancelUniqueWork(NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<DigestWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(untilEvening().toMinutes(), TimeUnit.MINUTES)
            .build()
        work.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    private fun untilEvening(): Duration {
        val now = ZonedDateTime.now()
        var next = now.with(LocalDate.now()).with(DIGEST_TIME)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return Duration.between(now, next)
    }

    private companion object {
        const val NAME = "notification-digest"
        val DIGEST_TIME: LocalTime = LocalTime.of(20, 0)
    }
}
