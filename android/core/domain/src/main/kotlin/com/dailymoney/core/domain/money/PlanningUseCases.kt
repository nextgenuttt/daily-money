package com.dailymoney.core.domain.money

import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Turns due recurring rules into transactions (app start + a daily background job).
 * Transaction ids are derived from (rule, occurrence), so running twice can never post twice.
 */
class PostDueRecurringUseCase @Inject constructor(
    private val rules: RecurringRepository,
    private val transactions: TransactionRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(): Int = lock.withLock {
        val today = LocalDate.now(clock)
        var posted = 0
        rules.all().filter { it.active && it.autoPost }.forEach { rule ->
            var count = rule.postedCount
            var guard = 0
            while (!rule.recurrence.occurrence(rule.startDate, count).isAfter(today) && guard < MAX_CATCH_UP) {
                val date = rule.recurrence.occurrence(rule.startDate, count)
                transactions.save(rule.toTransaction(occurrenceId(rule.id, rule.startDate, count), date))
                count++
                guard++
                posted++
            }
            if (count != rule.postedCount) rules.save(rule.copy(postedCount = count))
        }
        posted
    }

    private fun RecurringRule.toTransaction(id: String, date: LocalDate) = Transaction(
        id = id,
        type = type,
        amountMinor = amountMinor,
        currency = currency,
        categoryId = categoryId,
        accountId = accountId,
        toAccountId = toAccountId,
        // Midday avoids landing on the previous/next day if the phone's time zone changes.
        occurredAt = date.atTime(LocalTime.NOON).atZone(clock.zone).toInstant(),
        note = note ?: name.takeIf { isSubscription },
        paymentMethod = paymentMethod,
    )

    companion object {
        /** Max occurrences posted per rule in one run (e.g. after months offline). */
        const val MAX_CATCH_UP = 60
        private val lock = Mutex()

        /** Includes the start date: editing a subscription restarts its schedule, and must not reuse old ids. */
        fun occurrenceId(ruleId: String, start: LocalDate, index: Int): String =
            UUID.nameUUIDFromBytes("rule:$ruleId:${start.toEpochDay()}:$index".toByteArray(StandardCharsets.UTF_8)).toString()
    }
}

/** "Repeat" on a new transaction: the transaction just saved is occurrence 0 of the new rule. */
class CreateRecurringFromTransactionUseCase @Inject constructor(private val rules: RecurringRepository) {
    suspend operator fun invoke(transaction: Transaction, recurrence: Recurrence, startDate: LocalDate, name: String) {
        rules.save(
            RecurringRule(
                id = Ids.newId(),
                name = name,
                isSubscription = false,
                type = transaction.type,
                amountMinor = transaction.amountMinor,
                currency = transaction.currency,
                categoryId = transaction.categoryId,
                accountId = transaction.accountId,
                toAccountId = transaction.toAccountId,
                note = transaction.note,
                paymentMethod = transaction.paymentMethod,
                recurrence = recurrence,
                startDate = startDate,
                postedCount = 1,
                autoPost = true,
                active = true,
            ),
        )
    }
}

data class UpcomingPayment(val rule: RecurringRule, val date: LocalDate)

data class PlanningSummary(
    val debts: DebtSummary,
    val savedMinor: Long,
    val goalCount: Int,
    val upcoming: List<UpcomingPayment>,
    val subscriptionsMonthlyMinor: Long,
)

class ObservePlanningSummaryUseCase @Inject constructor(
    private val rules: RecurringRepository,
    private val goals: GoalRepository,
    private val debts: DebtRepository,
    private val clock: Clock,
) {
    operator fun invoke(): Flow<PlanningSummary> = combine(rules.observe(), goals.observe(), debts.observe()) { r, g, d ->
        val today = LocalDate.now(clock)
        PlanningSummary(
            debts = PlanningMath.debtSummary(d),
            savedMinor = g.sumOf { it.savedMinor },
            goalCount = g.size,
            upcoming = PlanningMath.upcoming(r, today, days = 7),
            subscriptionsMonthlyMinor = r.filter { it.isSubscription && it.active }.sumOf { it.recurrence.monthlyEquivalent(it.amountMinor) },
        )
    }
}

object PlanningMath {

    fun debtSummary(debts: List<DebtStatus>) = DebtSummary(
        toReceiveMinor = debts.filter { it.debt.direction == DebtDirection.LENT }.sumOf { it.outstandingMinor },
        toPayMinor = debts.filter { it.debt.direction == DebtDirection.BORROWED }.sumOf { it.outstandingMinor },
    )

    /** Expense rules due within [days] from today (inclusive), soonest first. */
    fun upcoming(rules: List<RecurringRule>, today: LocalDate, days: Long): List<UpcomingPayment> {
        val until = today.plusDays(days)
        return rules.filter { it.active && it.type == TransactionType.EXPENSE }
            .map { UpcomingPayment(it, it.nextDueDate(today)) }
            .filter { !it.date.isAfter(until) }
            .sortedBy { it.date }
    }
}

enum class AmountError { REQUIRED, INVALID }

/** Shared amount check for goals, contributions, debts and repayments. */
fun parsePositiveAmount(text: String, currency: String): Pair<Long?, AmountError?> {
    if (text.isBlank()) return null to AmountError.REQUIRED
    val minor = MoneyParser.parse(text, currency)
    return if (minor == null || minor <= 0) null to AmountError.INVALID else minor to null
}
