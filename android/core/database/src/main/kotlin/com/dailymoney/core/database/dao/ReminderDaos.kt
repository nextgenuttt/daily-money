package com.dailymoney.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.dailymoney.core.database.entity.BudgetAlertEntity
import com.dailymoney.core.database.entity.OccurrenceRecordEntity
import com.dailymoney.core.database.entity.ReminderEntity
import com.dailymoney.core.database.entity.ReminderStepEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminders WHERE deletedAt IS NULL ORDER BY startEpochDay")
    fun observeReminders(): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminder_steps ORDER BY position")
    fun observeSteps(): Flow<List<ReminderStepEntity>>

    @Query("SELECT * FROM reminder_occurrences")
    fun observeRecords(): Flow<List<OccurrenceRecordEntity>>

    @Query("SELECT * FROM reminders WHERE deletedAt IS NULL")
    suspend fun reminders(): List<ReminderEntity>

    @Query("SELECT * FROM reminder_steps ORDER BY position")
    suspend fun steps(): List<ReminderStepEntity>

    @Query("SELECT * FROM reminder_occurrences")
    suspend fun records(): List<OccurrenceRecordEntity>

    @Query("SELECT * FROM reminders WHERE id = :id AND deletedAt IS NULL")
    suspend fun get(id: String): ReminderEntity?

    @Query("SELECT * FROM reminder_steps WHERE reminderId = :id ORDER BY position")
    suspend fun stepsFor(id: String): List<ReminderStepEntity>

    @Query("SELECT * FROM reminder_occurrences WHERE reminderId = :id AND originalEpochDay = :epochDay")
    suspend fun record(id: String, epochDay: Long): OccurrenceRecordEntity?

    @Upsert
    suspend fun upsert(reminder: ReminderEntity)

    @Upsert
    suspend fun upsertSteps(steps: List<ReminderStepEntity>)

    @Query("DELETE FROM reminder_steps WHERE reminderId = :id")
    suspend fun deleteSteps(id: String)

    @Transaction
    suspend fun saveWithSteps(reminder: ReminderEntity, steps: List<ReminderStepEntity>) {
        upsert(reminder)
        deleteSteps(reminder.id)
        upsertSteps(steps)
    }

    @Query("UPDATE reminders SET deletedAt = :now, updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    @Upsert
    suspend fun upsertRecord(record: OccurrenceRecordEntity)

    @Query("DELETE FROM reminder_occurrences WHERE reminderId = :id AND originalEpochDay = :epochDay")
    suspend fun deleteRecord(id: String, epochDay: Long)
}

@Dao
interface BudgetAlertDao {
    @Query("SELECT * FROM budget_alerts")
    suspend fun all(): List<BudgetAlertEntity>

    @Upsert
    suspend fun upsert(alert: BudgetAlertEntity)
}
