package com.dailymoney.core.domain.todo

import com.dailymoney.core.domain.money.Ids
import com.dailymoney.core.domain.reminders.ReminderMath
import com.dailymoney.core.domain.reminders.ReminderScheduler
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

object TodoMath {

    fun status(todo: Todo, now: LocalDateTime): TodoStatus {
        if (todo.isDone) return TodoStatus.DONE
        val due = todo.dueDate ?: return TodoStatus.SOMEDAY
        val today = now.toLocalDate()
        return when {
            due.isBefore(today) -> TodoStatus.OVERDUE
            due == today && todo.dueTime != null && todo.dueTime.isBefore(now.toLocalTime()) -> TodoStatus.OVERDUE
            due == today -> TodoStatus.DUE_TODAY
            else -> TodoStatus.UPCOMING
        }
    }

    /** Share of checklist items and subtasks that are done; null when the task has neither. */
    fun progress(todo: Todo, subtasks: List<Todo>): Float? {
        val total = todo.checklist.size + subtasks.size
        if (total == 0) return null
        val done = todo.checklist.count { it.done } + subtasks.count { it.isDone }
        return done.toFloat() / total
    }

    /**
     * Next due date after completing a repeating task, computed from the anchor (monthly on the 31st
     * stays on month ends). Null when the task doesn't repeat or has no due date.
     */
    fun nextDue(todo: Todo): LocalDate? {
        val r = todo.recurrence ?: return null
        val due = todo.dueDate ?: return null
        val anchor = todo.recurrenceAnchor ?: due
        return r.occurrence(anchor, r.firstIndexOnOrAfter(anchor, due.plusDays(1)))
    }

    fun alertTimes(todo: Todo, zone: ZoneId): List<Instant> {
        val due = todo.dueDate ?: return emptyList()
        val at = due.atTime(todo.dueTime ?: ReminderMath.ALL_DAY_ALERT_TIME)
        return todo.alertOffsets.distinct().map { at.minusMinutes(it.toLong()).atZone(zone).toInstant() }
    }
}

enum class TodoError { TITLE_REQUIRED, TITLE_TOO_LONG, START_AFTER_DUE, CHECKLIST_INVALID }

class SaveTodoUseCase @Inject constructor(
    private val repository: TodoRepository,
    private val scheduler: ReminderScheduler,
    private val clock: Clock,
) {
    fun validate(t: Todo): Set<TodoError> = buildSet {
        when {
            t.title.isBlank() -> add(TodoError.TITLE_REQUIRED)
            t.title.trim().length > 100 -> add(TodoError.TITLE_TOO_LONG)
        }
        if (t.startDate != null && t.dueDate != null && t.startDate.isAfter(t.dueDate)) add(TodoError.START_AFTER_DUE)
        if (t.checklist.any { it.text.isBlank() || it.text.length > 100 }) add(TodoError.CHECKLIST_INVALID)
    }

    suspend operator fun invoke(t: Todo, isNew: Boolean): Set<TodoError> {
        val errors = validate(t)
        if (errors.isNotEmpty()) return errors
        val hasDue = t.dueDate != null
        repository.save(
            t.copy(
                title = t.title.trim(),
                notes = t.notes?.trim()?.ifEmpty { null },
                category = t.category?.trim()?.ifEmpty { null },
                // Repeat and alerts only make sense with a due date.
                recurrence = t.recurrence.takeIf { hasDue },
                recurrenceAnchor = if (hasDue && t.recurrence != null) (t.recurrenceAnchor ?: t.dueDate) else null,
                alertOffsets = if (hasDue) t.alertOffsets else emptyList(),
                dueTime = t.dueTime.takeIf { hasDue },
                checklist = t.checklist.map { it.copy(text = it.text.trim()) },
            ),
        )
        repository.addActivity(TodoActivity(Ids.newId(), t.id, if (isNew) TodoActivityType.CREATED else TodoActivityType.EDITED, clock.instant(), null))
        scheduler.reschedule()
        return emptySet()
    }
}

class CompleteTodoUseCase @Inject constructor(
    private val repository: TodoRepository,
    private val scheduler: ReminderScheduler,
    private val clock: Clock,
) {
    /**
     * A repeating task moves to its next due date with a fresh checklist (the completion is kept in
     * the activity history); a one-off task is marked done.
     */
    suspend operator fun invoke(id: String) {
        val t = repository.get(id) ?: return
        val now = clock.instant()
        val next = TodoMath.nextDue(t)
        if (next != null) {
            val shift = ChronoUnit.DAYS.between(t.dueDate, next)
            repository.save(
                t.copy(
                    dueDate = next,
                    startDate = t.startDate?.plusDays(shift),
                    checklist = t.checklist.map { it.copy(done = false) },
                    snoozedUntil = null,
                ),
            )
            repository.addActivity(TodoActivity(Ids.newId(), id, TodoActivityType.REPEATED, now, t.dueDate.toString()))
        } else {
            repository.save(t.copy(completedAt = now, snoozedUntil = null))
            repository.addActivity(TodoActivity(Ids.newId(), id, TodoActivityType.COMPLETED, now, null))
        }
        scheduler.reschedule()
    }
}

class ReopenTodoUseCase @Inject constructor(
    private val repository: TodoRepository,
    private val scheduler: ReminderScheduler,
    private val clock: Clock,
) {
    suspend operator fun invoke(id: String) {
        val t = repository.get(id) ?: return
        if (!t.isDone) return
        repository.save(t.copy(completedAt = null))
        repository.addActivity(TodoActivity(Ids.newId(), id, TodoActivityType.REOPENED, clock.instant(), null))
        scheduler.reschedule()
    }
}

class UpdateTodoUseCase @Inject constructor(
    private val repository: TodoRepository,
    private val scheduler: ReminderScheduler,
    private val clock: Clock,
) {
    suspend fun toggleChecklistItem(todoId: String, itemId: String) {
        val t = repository.get(todoId) ?: return
        repository.save(t.copy(checklist = t.checklist.map { if (it.id == itemId) it.copy(done = !it.done) else it }))
    }

    /** 1 hour for timed tasks; 9:00 tomorrow otherwise. */
    suspend fun snooze(todoId: String) {
        val t = repository.get(todoId) ?: return
        val now = clock.instant()
        val until = if (t.dueTime != null) {
            now.plusSeconds(3600)
        } else {
            LocalDate.now(clock).plusDays(1).atTime(ReminderMath.ALL_DAY_ALERT_TIME).atZone(clock.zone).toInstant()
        }
        repository.save(t.copy(snoozedUntil = until))
        scheduler.reschedule()
    }

    suspend fun delete(todoId: String) {
        repository.delete(todoId)
        scheduler.reschedule()
    }
}

data class TodoListItem(val todo: Todo, val status: TodoStatus, val progress: Float?, val subtaskCount: Int)

data class TodoOverview(
    val overdue: List<TodoListItem>,
    val today: List<TodoListItem>,
    val upcoming: List<TodoListItem>,
    val someday: List<TodoListItem>,
    val completed: List<TodoListItem>,
    val categories: List<String>,
) {
    val openCount get() = overdue.size + today.size + upcoming.size + someday.size
}

class ObserveTodoOverviewUseCase @Inject constructor(
    private val repository: TodoRepository,
    private val clock: Clock,
) {
    operator fun invoke(): Flow<TodoOverview> = repository.observe().map { build(it, LocalDateTime.now(clock), clock.zone) }

    companion object {
        /** Top-level tasks only; subtasks show inside their parent. Completed = last 30 days. */
        fun build(all: List<Todo>, now: LocalDateTime, zone: ZoneId): TodoOverview {
            val children = all.filter { it.parentId != null }.groupBy { it.parentId }
            val items = all.filter { it.parentId == null }.map { t ->
                val subs = children[t.id].orEmpty()
                TodoListItem(t, TodoMath.status(t, now), TodoMath.progress(t, subs), subs.size)
            }
            val byDue = compareBy<TodoListItem>({ it.todo.dueDate }, { it.todo.dueTime }, { -it.todo.priority.ordinal })
            val byPriority = compareBy<TodoListItem>({ -it.todo.priority.ordinal }, { it.todo.createdAt })
            val cutoff = now.toLocalDate().minusDays(30)
            return TodoOverview(
                overdue = items.filter { it.status == TodoStatus.OVERDUE }.sortedWith(byDue),
                today = items.filter { it.status == TodoStatus.DUE_TODAY }.sortedWith(compareBy<TodoListItem>({ it.todo.dueTime == null }, { it.todo.dueTime }, { -it.todo.priority.ordinal })),
                upcoming = items.filter { it.status == TodoStatus.UPCOMING }.sortedWith(byDue),
                someday = items.filter { it.status == TodoStatus.SOMEDAY }.sortedWith(byPriority),
                completed = items.filter { it.status == TodoStatus.DONE && !it.todo.completedAt!!.atZone(zone).toLocalDate().isBefore(cutoff) }
                    .sortedByDescending { it.todo.completedAt },
                categories = (DEFAULT_TODO_CATEGORIES + all.mapNotNull { it.category }).distinct(),
            )
        }
    }
}

class ObserveTodoUseCase @Inject constructor(private val repository: TodoRepository, private val clock: Clock) {
    data class Details(val item: TodoListItem, val subtasks: List<TodoListItem>, val activity: List<TodoActivity>)

    operator fun invoke(id: String): Flow<Details?> = combine(repository.observe(), repository.observeActivity(id)) { all, activity ->
        val t = all.firstOrNull { it.id == id } ?: return@combine null
        val now = LocalDateTime.now(clock)
        val subs = all.filter { it.parentId == id }
        Details(
            item = TodoListItem(t, TodoMath.status(t, now), TodoMath.progress(t, subs), subs.size),
            subtasks = subs.sortedWith(compareBy<Todo>({ it.isDone }, { it.dueDate }, { it.createdAt }))
                .map { TodoListItem(it, TodoMath.status(it, now), TodoMath.progress(it, emptyList()), 0) },
            activity = activity.sortedByDescending { it.at },
        )
    }
}

/** Title-only task from the quick-add field: no date, medium priority. */
class QuickAddTodoUseCase @Inject constructor(private val save: SaveTodoUseCase, private val clock: Clock) {
    suspend operator fun invoke(title: String, parentId: String? = null, dueDate: LocalDate? = null): Boolean {
        if (title.isBlank()) return false
        val todo = Todo(
            id = Ids.newId(), title = title, notes = null, priority = Priority.MEDIUM, category = null, startDate = null,
            dueDate = dueDate, dueTime = null, recurrence = null, recurrenceAnchor = null,
            alertOffsets = if (dueDate != null) listOf(0) else emptyList(),
            checklist = emptyList(), parentId = parentId, completedAt = null, snoozedUntil = null, createdAt = clock.instant(),
        )
        return save(todo, isNew = true).isEmpty()
    }
}
