package com.dailymoney.core.domain.reminders

import com.dailymoney.core.domain.money.BudgetProgress
import com.dailymoney.core.domain.money.DateRange
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject

/** Highest threshold already announced for a budget in one period, so each level fires once. */
data class BudgetAlertMark(val budgetId: String, val periodStart: LocalDate, val level: Int)

data class BudgetAlert(val progress: BudgetProgress, val level: Int)

interface BudgetAlertStore {
    suspend fun marks(): List<BudgetAlertMark>
    suspend fun save(mark: BudgetAlertMark)
}

interface Notifier {
    fun budgetAlert(alert: BudgetAlert)
}

class CheckBudgetAlertsUseCase @Inject constructor(
    private val store: BudgetAlertStore,
    private val notifier: Notifier,
    private val clock: Clock,
) {
    suspend operator fun invoke(progress: List<BudgetProgress>) {
        val marks = store.marks().associateBy { it.budgetId to it.periodStart }
        val today = LocalDate.now(clock)
        decide(progress, marks, today, clock).forEach { (alert, mark) ->
            notifier.budgetAlert(alert)
            store.save(mark)
        }
    }

    companion object {
        val LEVELS = listOf(50, 75, 90, 100)

        /** Only the highest newly crossed level is announced (no burst of 50/75/90 at once). */
        fun decide(
            progress: List<BudgetProgress>,
            marks: Map<Pair<String, LocalDate>, BudgetAlertMark>,
            today: LocalDate,
            clock: Clock,
        ): List<Pair<BudgetAlert, BudgetAlertMark>> = progress.mapNotNull { p ->
            val start = DateRange.period(p.budget.period, today, clock.zone).start.atZone(clock.zone).toLocalDate()
            val percent = if (p.budget.limitMinor <= 0) 0 else (p.spentMinor * 100 / p.budget.limitMinor).toInt()
            val reached = LEVELS.lastOrNull { percent >= it } ?: return@mapNotNull null
            val already = marks[p.budget.id to start]?.level ?: 0
            if (reached <= already) null else BudgetAlert(p, reached) to BudgetAlertMark(p.budget.id, start, reached)
        }
    }
}
