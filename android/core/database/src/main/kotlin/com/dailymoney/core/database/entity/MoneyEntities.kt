package com.dailymoney.core.database.entity

import androidx.room.ColumnInfo
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
    indices = [Index("occurredAt"), Index("accountId"), Index("toAccountId"), Index("categoryId"), Index("dedupeHash")],
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
    /** LOCAL for personal rows; PENDING_SYNC / SYNCED / SYNC_FAILED for rows shared with a group. */
    val localSyncStatus: String = "LOCAL",
    val version: Int = 1,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    /** Comma-separated user ids when visibility is SELECTED_MEMBERS. */
    @ColumnInfo(defaultValue = "NULL") val allowedMemberIds: String? = null,
    /** The server's version of this row; 0 until the group has it. Sent as the base of the next edit. */
    @ColumnInfo(defaultValue = "0") val serverVersion: Int = 0,
    /** The creator's split as entered: `METHOD;userId=value,userId=value`. Null = not split. */
    @ColumnInfo(defaultValue = "NULL") val splitSpec: String? = null,
    /** MANUAL, RECURRING, OCR, STATEMENT_IMPORT or SMS. */
    @ColumnInfo(defaultValue = "MANUAL") val source: String = "MANUAL",
    /** Statement imports only: the same line is never imported twice. */
    @ColumnInfo(defaultValue = "NULL") val dedupeHash: String? = null,
    /** Sent to the server at least once (the reply may have been lost), so the group may have a copy. */
    @ColumnInfo(defaultValue = "0") val pushAttempted: Boolean = false,
)

/** "Anything mentioning [pattern] goes to [categoryId]" (Phase 11). */
@Entity(tableName = "category_rules", indices = [Index("categoryId")])
data class CategoryRuleEntity(
    @PrimaryKey val id: String,
    val pattern: String,
    val categoryId: String,
    val createdAt: Long,
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

/** A payment found in a bank SMS (Phase 12 SMS build). Only parsed fields; the message text is never stored. */
@Entity(tableName = "payment_suggestions", indices = [Index("status")])
data class PaymentSuggestionEntity(
    @PrimaryKey val id: String,
    val sender: String,
    /** DEBIT or CREDIT. */
    val type: String,
    val amountMinor: Long,
    val currency: String,
    val counterparty: String?,
    /** The payment's date as epoch day. */
    val epochDay: Long,
    val accountHint: String?,
    val receivedAt: Long,
    /** NEW, ADDED or DISMISSED. */
    val status: String,
)
