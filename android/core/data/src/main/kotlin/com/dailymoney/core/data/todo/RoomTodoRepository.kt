package com.dailymoney.core.data.todo

import com.dailymoney.core.data.money.io
import com.dailymoney.core.data.money.stream
import com.dailymoney.core.database.DatabaseManager
import com.dailymoney.core.database.entity.TodoActivityEntity
import com.dailymoney.core.database.entity.TodoChecklistItemEntity
import com.dailymoney.core.database.entity.TodoEntity
import com.dailymoney.core.domain.money.Recurrence
import com.dailymoney.core.domain.todo.ChecklistItem
import com.dailymoney.core.domain.todo.Priority
import com.dailymoney.core.domain.todo.Todo
import com.dailymoney.core.domain.todo.TodoActivity
import com.dailymoney.core.domain.todo.TodoActivityType
import com.dailymoney.core.domain.todo.TodoRepository
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

@Singleton
class RoomTodoRepository @Inject constructor(
    private val db: DatabaseManager,
    private val clock: Clock,
) : TodoRepository {

    override fun observe(): Flow<List<Todo>> = combine(
        db.stream { todoDao().observeTodos() },
        db.stream { todoDao().observeChecklist() },
    ) { todos, items -> join(todos, items) }

    override fun observeActivity(todoId: String): Flow<List<TodoActivity>> = db.stream { todoDao().observeActivity(todoId) }.map { rows ->
        rows.map { TodoActivity(it.id, it.todoId, runCatching { TodoActivityType.valueOf(it.type) }.getOrDefault(TodoActivityType.EDITED), Instant.ofEpochMilli(it.at), it.detail) }
    }

    override suspend fun all(): List<Todo> = io { join(db.get().todoDao().todos(), db.get().todoDao().checklist()) }

    override suspend fun get(id: String): Todo? = io {
        val dao = db.get().todoDao()
        dao.get(id)?.let { join(listOf(it), dao.checklistFor(id)).single() }
    }

    override suspend fun save(todo: Todo) = io {
        val dao = db.get().todoDao()
        val existing = dao.get(todo.id)
        val now = clock.millis()
        dao.saveWithChecklist(
            TodoEntity(
                id = todo.id,
                title = todo.title,
                notes = todo.notes,
                priority = todo.priority.name,
                category = todo.category,
                startEpochDay = todo.startDate?.toEpochDay(),
                dueEpochDay = todo.dueDate?.toEpochDay(),
                dueTimeMinutes = todo.dueTime?.let { it.hour * 60 + it.minute },
                rrule = todo.recurrence?.toRRule(),
                anchorEpochDay = todo.recurrenceAnchor?.toEpochDay(),
                alertOffsets = todo.alertOffsets.joinToString(","),
                parentId = todo.parentId,
                completedAt = todo.completedAt?.toEpochMilli(),
                snoozedUntil = todo.snoozedUntil?.toEpochMilli(),
                createdAt = existing?.createdAt ?: todo.createdAt.toEpochMilli(),
                updatedAt = now,
                version = (existing?.version ?: 0) + 1,
            ),
            todo.checklist.mapIndexed { i, c -> TodoChecklistItemEntity(c.id, todo.id, c.text, c.done, i) },
        )
    }

    override suspend fun delete(id: String) = io { db.get().todoDao().softDeleteWithChildren(id, clock.millis()) }

    override suspend fun addActivity(activity: TodoActivity) = io {
        db.get().todoDao().addActivity(TodoActivityEntity(activity.id, activity.todoId, activity.type.name, activity.at.toEpochMilli(), activity.detail))
    }

    private fun join(todos: List<TodoEntity>, items: List<TodoChecklistItemEntity>): List<Todo> {
        val byTodo = items.groupBy { it.todoId }
        return todos.map { e ->
            Todo(
                id = e.id,
                title = e.title,
                notes = e.notes,
                priority = runCatching { Priority.valueOf(e.priority) }.getOrDefault(Priority.MEDIUM),
                category = e.category,
                startDate = e.startEpochDay?.let(LocalDate::ofEpochDay),
                dueDate = e.dueEpochDay?.let(LocalDate::ofEpochDay),
                dueTime = e.dueTimeMinutes?.let { LocalTime.of(it / 60, it % 60) },
                recurrence = e.rrule?.let(Recurrence::parse),
                recurrenceAnchor = e.anchorEpochDay?.let(LocalDate::ofEpochDay),
                alertOffsets = e.alertOffsets.split(',').mapNotNull { it.trim().toIntOrNull() },
                checklist = byTodo[e.id].orEmpty().map { ChecklistItem(it.id, it.text, it.done) },
                parentId = e.parentId,
                completedAt = e.completedAt?.let(Instant::ofEpochMilli),
                snoozedUntil = e.snoozedUntil?.let(Instant::ofEpochMilli),
                createdAt = Instant.ofEpochMilli(e.createdAt),
            )
        }
    }
}
