package com.dailymoney.core.domain.todo

import com.dailymoney.core.domain.money.Recurrence
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.flow.Flow

enum class Priority { LOW, MEDIUM, HIGH }

data class ChecklistItem(val id: String, val text: String, val done: Boolean)

data class Todo(
    val id: String,
    val title: String,
    val notes: String?,
    val priority: Priority,
    val category: String?,
    val startDate: LocalDate?,
    val dueDate: LocalDate?,
    val dueTime: LocalTime?,
    val recurrence: Recurrence?,
    /** First due date of a repeating task; later due dates are computed from it so they never drift. */
    val recurrenceAnchor: LocalDate?,
    val alertOffsets: List<Int>,
    val checklist: List<ChecklistItem>,
    val parentId: String?,
    val completedAt: Instant?,
    val snoozedUntil: Instant?,
    val createdAt: Instant,
) {
    val isDone: Boolean get() = completedAt != null
}

enum class TodoActivityType { CREATED, EDITED, COMPLETED, REOPENED, REPEATED }

data class TodoActivity(
    val id: String,
    val todoId: String,
    val type: TodoActivityType,
    val at: Instant,
    /** For REPEATED: the due date that was completed. */
    val detail: String?,
)

enum class TodoStatus { SOMEDAY, UPCOMING, DUE_TODAY, OVERDUE, DONE }

interface TodoRepository {
    fun observe(): Flow<List<Todo>>
    fun observeActivity(todoId: String): Flow<List<TodoActivity>>
    suspend fun all(): List<Todo>
    suspend fun get(id: String): Todo?
    suspend fun save(todo: Todo)

    /** Deletes the task and its subtasks. */
    suspend fun delete(id: String)
    suspend fun addActivity(activity: TodoActivity)
}

val DEFAULT_TODO_CATEGORIES = listOf("Personal", "Work", "Home", "Shopping", "Health", "Finance")
