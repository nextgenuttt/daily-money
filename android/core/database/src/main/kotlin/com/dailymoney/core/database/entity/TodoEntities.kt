package com.dailymoney.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "todos", indices = [Index("parentId"), Index("dueEpochDay")])
data class TodoEntity(
    @PrimaryKey val id: String,
    val title: String,
    val notes: String?,
    val priority: String,
    val category: String?,
    val startEpochDay: Long?,
    val dueEpochDay: Long?,
    val dueTimeMinutes: Int?,
    val rrule: String?,
    val anchorEpochDay: Long?,
    val alertOffsets: String,
    val parentId: String?,
    val completedAt: Long?,
    val snoozedUntil: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val version: Int = 1,
)

@Entity(tableName = "todo_checklist_items", indices = [Index("todoId")])
data class TodoChecklistItemEntity(
    @PrimaryKey val id: String,
    val todoId: String,
    val text: String,
    val done: Boolean,
    val position: Int,
)

@Entity(tableName = "todo_activity", indices = [Index("todoId")])
data class TodoActivityEntity(
    @PrimaryKey val id: String,
    val todoId: String,
    val type: String,
    val at: Long,
    val detail: String?,
)
