package com.dailymoney.core.data.notify

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.dailymoney.core.data.R
import com.dailymoney.core.data.reminders.AppLinks
import com.dailymoney.core.data.reminders.Channels
import com.dailymoney.core.data.reminders.canPostNotifications
import com.dailymoney.core.database.DatabaseManager
import com.dailymoney.core.domain.sharing.NotificationCategory
import com.dailymoney.core.domain.sharing.NotificationRules
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Clock
import java.time.Duration
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json

/**
 * After each pull: pops a system notification for anything new the person wants to hear about now
 * (LLD §10). Everything is marked shown either way, so muted or quiet-hour items never pop later;
 * they stay in the notification centre and the evening summary.
 */
@Singleton
class NotificationPoster @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: DatabaseManager,
    private val repository: RoomNotificationRepository,
    private val json: Json,
    private val clock: Clock,
) {
    suspend fun postNew() {
        val dao = db.get().syncDao()
        val fresh = dao.unshownSince(clock.millis() - MAX_AGE.toMillis())
        if (fresh.isEmpty()) return
        val prefs = repository.prefs()
        val now = LocalTime.now(clock)
        val allowed = canPostNotifications(context)
        if (allowed) Channels.ensure(context)
        fresh.forEach { row ->
            val n = row.toDomain(json) ?: return@forEach
            if (!allowed || !NotificationRules.shouldAlert(n, prefs, now)) return@forEach
            val channel = channelFor(n.type.category)
            val text = NotificationTexts.of(context, n)
            val notification = NotificationCompat.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setContentTitle(text.title)
                .setContentText(text.body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text.body))
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion(channel))
                .setWhen(n.createdAt.toEpochMilli())
                .setGroup(channel)
                .setContentIntent(AppLinks.openNotification(context, n.id))
                .setAutoCancel(true)
                .build()
            @Suppress("MissingPermission")
            NotificationManagerCompat.from(context).notify(n.id.hashCode(), notification)
        }
        dao.markShown(fresh.map { it.id })
    }

    /** Lock screen: what kind of update, never names or amounts. */
    private fun publicVersion(channel: String) = NotificationCompat.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_stat_notify)
        .setContentTitle(context.getString(R.string.notif_app_name))
        .setContentText(context.getString(R.string.notif_public_group))
        .build()

    private fun channelFor(category: NotificationCategory) = when (category) {
        NotificationCategory.APPROVALS -> Channels.APPROVALS
        NotificationCategory.PAYMENTS -> Channels.PAYMENTS
        NotificationCategory.MESSAGES -> Channels.MESSAGES
        NotificationCategory.GROUP_ACTIVITY -> Channels.GROUP_ACTIVITY
    }

    private companion object {
        /** A phone that was off for weeks doesn't flood the shade with old news. */
        val MAX_AGE: Duration = Duration.ofDays(3)
    }
}
