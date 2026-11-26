package com.dailymoney.core.data.sync

import com.dailymoney.core.data.money.stream
import com.dailymoney.core.data.money.toTransactionType
import com.dailymoney.core.database.DatabaseManager
import com.dailymoney.core.database.dao.SyncDao
import com.dailymoney.core.database.entity.SettlementEntity
import com.dailymoney.core.database.entity.SharedDecisionEntity
import com.dailymoney.core.database.entity.SharedExpenseEntity
import com.dailymoney.core.database.entity.SharedSplitEntity
import com.dailymoney.core.domain.money.PaymentMethod
import com.dailymoney.core.domain.sharing.ApprovalDecision
import com.dailymoney.core.domain.sharing.ApprovalMode
import com.dailymoney.core.domain.sharing.ApprovalStatus
import com.dailymoney.core.domain.sharing.GroupPerson
import com.dailymoney.core.domain.sharing.Me
import com.dailymoney.core.domain.sharing.SettleMethod
import com.dailymoney.core.domain.sharing.Settlement
import com.dailymoney.core.domain.sharing.SettlementStatus
import com.dailymoney.core.domain.sharing.Share
import com.dailymoney.core.domain.sharing.SharedExpense
import com.dailymoney.core.domain.sharing.SharedExpenseRepository
import com.dailymoney.core.domain.sharing.SharedGroup
import com.dailymoney.core.domain.sharing.Visibility
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

@Singleton
class RoomSharedExpenseRepository @Inject constructor(
    private val db: DatabaseManager,
) : SharedExpenseRepository {

    override fun observeGroups(): Flow<List<SharedGroup>> = combine(
        db.stream { syncDao().observeGroups() },
        db.stream { syncDao().observeMembers() },
        db.stream { syncDao().observeState(SyncDao.ME) },
    ) { groups, members, me ->
        val byGroup = members.groupBy { it.groupId }
        groups.map { g ->
            SharedGroup(
                g.id, g.name, g.currency, g.canView, g.canAdd, g.canReport, g.canSplit,
                byGroup[g.id].orEmpty().map { GroupPerson(it.userId, it.name, it.upiId, it.userId == me, it.phone) },
                approvalMode = enumOr(g.approvalMode, ApprovalMode.INSTANT),
                requiredApprovals = g.requiredApprovals,
                isApprover = g.isApprover,
                allowSelfApproval = g.allowSelfApproval,
            )
        }
    }

    override fun observeExpenses(groupId: String): Flow<List<SharedExpense>> = combine(
        db.stream { syncDao().observeShared(groupId) },
        db.stream { syncDao().observeSplits(groupId) },
        db.stream { syncDao().observeDecisions(groupId) },
    ) { rows, splits, decisions ->
        val byExpense = splits.groupBy { it.expenseId }
        val decided = decisions.groupBy { it.expenseId }
        rows.map { it.toDomain(byExpense[it.id].orEmpty(), decided[it.id].orEmpty()) }
    }

    override fun observeSettlements(groupId: String): Flow<List<Settlement>> =
        db.stream { syncDao().observeSettlements(groupId) }.map { rows -> rows.mapNotNull { it.toDomain() } }

    override fun observeMe(): Flow<Me?> = combine(
        db.stream { syncDao().observeState(SyncDao.ME) },
        db.stream { syncDao().observeState(SyncDao.MY_UPI) },
        db.stream { syncDao().observeState(SyncDao.MY_PHONE) },
    ) { me, upi, phone -> me?.let { Me(it, upi?.ifBlank { null }, phone?.ifBlank { null }) } }

    private fun SharedExpenseEntity.toDomain(splits: List<SharedSplitEntity>, decisions: List<SharedDecisionEntity>) = SharedExpense(
        id = id,
        groupId = groupId,
        creatorId = creatorId,
        creatorName = creatorName,
        mine = mine,
        type = type.toTransactionType(),
        amountMinor = amountMinor,
        currency = currency,
        categoryName = categoryName,
        categoryIcon = categoryIcon,
        occurredAt = Instant.ofEpochMilli(occurredAt),
        note = note,
        paymentMethod = paymentMethod?.let { runCatching { PaymentMethod.valueOf(it) }.getOrNull() },
        visibility = runCatching { Visibility.valueOf(visibility) }.getOrDefault(Visibility.ORGANIZATION),
        shares = splits.map { Share(it.userId, it.shareMinor) },
        approval = enumOr(approvalStatus, ApprovalStatus.APPROVED),
        decisions = decisions.map {
            ApprovalDecision(it.approverId, it.approverName, it.decision == "APPROVED", it.reason, Instant.ofEpochMilli(it.decidedAt), it.version)
        },
        version = version,
    )
}

internal inline fun <reified T : Enum<T>> enumOr(value: String, fallback: T): T = runCatching { enumValueOf<T>(value) }.getOrDefault(fallback)

internal fun SettlementEntity.toDomain(): Settlement? {
    val method = runCatching { SettleMethod.valueOf(method) }.getOrNull() ?: return null
    val status = runCatching { SettlementStatus.valueOf(status) }.getOrNull() ?: return null
    return Settlement(id, groupId, payerId, receiverId, amountMinor, currency, method, status, note, createdBy, Instant.ofEpochMilli(createdAt))
}
