package com.dailymoney.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Group expenses as the server sent them, mine and other people's. Kept apart from `transactions`
 * so other people's spending never changes my balances, budgets or personal reports.
 */
@Entity(tableName = "shared_expenses", indices = [Index("groupId", "occurredAt")])
data class SharedExpenseEntity(
    @PrimaryKey val id: String,
    val groupId: String,
    val creatorName: String,
    val mine: Boolean,
    val type: String,
    val amountMinor: Long,
    val currency: String,
    val categoryName: String?,
    val categoryIcon: String?,
    val occurredAt: Long,
    val note: String?,
    val paymentMethod: String?,
    val visibility: String,
    val version: Int,
    @ColumnInfo(defaultValue = "''") val creatorId: String = "",
    /** EQUAL / PERCENT / AMOUNT / SHARES, or null when not split. */
    @ColumnInfo(defaultValue = "NULL") val splitMethod: String? = null,
    @ColumnInfo(defaultValue = "'APPROVED'") val approvalStatus: String = "APPROVED",
)

/** Approval history; only the creator and approvers receive it. */
@Entity(tableName = "shared_decisions", primaryKeys = ["expenseId", "version", "approverId"])
data class SharedDecisionEntity(
    val expenseId: String,
    val version: Int,
    val approverId: String,
    val approverName: String,
    val decision: String,
    val reason: String?,
    val decidedAt: Long,
)

@Entity(tableName = "shared_splits", primaryKeys = ["expenseId", "userId"], indices = [Index("userId")])
data class SharedSplitEntity(
    val expenseId: String,
    val userId: String,
    val shareMinor: Long,
    /** As the creator entered it (percent, share count or amount); null for equal splits. */
    val inputValue: String?,
)

@Entity(tableName = "sync_members", primaryKeys = ["groupId", "userId"])
data class SyncMemberEntity(
    val groupId: String,
    val userId: String,
    val name: String,
    val upiId: String?,
    @ColumnInfo(defaultValue = "NULL") val phone: String? = null,
)

@Entity(tableName = "settlements", indices = [Index("groupId")])
data class SettlementEntity(
    @PrimaryKey val id: String,
    val groupId: String,
    val payerId: String,
    val receiverId: String,
    val amountMinor: Long,
    val currency: String,
    val method: String,
    val status: String,
    val note: String?,
    val createdBy: String,
    val createdAt: Long,
)

/** Groups I belong to and what I may do there, replaced on every pull. */
@Entity(tableName = "sync_groups")
data class SyncGroupEntity(
    @PrimaryKey val id: String,
    val name: String,
    val currency: String,
    val canView: Boolean,
    val canAdd: Boolean,
    val canReport: Boolean,
    @ColumnInfo(defaultValue = "0") val canSplit: Boolean = false,
    @ColumnInfo(defaultValue = "'INSTANT'") val approvalMode: String = "INSTANT",
    @ColumnInfo(defaultValue = "1") val requiredApprovals: Int = 1,
    @ColumnInfo(defaultValue = "0") val isApprover: Boolean = false,
    @ColumnInfo(defaultValue = "0") val allowSelfApproval: Boolean = false,
)

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val key: String,
    val value: String,
)

/** The notification centre. [shown]: already popped as a system notification on this phone. */
@Entity(tableName = "notifications", indices = [Index("createdAt")])
data class NotificationEntity(
    @PrimaryKey val id: String,
    val groupId: String?,
    val type: String,
    val entityType: String?,
    val entityId: String?,
    val paramsJson: String,
    val createdAt: Long,
    val read: Boolean,
    val shown: Boolean = false,
)
