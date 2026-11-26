package com.dailymoney.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// Date-only fields are stored as epoch days (LocalDate.toEpochDay) so they never shift with time zones.

@Entity(tableName = "recurring_rules")
data class RecurringRuleEntity(
    @PrimaryKey val id: String,
    val name: String,
    val isSubscription: Boolean,
    val type: String,
    val amountMinor: Long,
    val currency: String,
    val categoryId: String?,
    val accountId: String,
    val toAccountId: String?,
    val note: String?,
    val paymentMethod: String?,
    val rrule: String,
    val startEpochDay: Long,
    val postedCount: Int,
    val autoPost: Boolean,
    val active: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val version: Int = 1,
)

@Entity(tableName = "savings_goals")
data class SavingsGoalEntity(
    @PrimaryKey val id: String,
    val name: String,
    val targetMinor: Long,
    val currency: String,
    val targetEpochDay: Long?,
    val colorIndex: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val version: Int = 1,
)

@Entity(tableName = "goal_contributions", indices = [Index("goalId")])
data class GoalContributionEntity(
    @PrimaryKey val id: String,
    val goalId: String,
    val amountMinor: Long,
    val epochDay: Long,
    val note: String?,
    val createdAt: Long,
    val deletedAt: Long? = null,
)

@Entity(tableName = "debts")
data class DebtEntity(
    @PrimaryKey val id: String,
    val person: String,
    val direction: String,
    val principalMinor: Long,
    val currency: String,
    val startEpochDay: Long,
    val dueEpochDay: Long?,
    val note: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val version: Int = 1,
    @ColumnInfo(defaultValue = "NULL") val phone: String? = null,
    @ColumnInfo(defaultValue = "NULL") val upiId: String? = null,
)

@Entity(tableName = "debt_repayments", indices = [Index("debtId")])
data class DebtRepaymentEntity(
    @PrimaryKey val id: String,
    val debtId: String,
    val amountMinor: Long,
    val epochDay: Long,
    val note: String?,
    val createdAt: Long,
    val deletedAt: Long? = null,
)

@Entity(tableName = "attachments", indices = [Index("entityId")])
data class AttachmentEntity(
    @PrimaryKey val id: String,
    val entityType: String,
    val entityId: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val createdAt: Long,
)
