package com.dailymoney.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.dailymoney.core.database.entity.TodoActivityEntity
import com.dailymoney.core.database.entity.TodoChecklistItemEntity
import com.dailymoney.core.database.entity.TodoEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TodoDao {
    @Query("SELECT * FROM todos WHERE deletedAt IS NULL")
    fun observeTodos(): Flow<List<TodoEntity>>

    @Query("SELECT * FROM todo_checklist_items ORDER BY position")
    fun observeChecklist(): Flow<List<TodoChecklistItemEntity>>

    @Query("SELECT * FROM todo_activity WHERE todoId = :todoId ORDER BY at DESC LIMIT 50")
    fun observeActivity(todoId: String): Flow<List<TodoActivityEntity>>

    @Query("SELECT * FROM todos WHERE deletedAt IS NULL")
    suspend fun todos(): List<TodoEntity>

    @Query("SELECT * FROM todo_checklist_items ORDER BY position")
    suspend fun checklist(): List<TodoChecklistItemEntity>

    @Query("SELECT * FROM todos WHERE id = :id AND deletedAt IS NULL")
    suspend fun get(id: String): TodoEntity?

    @Query("SELECT * FROM todo_checklist_items WHERE todoId = :id ORDER BY position")
    suspend fun checklistFor(id: String): List<TodoChecklistItemEntity>

    @Upsert
    suspend fun upsert(todo: TodoEntity)

    @Upsert
    suspend fun upsertItems(items: List<TodoChecklistItemEntity>)

    @Query("DELETE FROM todo_checklist_items WHERE todoId = :id")
    suspend fun deleteItems(id: String)

    @Transaction
    suspend fun saveWithChecklist(todo: TodoEntity, items: List<TodoChecklistItemEntity>) {
        upsert(todo)
        deleteItems(todo.id)
        upsertItems(items)
    }

    /** Soft-deletes a task together with its subtasks. */
    @Query("UPDATE todos SET deletedAt = :now, updatedAt = :now WHERE id = :id OR parentId = :id")
    suspend fun softDeleteWithChildren(id: String, now: Long)

    @Upsert
    suspend fun addActivity(activity: TodoActivityEntity)
}
