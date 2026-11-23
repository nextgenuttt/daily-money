package com.dailymoney.core.domain.money

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanningTest {

    private val zone = ZoneId.of("Asia/Kolkata")

    @Test
    fun `monthly on the 31st clamps without drifting`() {
        val r = Recurrence(Frequency.MONTHLY)
        val start = LocalDate.of(2026, 1, 31)
        assertEquals(LocalDate.of(2026, 2, 28), r.occurrence(start, 1))
        assertEquals(LocalDate.of(2026, 3, 31), r.occurrence(start, 2))
        assertEquals(LocalDate.of(2028, 2, 29), Recurrence(Frequency.YEARLY).occurrence(LocalDate.of(2024, 2, 29), 4))
    }

    @Test
    fun `first occurrence on or after a date`() {
        val r = Recurrence(Frequency.WEEKLY, 2)
        val start = LocalDate.of(2026, 11, 2)
        assertEquals(0, r.firstIndexOnOrAfter(start, LocalDate.of(2026, 10, 1)))
        assertEquals(1, r.firstIndexOnOrAfter(start, LocalDate.of(2026, 11, 3)))
        assertEquals(1, r.firstIndexOnOrAfter(start, LocalDate.of(2026, 11, 16)))
        assertEquals(2, r.firstIndexOnOrAfter(start, LocalDate.of(2026, 11, 17)))
    }

    @Test
    fun `rrule round trip and monthly equivalent`() {
        val r = Recurrence(Frequency.WEEKLY, 2)
        assertEquals(r, Recurrence.parse(r.toRRule()))
        assertNull(Recurrence.parse("FREQ=HOURLY"))
        assertEquals(1000L, Recurrence(Frequency.YEARLY).monthlyEquivalent(12000))
        assertEquals(43333L, Recurrence(Frequency.WEEKLY).monthlyEquivalent(10000))
    }

    private class FakeRules(initial: List<RecurringRule>) : RecurringRepository {
        val list = initial.toMutableList()
        override fun observe(): Flow<List<RecurringRule>> = MutableStateFlow(list)
        override suspend fun all() = list.toList()
        override suspend fun save(rule: RecurringRule) {
            list.removeAll { it.id == rule.id }
            list += rule
        }
        override suspend fun delete(id: String) {
            list.removeAll { it.id == id }
        }
    }

    private class FakeTxns : TransactionRepository {
        val saved = linkedMapOf<String, Transaction>()
        override fun observeInRange(range: DateRange): Flow<List<Transaction>> = MutableStateFlow(saved.values.toList())
        override fun observeRecent(limit: Int): Flow<List<Transaction>> = MutableStateFlow(emptyList())
        override suspend fun get(id: String) = saved[id]
        override suspend fun save(transaction: Transaction) {
            saved[transaction.id] = transaction
        }
        override suspend fun delete(id: String) {
            saved.remove(id)
        }
    }

    private fun rule(start: LocalDate, posted: Int = 0, autoPost: Boolean = true, active: Boolean = true, sub: Boolean = false) = RecurringRule(
        id = "r1", name = "Netflix", isSubscription = sub, type = TransactionType.EXPENSE, amountMinor = 64900, currency = "INR",
        categoryId = "entertainment", accountId = "card", toAccountId = null, note = null, paymentMethod = PaymentMethod.CARD,
        recurrence = Recurrence(Frequency.MONTHLY), startDate = start, postedCount = posted, autoPost = autoPost, active = active,
    )

    @Test
    fun `posts every missed occurrence once, idempotently`() = runTest {
        val clock = Clock.fixed(Instant.parse("2026-11-22T06:00:00Z"), zone)
        val rules = FakeRules(listOf(rule(LocalDate.of(2026, 9, 5), sub = true)))
        val txns = FakeTxns()
        val use = PostDueRecurringUseCase(rules, txns, clock)

        assertEquals(3, use())
        assertEquals(0, use())
        assertEquals(3, txns.saved.size)
        assertEquals(3, rules.list.single().postedCount)
        assertEquals(LocalDate.of(2026, 12, 5), rules.list.single().nextPostDate)
        assertEquals("Netflix", txns.saved.values.first().note)
        assertEquals(LocalDate.of(2026, 9, 5), txns.saved.values.first().occurredAt.atZone(zone).toLocalDate())
    }

    @Test
    fun `restarting a schedule never overwrites earlier transactions`() = runTest {
        val clock = Clock.fixed(Instant.parse("2026-11-22T06:00:00Z"), zone)
        val rules = FakeRules(listOf(rule(LocalDate.of(2026, 10, 1))))
        val txns = FakeTxns()
        val use = PostDueRecurringUseCase(rules, txns, clock)
        use()
        // User edits the subscription: schedule restarts on a new date with postedCount = 0.
        rules.save(rules.list.single().copy(startDate = LocalDate.of(2026, 11, 20), postedCount = 0))
        use()
        assertEquals(3, txns.saved.size)
    }

    @Test
    fun `paused and tracked-only rules never post`() = runTest {
        val clock = Clock.fixed(Instant.parse("2026-11-22T06:00:00Z"), zone)
        val txns = FakeTxns()
        PostDueRecurringUseCase(FakeRules(listOf(rule(LocalDate.of(2026, 1, 1), active = false))), txns, clock)()
        PostDueRecurringUseCase(FakeRules(listOf(rule(LocalDate.of(2026, 1, 1), autoPost = false))), txns, clock)()
        assertTrue(txns.saved.isEmpty())
    }

    @Test
    fun `tracked-only subscription shows next renewal from today`() {
        val r = rule(LocalDate.of(2026, 1, 15), autoPost = false)
        assertEquals(LocalDate.of(2026, 12, 15), r.nextDueDate(LocalDate.of(2026, 11, 22)))
        assertEquals(LocalDate.of(2026, 11, 22), rule(LocalDate.of(2026, 1, 22), autoPost = false).nextDueDate(LocalDate.of(2026, 11, 22)))
    }

    @Test
    fun `upcoming payments within a week`() {
        val today = LocalDate.of(2026, 11, 22)
        val soon = rule(LocalDate.of(2026, 11, 25))
        val later = rule(LocalDate.of(2026, 12, 20)).copy(id = "r2")
        val income = rule(LocalDate.of(2026, 11, 23)).copy(id = "r3", type = TransactionType.INCOME)
        assertEquals(listOf("r1"), PlanningMath.upcoming(listOf(later, soon, income), today, 7).map { it.rule.id })
    }

    @Test
    fun `goal progress and monthly need`() {
        val goal = SavingsGoal("g", "Bike", 1_20_000_00, "INR", LocalDate.of(2027, 4, 30), 0)
        val p = GoalProgress(goal, listOf(GoalContribution("c1", "g", 20_000_00, LocalDate.of(2026, 11, 1), null), GoalContribution("c2", "g", -5_000_00, LocalDate.of(2026, 11, 2), null)))
        assertEquals(15_000_00, p.savedMinor)
        assertEquals(1_05_000_00, p.remainingMinor)
        // Nov..Apr = 6 months → 17,500 per month
        assertEquals(17_500_00L, p.monthlyNeeded(LocalDate.of(2026, 11, 22)))
        assertNull(p.copy(goal = goal.copy(targetDate = null)).monthlyNeeded(LocalDate.of(2026, 11, 22)))
        assertFalse(p.reached)
    }

    @Test
    fun `debt status and summary`() {
        val lent = DebtStatus(Debt("d1", "Arun", DebtDirection.LENT, 500000, "INR", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1), null),
            listOf(DebtRepayment("p1", "d1", 200000, LocalDate.of(2026, 10, 20), null)))
        val borrowed = DebtStatus(Debt("d2", "Priya", DebtDirection.BORROWED, 100000, "INR", LocalDate.of(2026, 11, 1), null, null),
            listOf(DebtRepayment("p2", "d2", 100000, LocalDate.of(2026, 11, 5), null)))
        assertEquals(300000, lent.outstandingMinor)
        assertTrue(lent.overdue(LocalDate.of(2026, 11, 22)))
        assertTrue(borrowed.settled)
        assertFalse(borrowed.overdue(LocalDate.of(2026, 11, 22)))
        assertEquals(DebtSummary(300000, 0), PlanningMath.debtSummary(listOf(lent, borrowed)))
    }

    @Test
    fun `csv escapes quotes and blocks formula injection`() {
        assertEquals("\"say \"\"hi\"\"\"", CsvExporter.escape("say \"hi\""))
        assertEquals("\"'=HYPERLINK(1)\"", CsvExporter.escape("=HYPERLINK(1)"))
        assertEquals("\"'-5\"", CsvExporter.escape("-5"))
        assertEquals("\"Food, snacks\"", CsvExporter.escape("Food, snacks"))
    }

    @Test
    fun `csv has bom, header and one CRLF row per transaction`() {
        val t = Transaction("t", TransactionType.EXPENSE, 123450, "INR", "food", "cash", null,
            LocalDate.of(2026, 11, 20).atTime(10, 0).atZone(zone).toInstant(), "Lunch", PaymentMethod.UPI)
        val food = Category("food", "Food", CategoryKind.EXPENSE, "food", 1, true)
        val cash = Account("cash", "Cash", AccountType.CASH, "INR", 0)
        val csv = CsvExporter.build(ExportData(LocalDate.MIN, LocalDate.MAX, listOf(TransactionDetails(t, food, cash, null)), Totals(0, 123450), "INR"), zone)
        assertTrue(csv.startsWith("﻿\"Date\""))
        val lines = csv.removePrefix("﻿").split("\r\n").filter { it.isNotEmpty() }
        assertEquals(2, lines.size)
        assertEquals("\"2026-11-20\",\"Expense\",\"1234.50\",\"INR\",\"Food\",\"Cash\",\"\",\"upi\",\"Lunch\"", lines[1])
    }

    @Test
    fun `export period ranges`() {
        val today = LocalDate.of(2026, 3, 15)
        assertEquals(LocalDate.of(2026, 2, 1) to LocalDate.of(2026, 2, 28), BuildExportUseCase.range(ExportPeriod.LAST_MONTH, today))
        assertEquals(LocalDate.of(2025, 3, 16) to today, BuildExportUseCase.range(ExportPeriod.LAST_12_MONTHS, today))
    }

    @Test
    fun `amount parsing helper`() {
        assertEquals(null to AmountError.REQUIRED, parsePositiveAmount(" ", "INR"))
        assertEquals(null to AmountError.INVALID, parsePositiveAmount("0", "INR"))
        assertEquals(1050L to null, parsePositiveAmount("10.5", "INR"))
    }
}
