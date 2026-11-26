package com.dailymoney.core.domain.money

import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

data class Totals(val incomeMinor: Long, val expenseMinor: Long) {
    val netMinor: Long get() = incomeMinor - expenseMinor
}

data class CategorySlice(val category: Category?, val amountMinor: Long, val share: Float)

data class MonthPoint(val month: YearMonth, val incomeMinor: Long, val expenseMinor: Long)

enum class BudgetStatus { ON_TRACK, NEAR_LIMIT, OVER }

data class BudgetProgress(
    val budget: Budget,
    val category: Category?,
    val spentMinor: Long,
) {
    val fraction: Float get() = if (budget.limitMinor <= 0) 0f else spentMinor.toFloat() / budget.limitMinor
    val remainingMinor: Long get() = budget.limitMinor - spentMinor
    val status: BudgetStatus
        get() = when {
            spentMinor > budget.limitMinor -> BudgetStatus.OVER
            fraction >= 0.75f -> BudgetStatus.NEAR_LIMIT
            else -> BudgetStatus.ON_TRACK
        }
}

/** Pure calculations behind the dashboard, budgets and reports. Transfers never count as income or spending. */
object MoneyMath {

    fun totals(transactions: List<Transaction>): Totals {
        var income = 0L
        var expense = 0L
        transactions.forEach {
            when (it.type) {
                TransactionType.INCOME -> income += it.amountMinor
                TransactionType.EXPENSE -> expense += it.amountMinor
                TransactionType.TRANSFER -> Unit
            }
        }
        return Totals(income, expense)
    }

    fun byCategory(
        transactions: List<Transaction>,
        categories: Map<String, Category>,
        type: TransactionType = TransactionType.EXPENSE,
    ): List<CategorySlice> {
        val sums = transactions.filter { it.type == type }
            .groupBy { it.categoryId }
            .mapValues { (_, list) -> list.sumOf { it.amountMinor } }
        val total = sums.values.sum()
        if (total == 0L) return emptyList()
        return sums.map { (id, amount) -> CategorySlice(id?.let { categories[it] }, amount, amount.toFloat() / total) }
            .sortedByDescending { it.amountMinor }
    }

    fun monthlyTrend(transactions: List<Transaction>, lastMonth: YearMonth, months: Int, zone: ZoneId): List<MonthPoint> {
        val window = (months - 1 downTo 0).map { lastMonth.minusMonths(it.toLong()) }
        val grouped = transactions.groupBy { YearMonth.from(it.occurredAt.atZone(zone)) }
        return window.map { month ->
            val t = totals(grouped[month].orEmpty())
            MonthPoint(month, t.incomeMinor, t.expenseMinor)
        }
    }

    fun budgetProgress(
        budgets: List<Budget>,
        transactions: List<Transaction>,
        categories: Map<String, Category>,
        today: LocalDate,
        zone: ZoneId,
    ): List<BudgetProgress> = budgets.map { budget ->
        val range = DateRange.period(budget.period, today, zone)
        val spent = transactions.asSequence()
            .filter { it.type == TransactionType.EXPENSE && it.occurredAt in range }
            .filter { budget.categoryId == null || it.categoryId == budget.categoryId }
            .sumOf { it.amountMinor }
        BudgetProgress(budget, budget.categoryId?.let { categories[it] }, spent)
    }.sortedWith(compareBy<BudgetProgress> { it.budget.categoryId != null }.thenByDescending { it.fraction })

    /** Average spend per day so far this month (or over the whole month for past months). */
    fun dailyAverage(expenseMinor: Long, month: YearMonth, clock: Clock): Long {
        val today = LocalDate.now(clock)
        val days = if (YearMonth.from(today) == month) today.dayOfMonth else month.lengthOfMonth()
        return if (days == 0) 0 else expenseMinor / days
    }

    /** The widest range any budget may need, so one query covers all of them. */
    fun budgetWindow(today: LocalDate, zone: ZoneId): DateRange {
        val year = DateRange.period(BudgetPeriod.YEARLY, today, zone)
        val week = DateRange.period(BudgetPeriod.WEEKLY, today, zone)
        return DateRange(minOf(year.start, week.start), maxOf(year.endExclusive, week.endExclusive))
    }
}
