package com.dailymoney.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Local outbox: one row per change waiting to be pushed to the server (LLD §6.1). */
@Entity(tableName = "pending_ops", indices = [Index("entityId")])
data class PendingOpEntity(
    @PrimaryKey val opId: String,
    val entityType: String,
    val entityId: String,
    val opType: String,
    val baseVersion: Int,
    val payloadJson: String,
    val createdAt: Long,
    val attempts: Int = 0,
    val lastError: String? = null,
)
