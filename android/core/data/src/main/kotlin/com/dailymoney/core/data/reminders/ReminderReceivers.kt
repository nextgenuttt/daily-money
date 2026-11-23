package com.dailymoney.core.data.reminders

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.dailymoney.core.data.R
import com.dailymoney.core.domain.money.MoneyFormatter
import com.dailymoney.core.domain.money.RecurringRepository
import com.dailymoney.core.domain.reminders.AlarmType
import com.dailymoney.core.domain.reminders.CompleteOccurrenceUseCase
import com.dailymoney.core.domain.reminders.Occurrence
import com.dailymoney.core.domain.reminders.OccurrenceStatus
import com.dailymoney.core.domain.reminders.ReminderGroup
import com.dailymoney.core.domain.reminders.ReminderMath
import com.dailymoney.core.domain.reminders.ReminderRepository
import com.dailymoney.core.domain.reminders.ReminderScheduler
import com.dailymoney.core.domain.reminders.UpdateOccurrenceUseCase
import com.dailymoney.core.domain.todo.CompleteTodoUseCase
import com.dailymoney.core.domain.todo.TodoRepository
import com.dailymoney.core.domain.todo.UpdateTodoUseCase
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ReminderReceiverDeps {
    fun reminders(): ReminderRepository
    fun recurring(): RecurringRepository
    fun scheduler(): ReminderScheduler
    fun complete(): CompleteOccurrenceUseCase
    fun update(): UpdateOccurrenceUseCase
    fun todos(): TodoRepository
    fun completeTodo(): CompleteTodoUseCase
    fun updateTodo(): UpdateTodoUseCase
    fun auth(): FirebaseAuth
    fun clock(): Clock
}

private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** Runs [block] off the main thread while keeping the broadcast alive (goAsync). */
private fun BroadcastReceiver.async(context: Context, block: suspend (ReminderReceiverDeps) -> Unit) {
    val pending = goAsync()
    val deps = EntryPointAccessors.fromApplication(context.applicationContext, ReminderReceiverDeps::class.java)
    receiverScope.launch {
        try {
            if (deps.auth().currentUser != null) block(deps)
        } finally {
            pending.finish()
        }
    }
}

/** An alarm fired: show the notification with actions that fit the reminder kind (spec §7, §18). */
class ReminderAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        val type = intent.getStringExtra(EXTRA_TYPE)?.let { runCatching { AlarmType.valueOf(it) }.getOrNull() } ?: return
        val sourceId = intent.getStringExtra(EXTRA_SOURCE) ?: return
        val date = LocalDate.ofEpochDay(intent.getLongExtra(EXTRA_DATE, 0))
        val stepId = intent.getStringExtra(EXTRA_STEP)
        async(context) { deps ->
            if (canPostNotifications(context)) {
                Channels.ensure(context)
                when (type) {
                    AlarmType.RENEWAL -> renewal(context, deps, key, sourceId, date)
                    AlarmType.TASK, AlarmType.TASK_SNOOZE -> task(context, deps, key, sourceId)
                    else -> reminder(context, deps, key, type, sourceId, date, stepId)
                }
            }
            deps.scheduler().reschedule()
        }
    }

    private suspend fun renewal(context: Context, deps: ReminderReceiverDeps, key: String, ruleId: String, date: LocalDate) {
        val rule = deps.recurring().all().firstOrNull { it.id == ruleId && it.active } ?: return
        val n = NotificationCompat.Builder(context, Channels.PAYMENTS)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(context.getString(R.string.notif_renews_tomorrow, rule.name))
            .setContentText(context.getString(R.string.notif_renewal_body, MoneyFormatter.format(rule.amountMinor, rule.currency), date.medium()))
            .setPublicVersion(publicVersion(context, Channels.PAYMENTS, context.getString(R.string.notif_public_payment)))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(AppLinks.open(context, AppLinks.OPEN_SUBSCRIPTIONS, requestKey = key))
            .setAutoCancel(true)
            .build()
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(context).notify(notificationId(ruleId, date), n)
    }

    private suspend fun task(context: Context, deps: ReminderReceiverDeps, key: String, todoId: String) {
        val todo = deps.todos().get(todoId)?.takeIf { !it.isDone } ?: return
        val due = todo.dueDate ?: return
        val time = todo.dueTime
        val whenText = if (time != null) {
            context.getString(R.string.notif_at, time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)))
        } else if (due == LocalDate.now(deps.clock())) {
            context.getString(R.string.notif_today)
        } else {
            due.medium()
        }
        val n = NotificationCompat.Builder(context, Channels.REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(todo.title)
            .setContentText(context.getString(R.string.notif_task_due, whenText))
            .setPublicVersion(publicVersion(context, Channels.REMINDERS, context.getString(R.string.notif_public_reminder)))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(AppLinks.open(context, AppLinks.OPEN_TODO, todoId, requestKey = key))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .addAction(0, context.getString(R.string.action_complete), action(context, ReminderActionReceiver.ACTION_TASK_DONE, todoId, 0, null, key))
            .addAction(0, context.getString(R.string.action_remind_later), action(context, ReminderActionReceiver.ACTION_TASK_SNOOZE, todoId, 0, null, key))
            .build()
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(context).notify(taskNotificationId(todoId), n)
    }

    private suspend fun reminder(
        context: Context,
        deps: ReminderReceiverDeps,
        key: String,
        type: AlarmType,
        reminderId: String,
        date: LocalDate,
        stepId: String?,
    ) {
        val reminder = deps.reminders().get(reminderId)?.takeIf { it.active } ?: return
        val record = deps.reminders().record(reminderId, date)
        if (record?.action != null) return
        val now = LocalDateTime.now(deps.clock())
        val occurrence = ReminderMath.occurrences(reminder, listOfNotNull(record).associateBy { it.originalDate }, date, date, now)
            .firstOrNull() ?: return
        val id = notificationId(reminderId, date)
        val epochDay = date.toEpochDay()
        val group = reminder.kind.group
        val channel = when (group) {
            ReminderGroup.PAYMENT, ReminderGroup.COLLECTION -> Channels.PAYMENTS
            ReminderGroup.CELEBRATION, ReminderGroup.EVENT -> Channels.EVENTS
            else -> Channels.REMINDERS
        }
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(context, channel, context.getString(R.string.notif_public_reminder)))
            .setContentIntent(AppLinks.open(context, AppLinks.OPEN_REMINDER, reminderId, epochDay, key))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)

        if (type == AlarmType.STEP && stepId != null) {
            val step = reminder.steps.firstOrNull { it.id == stepId } ?: return
            builder.setContentTitle(step.title)
                .setContentText(context.getString(R.string.notif_step_body, reminder.title, occurrence.date.medium()))
                .addAction(0, context.getString(R.string.action_done), action(context, ReminderActionReceiver.ACTION_STEP_DONE, reminderId, epochDay, stepId, key))
            @Suppress("MissingPermission")
            NotificationManagerCompat.from(context).notify(key.hashCode(), builder.build())
            return
        }

        builder.setContentTitle(reminder.title).setContentText(body(context, occurrence))
        when (group) {
            ReminderGroup.PAYMENT, ReminderGroup.COLLECTION -> {
                // Paying needs the amount/date sheet, so this action opens the app.
                val label = if (group == ReminderGroup.PAYMENT) R.string.action_mark_paid else R.string.action_mark_received
                builder.addAction(0, context.getString(label), AppLinks.open(context, AppLinks.OPEN_COMPLETE, reminderId, epochDay, "$key:complete"))
            }
            ReminderGroup.CELEBRATION ->
                builder.addAction(0, context.getString(R.string.action_mark_wished), action(context, ReminderActionReceiver.ACTION_DONE, reminderId, epochDay, null, key))
            ReminderGroup.APPOINTMENT -> {
                builder.addAction(0, context.getString(R.string.action_completed), action(context, ReminderActionReceiver.ACTION_DONE, reminderId, epochDay, null, key))
                builder.addAction(0, context.getString(R.string.action_reschedule), AppLinks.open(context, AppLinks.OPEN_REMINDER, reminderId, epochDay, "$key:reschedule"))
            }
            else ->
                builder.addAction(0, context.getString(R.string.action_done), action(context, ReminderActionReceiver.ACTION_DONE, reminderId, epochDay, null, key))
        }
        builder.addAction(0, context.getString(R.string.action_remind_later), action(context, ReminderActionReceiver.ACTION_SNOOZE, reminderId, epochDay, null, key))
        if (group == ReminderGroup.PAYMENT || group == ReminderGroup.APPOINTMENT) builder.priority = NotificationCompat.PRIORITY_HIGH
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(context).notify(id, builder.build())
    }

    private fun body(context: Context, o: Occurrence): String {
        val r = o.reminder
        val whenText = when (o.status) {
            OccurrenceStatus.DUE_TODAY, OccurrenceStatus.OVERDUE ->
                if (o.isAllDay) context.getString(R.string.notif_today) else context.getString(R.string.notif_at, o.at.toLocalTime().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)))
            else -> o.date.medium()
        }
        return listOfNotNull(
            r.amountMinor?.let { MoneyFormatter.format(it, r.currency) },
            whenText,
            r.location,
        ).joinToString(" · ")
    }

    private fun publicVersion(context: Context, channel: String, text: String) = NotificationCompat.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_stat_notify)
        .setContentTitle(context.getString(R.string.notif_app_name))
        .setContentText(text)
        .build()

    companion object {
        const val ACTION_FIRE = "com.dailymoney.action.REMINDER_ALARM"
        const val EXTRA_KEY = "key"
        const val EXTRA_TYPE = "type"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_DATE = "date"
        const val EXTRA_STEP = "step"

        fun notificationId(sourceId: String, date: LocalDate) = "$sourceId:$date".hashCode()
        fun taskNotificationId(todoId: String) = "task:$todoId".hashCode()
    }
}

/** Notification buttons that can be handled without opening the app. */
class ReminderActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val reminderId = intent.getStringExtra(EXTRA_REMINDER) ?: return
        val date = LocalDate.ofEpochDay(intent.getLongExtra(EXTRA_DATE, 0))
        val stepId = intent.getStringExtra(EXTRA_STEP)
        val notificationKey = intent.getStringExtra(EXTRA_NOTIFICATION_KEY)
        async(context) { deps ->
            when (intent.action) {
                ACTION_TASK_DONE -> {
                    deps.completeTodo()(reminderId)
                    NotificationManagerCompat.from(context).cancel(ReminderAlarmReceiver.taskNotificationId(reminderId))
                    return@async
                }
                ACTION_TASK_SNOOZE -> {
                    deps.updateTodo().snooze(reminderId)
                    NotificationManagerCompat.from(context).cancel(ReminderAlarmReceiver.taskNotificationId(reminderId))
                    return@async
                }
            }
            val reminder = deps.reminders().get(reminderId) ?: return@async
            when (intent.action) {
                ACTION_DONE -> deps.complete()(reminder, date)
                ACTION_STEP_DONE -> if (stepId != null) deps.update().toggleStep(reminderId, date, stepId)
                ACTION_SNOOZE -> {
                    val record = deps.reminders().record(reminderId, date)
                    val now = LocalDateTime.now(deps.clock())
                    ReminderMath.occurrences(reminder, listOfNotNull(record).associateBy { it.originalDate }, date, date, now)
                        .firstOrNull()?.let { deps.update().snooze(it) }
                }
            }
            val manager = NotificationManagerCompat.from(context)
            manager.cancel(ReminderAlarmReceiver.notificationId(reminderId, date))
            notificationKey?.let { manager.cancel(it.hashCode()) }
        }
    }

    companion object {
        const val ACTION_DONE = "com.dailymoney.action.REMINDER_DONE"
        const val ACTION_SNOOZE = "com.dailymoney.action.REMINDER_SNOOZE"
        const val ACTION_STEP_DONE = "com.dailymoney.action.REMINDER_STEP_DONE"
        const val ACTION_TASK_DONE = "com.dailymoney.action.TASK_DONE"
        const val ACTION_TASK_SNOOZE = "com.dailymoney.action.TASK_SNOOZE"
        const val EXTRA_REMINDER = "reminder"
        const val EXTRA_DATE = "date"
        const val EXTRA_STEP = "step"
        const val EXTRA_NOTIFICATION_KEY = "notification_key"
    }
}

private fun action(context: Context, action: String, reminderId: String, epochDay: Long, stepId: String?, key: String): PendingIntent {
    val intent = Intent(context, ReminderActionReceiver::class.java)
        .setAction(action)
        .setData(Uri.parse("dailymoney://action/$action/${Uri.encode(key)}"))
        .putExtra(ReminderActionReceiver.EXTRA_REMINDER, reminderId)
        .putExtra(ReminderActionReceiver.EXTRA_DATE, epochDay)
        .putExtra(ReminderActionReceiver.EXTRA_NOTIFICATION_KEY, key)
    stepId?.let { intent.putExtra(ReminderActionReceiver.EXTRA_STEP, it) }
    return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

/** Reboots, clock and time-zone changes and app updates clear or shift alarms, so plan them again. */
class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED, "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
            -> async(context) { it.scheduler().reschedule() }
        }
    }
}

private fun LocalDate.medium(): String = format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
