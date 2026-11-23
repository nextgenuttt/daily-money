package com.dailymoney.core.data.planning

import com.dailymoney.core.data.money.io
import com.dailymoney.core.data.money.stream
import com.dailymoney.core.data.money.toDb
import com.dailymoney.core.data.money.toTransactionType
import com.dailymoney.core.database.DatabaseManager
import com.dailymoney.core.database.entity.DebtEntity
import com.dailymoney.core.database.entity.DebtRepaymentEntity
import com.dailymoney.core.database.entity.GoalContributionEntity
import com.dailymoney.core.database.entity.RecurringRuleEntity
import com.dailymoney.core.database.entity.SavingsGoalEntity
import com.dailymoney.core.domain.money.Debt
import com.dailymoney.core.domain.money.DebtDirection
import com.dailymoney.core.domain.money.DebtRepayment
import com.dailymoney.core.domain.money.DebtRepository
import com.dailymoney.core.domain.money.DebtStatus
import com.dailymoney.core.domain.money.Frequency
import com.dailymoney.core.domain.money.GoalContribution
import com.dailymoney.core.domain.money.GoalProgress
import com.dailymoney.core.domain.money.GoalRepository
import com.dailymoney.core.domain.money.PaymentMethod
import com.dailymoney.core.domain.money.Recurrence
import com.dailymoney.core.domain.money.RecurringRepository
import com.dailymoney.core.domain.money.RecurringRule
import com.dailymoney.core.domain.money.SavingsGoal
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

@Singleton
class RoomRecurringRepository @Inject constructor(
    private val db: DatabaseManager,
    private val clock: Clock,
) : RecurringRepository {

    override fun observe(): Flow<List<RecurringRule>> = db.stream { recurringDao().observeAll() }.map { it.map(::toDomain) }

    override suspend fun all(): List<RecurringRule> = io { db.get().recurringDao().all().map(::toDomain) }

    override suspend fun save(rule: RecurringRule) = io {
        val now = clock.millis()
        val existing = db.get().recurringDao().get(rule.id)
        db.get().recurringDao().upsert(
            RecurringRuleEntity(
                id = rule.id,
                name = rule.name,
                isSubscription = rule.isSubscription,
                type = rule.type.toDb(),
                amountMinor = rule.amountMinor,
                currency = rule.currency,
                categoryId = rule.categoryId,
                accountId = rule.accountId,
                toAccountId = rule.toAccountId,
                note = rule.note,
                paymentMethod = rule.paymentMethod?.name,
                rrule = rule.recurrence.toRRule(),
                startEpochDay = rule.startDate.toEpochDay(),
                postedCount = rule.postedCount,
                autoPost = rule.autoPost,
                active = rule.active,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
                version = (existing?.version ?: 0) + 1,
            ),
        )
    }

    override suspend fun delete(id: String) = io { db.get().recurringDao().softDelete(id, clock.millis()) }

    private fun toDomain(e: RecurringRuleEntity) = RecurringRule(
        id = e.id,
        name = e.name,
        isSubscription = e.isSubscription,
        type = e.type.toTransactionType(),
        amountMinor = e.amountMinor,
        currency = e.currency,
        categoryId = e.categoryId,
        accountId = e.accountId,
        toAccountId = e.toAccountId,
        note = e.note,
        paymentMethod = e.paymentMethod?.let { runCatching { PaymentMethod.valueOf(it) }.getOrNull() },
        recurrence = Recurrence.parse(e.rrule) ?: Recurrence(Frequency.MONTHLY),
        startDate = LocalDate.ofEpochDay(e.startEpochDay),
        postedCount = e.postedCount,
        autoPost = e.autoPost,
        active = e.active,
    )
}

@Singleton
class RoomGoalRepository @Inject constructor(
    private val db: DatabaseManager,
    private val clock: Clock,
) : GoalRepository {

    override fun observe(): Flow<List<GoalProgress>> = combine(
        db.stream { goalDao().observeGoals() },
        db.stream { goalDao().observeContributions() },
    ) { goals, contributions ->
        val byGoal = contributions.groupBy { it.goalId }
        goals.map { g ->
            GoalProgress(
                SavingsGoal(g.id, g.name, g.targetMinor, g.currency, g.targetEpochDay?.let(LocalDate::ofEpochDay), g.colorIndex),
                byGoal[g.id].orEmpty().map { GoalContribution(it.id, it.goalId, it.amountMinor, LocalDate.ofEpochDay(it.epochDay), it.note) },
            )
        }
    }

    override suspend fun save(goal: SavingsGoal) = io {
        val now = clock.millis()
        val existing = db.get().goalDao().get(goal.id)
        db.get().goalDao().upsert(
            SavingsGoalEntity(
                goal.id, goal.name, goal.targetMinor, goal.currency, goal.targetDate?.toEpochDay(), goal.colorIndex,
                createdAt = existing?.createdAt ?: now, updatedAt = now, version = (existing?.version ?: 0) + 1,
            ),
        )
    }

    override suspend fun delete(id: String) = io { db.get().goalDao().softDelete(id, clock.millis()) }

    override suspend fun addContribution(contribution: GoalContribution) = io {
        db.get().goalDao().upsertContribution(
            GoalContributionEntity(
                contribution.id, contribution.goalId, contribution.amountMinor, contribution.date.toEpochDay(),
                contribution.note, clock.millis(),
            ),
        )
    }

    override suspend fun deleteContribution(id: String) = io { db.get().goalDao().softDeleteContribution(id, clock.millis()) }
}

@Singleton
class RoomDebtRepository @Inject constructor(
    private val db: DatabaseManager,
    private val clock: Clock,
) : DebtRepository {

    override fun observe(): Flow<List<DebtStatus>> = combine(
        db.stream { debtDao().observeDebts() },
        db.stream { debtDao().observeRepayments() },
    ) { debts, repayments ->
        val byDebt = repayments.groupBy { it.debtId }
        debts.map { d ->
            DebtStatus(
                Debt(
                    d.id, d.person, runCatching { DebtDirection.valueOf(d.direction) }.getOrDefault(DebtDirection.LENT),
                    d.principalMinor, d.currency, LocalDate.ofEpochDay(d.startEpochDay), d.dueEpochDay?.let(LocalDate::ofEpochDay), d.note,
                ),
                byDebt[d.id].orEmpty().map { DebtRepayment(it.id, it.debtId, it.amountMinor, LocalDate.ofEpochDay(it.epochDay), it.note) },
            )
        }
    }

    override suspend fun save(debt: Debt) = io {
        val now = clock.millis()
        val existing = db.get().debtDao().get(debt.id)
        db.get().debtDao().upsert(
            DebtEntity(
                debt.id, debt.person, debt.direction.name, debt.principalMinor, debt.currency, debt.startDate.toEpochDay(),
                debt.dueDate?.toEpochDay(), debt.note,
                createdAt = existing?.createdAt ?: now, updatedAt = now, version = (existing?.version ?: 0) + 1,
            ),
        )
    }

    override suspend fun delete(id: String) = io { db.get().debtDao().softDelete(id, clock.millis()) }

    override suspend fun addRepayment(repayment: DebtRepayment) = io {
        db.get().debtDao().upsertRepayment(
            DebtRepaymentEntity(repayment.id, repayment.debtId, repayment.amountMinor, repayment.date.toEpochDay(), repayment.note, clock.millis()),
        )
    }

    override suspend fun deleteRepayment(id: String) = io { db.get().debtDao().softDeleteRepayment(id, clock.millis()) }
}
