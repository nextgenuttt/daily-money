package com.dailymoney.core.domain.reminders

import com.dailymoney.core.domain.money.RecurringRule
import com.dailymoney.core.domain.todo.Todo
import com.dailymoney.core.domain.todo.TodoMath
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

enum class AlarmType { ALERT, STEP, SNOOZE, RENEWAL, TASK, TASK_SNOOZE }

/** One local notification to schedule. [key] is stable so re-planning can tell old alarms from new. */
data class PlannedAlarm(
    val key: String,
    val at: Instant,
    val type: AlarmType,
    val sourceId: String,
    val originalDate: LocalDate,
    val stepId: String? = null,
)

object ReminderMath {

    val ALL_DAY_ALERT_TIME: LocalTime = LocalTime.of(9, 0)

    /** How far back unfinished payments/tasks still count as overdue. */
    const val OVERDUE_WINDOW_DAYS = 60L

    fun occurrenceDates(reminder: Reminder, from: LocalDate, to: LocalDate): List<LocalDate> {
        val r = reminder.recurrence ?: return listOf(reminder.startDate).filter { !it.isBefore(from) && !it.isAfter(to) }
        val out = mutableListOf<LocalDate>()
        var n = r.firstIndexOnOrAfter(reminder.startDate, from)
        while (out.size < 1000) {
            val d = r.occurrence(reminder.startDate, n)
            if (d.isAfter(to)) break
            out += d
            n++
        }
        return out
    }

    /**
     * Occurrences whose ORIGINAL date is in [from, to]. Reschedules move the effective date but an
     * occurrence keeps its identity (original date), so history and completions stay attached.
     */
    fun occurrences(
        reminder: Reminder,
        records: Map<LocalDate, OccurrenceRecord>,
        from: LocalDate,
        to: LocalDate,
        now: LocalDateTime,
    ): List<Occurrence> = occurrenceDates(reminder, from, to).map { date ->
        val record = records[date]
        val at = record?.rescheduledTo ?: date.atTime(reminder.time ?: ALL_DAY_ALERT_TIME)
        Occurrence(reminder, date, at, status(reminder, record, at, now), record)
    }

    fun status(reminder: Reminder, record: OccurrenceRecord?, at: LocalDateTime, now: LocalDateTime): OccurrenceStatus {
        when (record?.action) {
            OccurrenceAction.DONE -> return OccurrenceStatus.DONE
            OccurrenceAction.SKIPPED -> return OccurrenceStatus.SKIPPED
            null -> Unit
        }
        val today = now.toLocalDate()
        val allDay = reminder.time == null && record?.rescheduledTo == null
        return when {
            at.toLocalDate().isAfter(today) -> OccurrenceStatus.UPCOMING
            at.toLocalDate() == today && (allDay || !at.isBefore(now) || !reminder.kind.tracksOverdue) -> OccurrenceStatus.DUE_TODAY
            reminder.kind.tracksOverdue && !at.toLocalDate().isBefore(today.minusDays(OVERDUE_WINDOW_DAYS)) -> OccurrenceStatus.OVERDUE
            else -> OccurrenceStatus.PAST
        }
    }

    /** First occurrence that still needs attention (today or later, not done/skipped). */
    fun nextOpen(reminder: Reminder, records: Map<LocalDate, OccurrenceRecord>, now: LocalDateTime): Occurrence? {
        val today = now.toLocalDate()
        var from = today.minusDays(1)
        repeat(5) {
            val to = from.plusYears(1)
            occurrences(reminder, records, from, to, now)
                .firstOrNull { it.status == OccurrenceStatus.UPCOMING || it.status == OccurrenceStatus.DUE_TODAY }
                ?.let { return it }
            if (reminder.recurrence == null) return null
            from = to.plusDays(1)
        }
        return null
    }

    /**
     * Alarms in (now, now + horizon]: alert offsets, prep steps (9:00 on the step's day), snoozes, and
     * subscription renewals (9:00 the day before), and open tasks' due-date alerts and snoozes.
     * Done or skipped occurrences get no alerts.
     */
    fun plan(
        reminders: List<Reminder>,
        records: List<OccurrenceRecord>,
        subscriptions: List<RecurringRule>,
        todos: List<Todo> = emptyList(),
        now: Instant,
        zone: ZoneId,
        horizonDays: Long = 30,
        limit: Int = 200,
    ): List<PlannedAlarm> {
        val localNow = LocalDateTime.ofInstant(now, zone)
        val until = now.plusSeconds(horizonDays * 86_400)
        val byReminder = records.groupBy { it.reminderId }.mapValues { (_, list) -> list.associateBy { it.originalDate } }
        val out = mutableListOf<PlannedAlarm>()

        fun add(key: String, at: LocalDateTime, type: AlarmType, id: String, date: LocalDate, stepId: String? = null) {
            val instant = at.atZone(zone).toInstant()
            if (instant.isAfter(now) && !instant.isAfter(until)) out += PlannedAlarm(key, instant, type, id, date, stepId)
        }

        reminders.filter { it.active }.forEach { r ->
            val recs = byReminder[r.id].orEmpty()
            val maxStep = r.steps.maxOfOrNull { it.daysBefore }?.toLong() ?: 0L
            val maxOffsetDays = (r.alertOffsets.maxOrNull() ?: 0) / 1440L + 1
            occurrences(r, recs, localNow.toLocalDate().minusDays(1), localNow.toLocalDate().plusDays(horizonDays + maxStep + maxOffsetDays), localNow)
                .forEach { o ->
                    val rec = o.record
                    if (rec?.action == null) {
                        r.alertOffsets.distinct().forEach { m -> add("A:${r.id}:${o.originalDate}:$m", o.at.minusMinutes(m.toLong()), AlarmType.ALERT, r.id, o.originalDate) }
                        r.steps.filter { it.id !in rec?.doneStepIds.orEmpty() }.forEach { s ->
                            add("S:${r.id}:${o.originalDate}:${s.id}", o.date.minusDays(s.daysBefore.toLong()).atTime(ALL_DAY_ALERT_TIME), AlarmType.STEP, r.id, o.originalDate, s.id)
                        }
                        rec?.snoozedUntil?.let { add("Z:${r.id}:${o.originalDate}:${it.epochSecond}", LocalDateTime.ofInstant(it, zone), AlarmType.SNOOZE, r.id, o.originalDate) }
                    }
                }
        }

        subscriptions.filter { it.isSubscription && it.active }.forEach { s ->
            val next = s.nextDueDate(localNow.toLocalDate())
            add("R:${s.id}:$next", next.minusDays(1).atTime(ALL_DAY_ALERT_TIME), AlarmType.RENEWAL, s.id, next)
        }
        todos.filter { !it.isDone && it.dueDate != null }.forEach { t ->
            TodoMath.alertTimes(t, zone).forEach { at ->
                add("T:${t.id}:${t.dueDate}:${at.epochSecond}", LocalDateTime.ofInstant(at, zone), AlarmType.TASK, t.id, t.dueDate!!)
            }
            t.snoozedUntil?.let { add("TZ:${t.id}:${it.epochSecond}", LocalDateTime.ofInstant(it, zone), AlarmType.TASK_SNOOZE, t.id, t.dueDate!!) }
        }
        return out.sortedBy { it.at }.take(limit)
    }

    /** Snooze target: 1 hour for timed items; 9:00 tomorrow for all-day ones. */
    fun snoozeUntil(occurrence: Occurrence, now: Instant, zone: ZoneId): Instant =
        if (occurrence.isAllDay) {
            LocalDateTime.ofInstant(now, zone).toLocalDate().plusDays(1).atTime(ALL_DAY_ALERT_TIME).atZone(zone).toInstant()
        } else {
            now.plusSeconds(3600)
        }
}
