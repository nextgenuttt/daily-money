package com.dailymoney.core.domain.money

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoneyMathTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val food = Category("food", "Food", CategoryKind.EXPENSE, "food", 1, true)
    private val rent = Category("rent", "Rent", CategoryKind.EXPENSE, "rent", 5, true)
    private val salary = Category("salary", "Salary", CategoryKind.INCOME, "salary", 3, true)
    private val cats = listOf(food, rent, salary).associateBy { it.id }

    private fun txn(type: TransactionType, amount: Long, cat: String?, date: String, time: String = "10:00") = Transaction(
        id = Ids.newId(),
        type = type,
        amountMinor = amount,
        currency = "INR",
        categoryId = cat,
        accountId = "cash",
        toAccountId = if (type == TransactionType.TRANSFER) "bank" else null,
        occurredAt = LocalDate.parse(date).atTime(java.time.LocalTime.parse(time)).atZone(zone).toInstant(),
        note = null,
        paymentMethod = null,
    )

    @Test
    fun `transfers are not income or spending`() {
        val totals = MoneyMath.totals(
            listOf(
                txn(TransactionType.INCOME, 5000000, "salary", "2026-11-01"),
                txn(TransactionType.EXPENSE, 120000, "food", "2026-11-02"),
                txn(TransactionType.TRANSFER, 1000000, null, "2026-11-03"),
            ),
        )
        assertEquals(5000000, totals.incomeMinor)
        assertEquals(120000, totals.expenseMinor)
        assertEquals(4880000, totals.netMinor)
    }

    @Test
    fun `category breakdown is sorted and shares add up`() {
        val slices = MoneyMath.byCategory(
            listOf(
                txn(TransactionType.EXPENSE, 30000, "food", "2026-11-02"),
                txn(TransactionType.EXPENSE, 70000, "rent", "2026-11-03"),
                txn(TransactionType.EXPENSE, 10000, "food", "2026-11-04"),
                txn(TransactionType.INCOME, 999999, "salary", "2026-11-04"),
            ),
            cats,
        )
        assertEquals(listOf("rent", "food"), slices.map { it.category?.id })
        assertEquals(70000, slices[0].amountMinor)
        assertEquals(1f, slices.sumOf { it.share.toDouble() }.toFloat(), 0.0001f)
    }

    @Test
    fun `month boundary uses local time zone`() {
        // 23:30 IST on 31 Oct is still October in India even though it's 18:00 UTC.
        val trend = MoneyMath.monthlyTrend(
            listOf(
                txn(TransactionType.EXPENSE, 100, "food", "2026-10-31", "23:30"),
                txn(TransactionType.EXPENSE, 200, "food", "2026-11-01", "00:15"),
            ),
            YearMonth.of(2026, 11), 2, zone,
        )
        assertEquals(YearMonth.of(2026, 10), trend[0].month)
        assertEquals(100, trend[0].expenseMinor)
        assertEquals(200, trend[1].expenseMinor)
    }

    @Test
    fun `budget progress per category and overall`() {
        val today = LocalDate.of(2026, 11, 22)
        val txns = listOf(
            txn(TransactionType.EXPENSE, 80000, "food", "2026-11-10"),
            txn(TransactionType.EXPENSE, 50000, "rent", "2026-11-12"),
            txn(TransactionType.EXPENSE, 99999, "food", "2026-10-10"),
        )
        val progress = MoneyMath.budgetProgress(
            listOf(
                Budget("b1", "food", BudgetPeriod.MONTHLY, 100000, "INR"),
                Budget("b2", null, BudgetPeriod.MONTHLY, 120000, "INR"),
            ),
            txns, cats, today, zone,
        )
        val overall = progress.first { it.budget.categoryId == null }
        val foodBudget = progress.first { it.budget.categoryId == "food" }
        assertEquals(progress.first(), overall)
        assertEquals(130000, overall.spentMinor)
        assertEquals(BudgetStatus.OVER, overall.status)
        assertEquals(80000, foodBudget.spentMinor)
        assertEquals(BudgetStatus.NEAR_LIMIT, foodBudget.status)
        assertEquals(20000, foodBudget.remainingMinor)
    }

    @Test
    fun `weekly range starts on Monday`() {
        val range = DateRange.period(BudgetPeriod.WEEKLY, LocalDate.of(2026, 11, 22), zone)
        assertEquals(LocalDate.of(2026, 11, 16).atStartOfDay(zone).toInstant(), range.start)
        assertEquals(LocalDate.of(2026, 11, 23).atStartOfDay(zone).toInstant(), range.endExclusive)
    }

    @Test
    fun `daily average uses days so far in the current month`() {
        val clock = Clock.fixed(Instant.parse("2026-11-10T06:00:00Z"), zone)
        assertEquals(1000, MoneyMath.dailyAverage(10000, YearMonth.of(2026, 11), clock))
        assertEquals(10000 / 31, MoneyMath.dailyAverage(10000, YearMonth.of(2026, 10), clock))
    }

    @Test
    fun `budget window covers week and year`() {
        val today = LocalDate.of(2026, 1, 1)
        val window = MoneyMath.budgetWindow(today, zone)
        assertTrue(window.start.isBefore(LocalDate.of(2026, 1, 1).atStartOfDay(zone).toInstant()))
    }
}
