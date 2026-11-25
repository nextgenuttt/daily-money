package com.dailymoney.core.data.reminders

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.dailymoney.core.data.R
import com.dailymoney.core.domain.money.MoneyFormatter
import com.dailymoney.core.domain.reminders.BudgetAlert
import com.dailymoney.core.domain.reminders.Notifier
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Channels the user can tune in system settings (doc LLD §10). */
object Channels {
    const val REMINDERS = "reminders"
    const val PAYMENTS = "payments"
    const val EVENTS = "events"
    const val BUDGET = "budget"
    const val APPROVALS = "approvals"
    const val GROUP_ACTIVITY = "group_activity"
    const val MESSAGES = "messages"

    fun ensure(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        listOf(
            NotificationChannel(PAYMENTS, context.getString(R.string.channel_payments), NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(REMINDERS, context.getString(R.string.channel_reminders), NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(EVENTS, context.getString(R.string.channel_events), NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(BUDGET, context.getString(R.string.channel_budget), NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(APPROVALS, context.getString(R.string.channel_approvals), NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(MESSAGES, context.getString(R.string.channel_messages), NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(GROUP_ACTIVITY, context.getString(R.string.channel_group_activity), NotificationManager.IMPORTANCE_LOW),
        ).forEach {
            // Amounts are hidden on the lock screen; the public version only says what kind of alert it is.
            it.lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
            manager.createNotificationChannel(it)
        }
    }
}

/** Intents that open the app; MainActivity reads the extras to show the right screen. */
object AppLinks {
    const val EXTRA_REMINDER_ID = "dm_reminder_id"
    const val EXTRA_ORIGINAL_DATE = "dm_original_date"
    const val EXTRA_OPEN = "dm_open"
    const val OPEN_REMINDER = "reminder"
    const val OPEN_COMPLETE = "complete"
    const val OPEN_SUBSCRIPTIONS = "subscriptions"
    const val OPEN_BUDGETS = "budgets"
    const val OPEN_TODO = "todo"
    const val EXTRA_TODO_ID = "dm_todo_id"
    const val OPEN_NOTIFICATION = "notification"
    const val EXTRA_NOTIFICATION_ID = "dm_notification_id"

    /** [id] null opens the notification centre itself (the evening summary). */
    fun openNotification(context: Context, id: String?): PendingIntent {
        val intent = (context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent())
            .setPackage(context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .setData(Uri.parse("dailymoney://open/$OPEN_NOTIFICATION/${Uri.encode(id ?: "all")}"))
            .putExtra(EXTRA_OPEN, OPEN_NOTIFICATION)
        id?.let { intent.putExtra(EXTRA_NOTIFICATION_ID, it) }
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun open(context: Context, what: String, reminderId: String? = null, epochDay: Long? = null, requestKey: String): PendingIntent {
        val intent = (context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent())
            .setPackage(context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .setData(Uri.parse("dailymoney://open/$what/${Uri.encode(requestKey)}"))
            .putExtra(EXTRA_OPEN, what)
        reminderId?.let { intent.putExtra(if (what == OPEN_TODO) EXTRA_TODO_ID else EXTRA_REMINDER_ID, it) }
        epochDay?.let { intent.putExtra(EXTRA_ORIGINAL_DATE, it) }
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}

fun canPostNotifications(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

@Singleton
class AndroidNotifier @Inject constructor(@ApplicationContext private val context: Context) : Notifier {

    override fun budgetAlert(alert: BudgetAlert) {
        if (!canPostNotifications(context)) return
        Channels.ensure(context)
        val p = alert.progress
        val name = p.category?.name ?: context.getString(R.string.notif_budget_overall)
        val title = if (alert.level >= 100) {
            context.getString(R.string.notif_budget_over, name)
        } else {
            context.getString(R.string.notif_budget_level, alert.level, name)
        }
        val body = context.getString(
            R.string.notif_budget_body,
            MoneyFormatter.format(p.spentMinor, p.budget.currency),
            MoneyFormatter.format(p.budget.limitMinor, p.budget.currency),
        )
        val key = "budget:${p.budget.id}"
        val notification = NotificationCompat.Builder(context, Channels.BUDGET)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(title)
            .setContentText(body)
            .setPublicVersion(publicVersion(Channels.BUDGET, context.getString(R.string.notif_public_budget)))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(AppLinks.open(context, AppLinks.OPEN_BUDGETS, requestKey = key))
            .setAutoCancel(true)
            .build()
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(context).notify(key.hashCode(), notification)
    }

    private fun publicVersion(channel: String, text: String) = NotificationCompat.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_stat_notify)
        .setContentTitle(context.getString(R.string.notif_app_name))
        .setContentText(text)
        .build()
}
