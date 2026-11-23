package com.dailymoney.core.data.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.dailymoney.core.data.money.io
import com.dailymoney.core.domain.money.RecurringRepository
import com.dailymoney.core.domain.reminders.PlannedAlarm
import com.dailymoney.core.domain.reminders.ReminderMath
import com.dailymoney.core.domain.reminders.ReminderRepository
import com.dailymoney.core.domain.reminders.ReminderScheduler
import com.dailymoney.core.domain.todo.TodoRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps AlarmManager in step with the plan from [ReminderMath.plan] (next 30 days, max 200 alarms).
 * Exact alarms are used when the user allowed them (Android 12+ setting); otherwise the system may
 * deliver a few minutes late (doc 04/LLD §9.3).
 */
@Singleton
class AndroidReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val reminders: ReminderRepository,
    private val recurring: RecurringRepository,
    private val todos: TodoRepository,
    private val clock: Clock,
) : ReminderScheduler {

    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Mutex()

    override suspend fun reschedule() = lock.withLock {
        io {
            val plan = ReminderMath.plan(
                reminders = reminders.all(),
                records = reminders.allRecords(),
                subscriptions = recurring.all(),
                todos = todos.all(),
                now = clock.instant(),
                zone = clock.zone,
            )
            val wanted = plan.associateBy { it.key }
            val previous = prefs.getStringSet(KEY_SCHEDULED, emptySet()).orEmpty()
            (previous - wanted.keys).forEach(::cancel)
            plan.forEach(::schedule)
            prefs.edit().putStringSet(KEY_SCHEDULED, wanted.keys).apply()
        }
    }

    /** Sign-out: no alarm may fire for the previous user. */
    fun cancelAll() {
        prefs.getStringSet(KEY_SCHEDULED, emptySet()).orEmpty().forEach(::cancel)
        prefs.edit().clear().commit()
    }

    fun canScheduleExact(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    private fun schedule(alarm: PlannedAlarm) {
        val pending = pendingIntent(alarm.key, alarm, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        val at = alarm.at.toEpochMilli()
        try {
            if (canScheduleExact()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            }
        } catch (e: SecurityException) {
            // The user withdrew exact-alarm access between the check and the call.
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }

    private fun cancel(key: String) {
        pendingIntent(key, null, PendingIntent.FLAG_NO_CREATE)?.let {
            alarmManager.cancel(it)
            it.cancel()
        }
    }

    private fun pendingIntent(key: String, alarm: PlannedAlarm?, flag: Int): PendingIntent? {
        val intent = Intent(context, ReminderAlarmReceiver::class.java)
            .setAction(ReminderAlarmReceiver.ACTION_FIRE)
            // A unique data URI keeps one PendingIntent per alarm key.
            .setData(Uri.parse("dailymoney://alarm/${Uri.encode(key)}"))
        if (alarm != null) {
            intent.putExtra(ReminderAlarmReceiver.EXTRA_KEY, alarm.key)
                .putExtra(ReminderAlarmReceiver.EXTRA_TYPE, alarm.type.name)
                .putExtra(ReminderAlarmReceiver.EXTRA_SOURCE, alarm.sourceId)
                .putExtra(ReminderAlarmReceiver.EXTRA_DATE, alarm.originalDate.toEpochDay())
                .putExtra(ReminderAlarmReceiver.EXTRA_STEP, alarm.stepId)
        }
        return PendingIntent.getBroadcast(context, 0, intent, flag or PendingIntent.FLAG_IMMUTABLE)
    }

    private companion object {
        const val PREFS = "dm_alarms"
        const val KEY_SCHEDULED = "scheduled"
    }
}
