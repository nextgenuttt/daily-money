package com.dailymoney.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.dailymoney.core.database.entity.AttachmentEntity
import com.dailymoney.core.database.entity.DebtEntity
import com.dailymoney.core.database.entity.DebtRepaymentEntity
import com.dailymoney.core.database.entity.GoalContributionEntity
import com.dailymoney.core.database.entity.RecurringRuleEntity
import com.dailymoney.core.database.entity.SavingsGoalEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RecurringDao {
    @Query("SELECT * FROM recurring_rules WHERE deletedAt IS NULL ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<RecurringRuleEntity>>

    @Query("SELECT * FROM recurring_rules WHERE deletedAt IS NULL")
    suspend fun all(): List<RecurringRuleEntity>

    @Query("SELECT * FROM recurring_rules WHERE id = :id")
    suspend fun get(id: String): RecurringRuleEntity?

    @Upsert
    suspend fun upsert(rule: RecurringRuleEntity)

    @Query("UPDATE recurring_rules SET deletedAt = :now, updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)
}

@Dao
interface GoalDao {
    @Query("SELECT * FROM savings_goals WHERE deletedAt IS NULL ORDER BY createdAt")
    fun observeGoals(): Flow<List<SavingsGoalEntity>>

    @Query("SELECT * FROM goal_contributions WHERE deletedAt IS NULL ORDER BY epochDay DESC, createdAt DESC")
    fun observeContributions(): Flow<List<GoalContributionEntity>>

    @Query("SELECT * FROM savings_goals WHERE id = :id")
    suspend fun get(id: String): SavingsGoalEntity?

    @Upsert
    suspend fun upsert(goal: SavingsGoalEntity)

    @Upsert
    suspend fun upsertContribution(contribution: GoalContributionEntity)

    @Query("UPDATE savings_goals SET deletedAt = :now, updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    @Query("UPDATE goal_contributions SET deletedAt = :now WHERE id = :id")
    suspend fun softDeleteContribution(id: String, now: Long)
}

@Dao
interface DebtDao {
    @Query("SELECT * FROM debts WHERE deletedAt IS NULL ORDER BY startEpochDay DESC")
    fun observeDebts(): Flow<List<DebtEntity>>

    @Query("SELECT * FROM debt_repayments WHERE deletedAt IS NULL ORDER BY epochDay DESC, createdAt DESC")
    fun observeRepayments(): Flow<List<DebtRepaymentEntity>>

    @Query("SELECT * FROM debts WHERE id = :id")
    suspend fun get(id: String): DebtEntity?

    @Upsert
    suspend fun upsert(debt: DebtEntity)

    @Upsert
    suspend fun upsertRepayment(repayment: DebtRepaymentEntity)

    @Query("UPDATE debts SET deletedAt = :now, updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    @Query("UPDATE debt_repayments SET deletedAt = :now WHERE id = :id")
    suspend fun softDeleteRepayment(id: String, now: Long)
}

@Dao
interface AttachmentDao {
    @Query("SELECT * FROM attachments WHERE entityId = :entityId ORDER BY createdAt")
    fun observeFor(entityId: String): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachments WHERE entityId = :entityId")
    suspend fun listFor(entityId: String): List<AttachmentEntity>

    @Query("SELECT * FROM attachments WHERE id = :id")
    suspend fun get(id: String): AttachmentEntity?

    @Upsert
    suspend fun upsert(attachment: AttachmentEntity)

    @Query("DELETE FROM attachments WHERE id = :id")
    suspend fun delete(id: String)
}
