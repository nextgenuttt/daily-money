package com.dailymoney.core.database.entity

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Column names follow the server tables in LLD §4.3 so the R2 sync can map rows 1:1.
 * Soft delete (deletedAt) keeps rows around until sync can tell the server.
 */

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,
    val currency: String,
    val openingMinor: Long,
    val archived: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val version: Int = 1,
)

@Entity(tableName = "categories", indices = [Index("kind")])
data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val kind: String,
    val iconKey: String,
    val colorIndex: Int,
    val isSystem: Boolean,
    val parentId: String?,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val version: Int = 1,
)

@Entity(
    tableName = "transactions",
    indices = [Index("occurredAt"), Index("accountId"), Index("toAccountId"), Index("categoryId")],
)
data class TransactionEntity(
    @PrimaryKey val id: String,
    val creatorId: String,
    val orgId: String? = null,
    /** DEBIT (expense), CREDIT (income) or TRANSFER, matching the server column. */
    val type: String,
    val amountMinor: Long,
    val currency: String,
    val categoryId: String?,
    val accountId: String,
    val toAccountId: String?,
    val occurredAt: Long,
    val note: String?,
    val paymentMethod: String?,
    val visibility: String = "PERSONAL",
    val approvalStatus: String = "APPROVED",
    /** LOCAL until the R2 sync engine exists; then PENDING_SYNC / SYNCED / CONFLICT. */
    val localSyncStatus: String = "LOCAL",
    val version: Int = 1,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

@Entity(tableName = "budgets", indices = [Index("categoryId")])
data class BudgetEntity(
    @PrimaryKey val id: String,
    val categoryId: String?,
    val period: String,
    val limitMinor: Long,
    val currency: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val version: Int = 1,
)

data class AccountWithBalance(
    @Embedded val account: AccountEntity,
    val balanceMinor: Long,
)
