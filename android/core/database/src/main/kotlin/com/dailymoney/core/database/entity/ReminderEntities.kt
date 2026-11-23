package com.dailymoney.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "reminders")
data class ReminderEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val title: String,
    val notes: String?,
    val amountMinor: Long?,
    val currency: String,
    val categoryId: String?,
    val accountId: String?,
    val startEpochDay: Long,
    /** Minutes after midnight; null = all day. */
    val timeMinutes: Int?,
    val endTimeMinutes: Int?,
    val location: String?,
    val rrule: String?,
    /** Comma-separated minutes before the occurrence. */
    val alertOffsets: String,
    val active: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val version: Int = 1,
)

@Entity(tableName = "reminder_steps", indices = [Index("reminderId")])
data class ReminderStepEntity(
    @PrimaryKey val id: String,
    val reminderId: String,
    val title: String,
    val daysBefore: Int,
    val position: Int,
)

/** User actions on one occurrence; primary key is (reminder, original date). */
@Entity(tableName = "reminder_occurrences", primaryKeys = ["reminderId", "originalEpochDay"])
data class OccurrenceRecordEntity(
    val reminderId: String,
    val originalEpochDay: Long,
    val action: String?,
    val completedAt: Long?,
    val snoozedUntil: Long?,
    /** ISO local date-time, e.g. 2026-11-24T18:00. */
    val rescheduledTo: String?,
    val paidAmountMinor: Long?,
    val transactionId: String?,
    /** Comma-separated step ids done for this occurrence. */
    val doneStepIds: String,
    val updatedAt: Long,
)

@Entity(tableName = "budget_alerts", primaryKeys = ["budgetId", "periodStartEpochDay"])
data class BudgetAlertEntity(
    val budgetId: String,
    val periodStartEpochDay: Long,
    val level: Int,
)
