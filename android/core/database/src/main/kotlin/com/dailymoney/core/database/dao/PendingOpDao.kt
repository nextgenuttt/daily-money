package com.dailymoney.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.dailymoney.core.database.entity.PendingOpEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingOpDao {

    @Query("SELECT COUNT(*) FROM pending_ops")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM pending_ops")
    fun observeCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(op: PendingOpEntity)

    @Query("SELECT * FROM pending_ops ORDER BY createdAt LIMIT :limit")
    suspend fun oldest(limit: Int): List<PendingOpEntity>

    @Query("DELETE FROM pending_ops WHERE opId IN (:opIds)")
    suspend fun delete(opIds: List<String>)
}
