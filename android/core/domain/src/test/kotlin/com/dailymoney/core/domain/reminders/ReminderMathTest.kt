package com.dailymoney.core.domain.reminders

import com.dailymoney.core.domain.money.Budget
import com.dailymoney.core.domain.money.BudgetPeriod
import com.dailymoney.core.domain.money.BudgetProgress
import com.dailymoney.core.domain.money.Frequency
import com.dailymoney.core.domain.money.PaymentMethod
import com.dailymoney.core.domain.money.Recurrence
import com.dailymoney.core.domain.money.RecurringRule
import com.dailymoney.core.domain.money.TransactionType
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderMathTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = LocalDateTime.of(2026, 11, 22, 10, 0)

    private fun reminder(
        kind: ReminderKind = ReminderKind.BILL,
        start: LocalDate = LocalDate.of(2026, 11, 25),
        time: LocalTime? = null,
        recurrence: Recurrence? = Recurrence(Frequency.MONTHLY),
        offsets: List<Int> = listOf(0, 1440),
        steps: List<PrepStep> = emptyList(),
    ) = Reminder("r1", kind, "Electricity", null, 200000, "INR", null, null, start, time, null, null, recurrence, offsets, steps)

    @Test
    fun `status rules`() {
        val bill = reminder()
        assertEquals(OccurrenceStatus.UPCOMING, ReminderMath.status(bill, null, LocalDate.of(2026, 11, 25).atTime(9, 0), now))
        assertEquals(OccurrenceStatus.DUE_TODAY, ReminderMath.status(bill, null, LocalDate.of(2026, 11, 22).atTime(9, 0), now))
        assertEquals(OccurrenceStatus.OVERDUE, ReminderMath.status(bill, null, LocalDate.of(2026, 11, 20).atTime(9, 0), now))
        // Timed bill earlier today is already overdue; a meeting earlier today is still "today".
        val timedBill = reminder(time = LocalTime.of(8, 0))
        assertEquals(OccurrenceStatus.OVERDUE, ReminderMath.status(timedBill, null, now.withHour(8), now))
        val meeting = reminder(kind = ReminderKind.MEETING, time = LocalTime.of(8, 0))
        assertEquals(OccurrenceStatus.DUE_TODAY, ReminderMath.status(meeting, null, now.withHour(8), now))
        // Past birthdays become history, not overdue.
        assertEquals(OccurrenceStatus.PAST, ReminderMath.status(reminder(kind = ReminderKind.BIRTHDAY), null, LocalDate.of(2026, 11, 1).atTime(9, 0), now))
        val done = OccurrenceRecord("r1", LocalDate.of(2026, 11, 20), action = OccurrenceAction.DONE)
        assertEquals(OccurrenceStatus.DONE, ReminderMath.status(bill, done, LocalDate.of(2026, 11, 20).atTime(9, 0), now))
    }

    @Test
    fun `next open skips done occurrences and honours reschedules`() {
        val bill = reminder(start = LocalDate.of(2026, 10, 22))
        val records = mapOf(LocalDate.of(2026, 11, 22) to OccurrenceRecord("r1", LocalDate.of(2026, 11, 22), action = OccurrenceAction.DONE))
        assertEquals(LocalDate.of(2026, 12, 22), ReminderMath.nextOpen(bill, records, now)!!.originalDate)

        val moved = mapOf(LocalDate.of(2026, 11, 22) to OccurrenceRecord("r1", LocalDate.of(2026, 11, 22), rescheduledTo = LocalDateTime.of(2026, 11, 24, 18, 0)))
        val next = ReminderMath.nextOpen(bill, moved, now)!!
        assertEquals(LocalDate.of(2026, 11, 22), next.originalDate)
        assertEquals(LocalDate.of(2026, 11, 24), next.date)
        assertEquals(OccurrenceStatus.UPCOMING, next.status)
    }

    @Test
    fun `one-time reminder in the past has no next`() {
        assertNull(ReminderMath.nextOpen(reminder(start = LocalDate.of(2026, 1, 1), recurrence = null), emptyMap(), now))
    }

    @Test
    fun `plan creates alerts, steps, snoozes and renewals within the horizon`() {
        val birthday = reminder(
            kind = ReminderKind.BIRTHDAY, start = LocalDate.of(2026, 12, 10), recurrence = Recurrence(Frequency.YEARLY),
            offsets = listOf(0), steps = listOf(PrepStep("gift", "Buy a gift", 5), PrepStep("cake", "Order cake", 1)),
        )
        val bill = reminder(start = LocalDate.of(2026, 11, 22), offsets = listOf(0))
        val snoozed = listOf(OccurrenceRecord("r1", LocalDate.of(2026, 11, 22), snoozedUntil = Instant.parse("2026-11-22T06:30:00Z")))
        val netflix = RecurringRule("s1", "Netflix", true, TransactionType.EXPENSE, 64900, "INR", null, "card", null, null, PaymentMethod.CARD,
            Recurrence(Frequency.MONTHLY), LocalDate.of(2026, 1, 5), 0, autoPost = false, active = true)

        val nowInstant = now.atZone(zone).toInstant()
        val alarms = ReminderMath.plan(listOf(birthday.copy(id = "b1")), emptyList(), listOf(netflix), now = nowInstant, zone = zone)
        val keys = alarms.map { it.key }
        assertTrue("A:b1:2026-12-10:0" in keys)
        assertTrue("S:b1:2026-12-10:gift" in keys)
        assertTrue("S:b1:2026-12-10:cake" in keys)
        assertTrue("R:s1:2026-12-05" in keys)
        assertEquals(alarms.sortedBy { it.at }, alarms)

        // Today 09:00 alert already passed, so only the snooze remains for today's bill.
        val billAlarms = ReminderMath.plan(listOf(bill), snoozed, emptyList(), now = nowInstant, zone = zone).filter { it.originalDate == LocalDate.of(2026, 11, 22) }
        assertEquals(listOf(AlarmType.SNOOZE), billAlarms.map { it.type })

        // Done occurrences get no alarms at all.
        val done = listOf(OccurrenceRecord("b1", LocalDate.of(2026, 12, 10), action = OccurrenceAction.DONE))
        assertTrue(ReminderMath.plan(listOf(birthday.copy(id = "b1")), done, emptyList(), now = nowInstant, zone = zone).none { it.originalDate == LocalDate.of(2026, 12, 10) })
    }

    @Test
    fun `snooze is an hour, or 9am tomorrow for all-day`() {
        val nowInstant = now.atZone(zone).toInstant()
        val allDay = ReminderMath.occurrences(reminder(start = LocalDate.of(2026, 11, 22)), emptyMap(), LocalDate.of(2026, 11, 22), LocalDate.of(2026, 11, 22), now).single()
        assertEquals(LocalDate.of(2026, 11, 23).atTime(9, 0).atZone(zone).toInstant(), ReminderMath.snoozeUntil(allDay, now = nowInstant, zone = zone))
        val timed = ReminderMath.occurrences(reminder(start = LocalDate.of(2026, 11, 22), time = LocalTime.of(11, 0)), emptyMap(), LocalDate.of(2026, 11, 22), LocalDate.of(2026, 11, 22), now).single()
        assertEquals(nowInstant.plusSeconds(3600), ReminderMath.snoozeUntil(timed, now = nowInstant, zone = zone))
    }

    @Test
    fun `overview groups overdue, today, upcoming and done`() {
        val overdueBill = reminder(start = LocalDate.of(2026, 11, 20), recurrence = null).copy(id = "a")
        val todayBill = reminder(start = LocalDate.of(2026, 11, 22), recurrence = null).copy(id = "b")
        val laterBill = reminder(start = LocalDate.of(2026, 12, 1), recurrence = null).copy(id = "c")
        val paid = reminder(start = LocalDate.of(2026, 11, 21), recurrence = null).copy(id = "d")
        val records = listOf(OccurrenceRecord("d", LocalDate.of(2026, 11, 21), action = OccurrenceAction.DONE, completedAt = Instant.EPOCH))
        val o = ObserveRemindersOverviewUseCase.build(listOf(overdueBill, todayBill, laterBill, paid), records, now)
        assertEquals(listOf("a"), o.overdue.map { it.reminder.id })
        assertEquals(listOf("b"), o.today.map { it.reminder.id })
        assertEquals(listOf("c"), o.upcoming.map { it.reminder.id })
        assertEquals(listOf("d"), o.done.map { it.reminder.id })
    }

    @Test
    fun `budget alerts fire once per level and only the highest new one`() {
        val clock = Clock.fixed(Instant.parse("2026-11-22T06:00:00Z"), zone)
        val budget = Budget("b", null, BudgetPeriod.MONTHLY, 100000, "INR")
        val today = LocalDate.of(2026, 11, 22)
        val first = CheckBudgetAlertsUseCase.decide(listOf(BudgetProgress(budget, null, 92000)), emptyMap(), today, clock)
        assertEquals(listOf(90), first.map { it.first.level })
        val marks = first.associate { (it.second.budgetId to it.second.periodStart) to it.second }
        assertTrue(CheckBudgetAlertsUseCase.decide(listOf(BudgetProgress(budget, null, 95000)), marks, today, clock).isEmpty())
        assertEquals(listOf(100), CheckBudgetAlertsUseCase.decide(listOf(BudgetProgress(budget, null, 100000)), marks, today, clock).map { it.first.level })
        assertTrue(CheckBudgetAlertsUseCase.decide(listOf(BudgetProgress(budget, null, 40000)), emptyMap(), today, clock).isEmpty())
    }

    @Test
    fun `birthday prep template matches the spec example`() {
        assertEquals(listOf(5, 1, 0), PrepTemplates.forKind(ReminderKind.BIRTHDAY).map { it.second })
    }
}
