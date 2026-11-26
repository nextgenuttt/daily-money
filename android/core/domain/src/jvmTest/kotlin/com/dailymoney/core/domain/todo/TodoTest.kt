package com.dailymoney.core.domain.todo

import com.dailymoney.core.domain.money.Frequency
import com.dailymoney.core.domain.money.Recurrence
import com.dailymoney.core.domain.reminders.AlarmType
import com.dailymoney.core.domain.reminders.ReminderMath
import com.dailymoney.core.domain.reminders.ReminderScheduler
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TodoTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = LocalDateTime.of(2026, 11, 23, 10, 0)
    private val clock = Clock.fixed(now.atZone(zone).toInstant(), zone)

    private fun todo(
        id: String = "t1",
        due: LocalDate? = null,
        time: LocalTime? = null,
        recurrence: Recurrence? = null,
        anchor: LocalDate? = null,
        checklist: List<ChecklistItem> = emptyList(),
        parent: String? = null,
        done: Boolean = false,
        priority: Priority = Priority.MEDIUM,
    ) = Todo(
        id, "Pay school fees", null, priority, null, null, due, time, recurrence, anchor, listOf(0), checklist, parent,
        if (done) Instant.parse("2026-11-22T05:00:00Z") else null, null, Instant.EPOCH,
    )

    @Test
    fun `status rules`() {
        assertEquals(TodoStatus.SOMEDAY, TodoMath.status(todo(), now))
        assertEquals(TodoStatus.UPCOMING, TodoMath.status(todo(due = LocalDate.of(2026, 11, 24)), now))
        assertEquals(TodoStatus.DUE_TODAY, TodoMath.status(todo(due = LocalDate.of(2026, 11, 23)), now))
        assertEquals(TodoStatus.OVERDUE, TodoMath.status(todo(due = LocalDate.of(2026, 11, 23), time = LocalTime.of(9, 0)), now))
        assertEquals(TodoStatus.OVERDUE, TodoMath.status(todo(due = LocalDate.of(2026, 11, 20)), now))
        assertEquals(TodoStatus.DONE, TodoMath.status(todo(due = LocalDate.of(2026, 11, 20), done = true), now))
    }

    @Test
    fun `progress counts checklist items and subtasks`() {
        val t = todo(checklist = listOf(ChecklistItem("a", "Form", true), ChecklistItem("b", "Photo", false)))
        assertEquals(2f / 3, TodoMath.progress(t, listOf(todo(id = "s", parent = "t1", done = true)))!!, 0.001f)
        assertNull(TodoMath.progress(todo(), emptyList()))
    }

    @Test
    fun `monthly repeat on the 31st does not drift`() {
        val r = Recurrence(Frequency.MONTHLY)
        val jan = todo(due = LocalDate.of(2026, 1, 31), recurrence = r, anchor = LocalDate.of(2026, 1, 31))
        val feb = TodoMath.nextDue(jan)!!
        assertEquals(LocalDate.of(2026, 2, 28), feb)
        assertEquals(LocalDate.of(2026, 3, 31), TodoMath.nextDue(jan.copy(dueDate = feb)))
        assertNull(TodoMath.nextDue(todo(due = LocalDate.of(2026, 1, 31))))
    }

    private class FakeRepo(initial: List<Todo>) : TodoRepository {
        val todos = initial.associateBy { it.id }.toMutableMap()
        val activity = mutableListOf<TodoActivity>()
        override fun observe(): Flow<List<Todo>> = MutableStateFlow(todos.values.toList())
        override fun observeActivity(todoId: String): Flow<List<TodoActivity>> = MutableStateFlow(activity.filter { it.todoId == todoId })
        override suspend fun all() = todos.values.toList()
        override suspend fun get(id: String) = todos[id]
        override suspend fun save(todo: Todo) {
            todos[todo.id] = todo
        }
        override suspend fun delete(id: String) {
            todos.remove(id)
            todos.values.filter { it.parentId == id }.forEach { todos.remove(it.id) }
        }
        override suspend fun addActivity(activity: TodoActivity) {
            this.activity += activity
        }
    }

    private val scheduler = object : ReminderScheduler {
        var calls = 0
        override suspend fun reschedule() {
            calls++
        }
    }

    @Test
    fun `completing a repeating task moves it forward and resets the checklist`() = runTest {
        val repo = FakeRepo(
            listOf(
                todo(
                    due = LocalDate.of(2026, 11, 23), recurrence = Recurrence(Frequency.WEEKLY), anchor = LocalDate.of(2026, 11, 2),
                    checklist = listOf(ChecklistItem("a", "Water plants", true)),
                ).copy(startDate = LocalDate.of(2026, 11, 21)),
            ),
        )
        CompleteTodoUseCase(repo, scheduler, clock)("t1")
        val t = repo.todos.getValue("t1")
        assertEquals(LocalDate.of(2026, 11, 30), t.dueDate)
        assertEquals(LocalDate.of(2026, 11, 28), t.startDate)
        assertTrue(t.checklist.none { it.done })
        assertNull(t.completedAt)
        assertEquals(TodoActivityType.REPEATED, repo.activity.single().type)
        assertEquals("2026-11-23", repo.activity.single().detail)
    }

    @Test
    fun `completing and reopening a one-off task`() = runTest {
        val repo = FakeRepo(listOf(todo(due = LocalDate.of(2026, 11, 23))))
        CompleteTodoUseCase(repo, scheduler, clock)("t1")
        assertTrue(repo.todos.getValue("t1").isDone)
        ReopenTodoUseCase(repo, scheduler, clock)("t1")
        assertTrue(!repo.todos.getValue("t1").isDone)
        assertEquals(listOf(TodoActivityType.COMPLETED, TodoActivityType.REOPENED), repo.activity.map { it.type })
    }

    @Test
    fun `save validates and drops repeat and alerts without a due date`() = runTest {
        val repo = FakeRepo(emptyList())
        val save = SaveTodoUseCase(repo, scheduler, clock)
        assertEquals(setOf(TodoError.TITLE_REQUIRED), save(todo().copy(title = " "), isNew = true))
        assertEquals(
            setOf(TodoError.START_AFTER_DUE),
            save(todo(due = LocalDate.of(2026, 11, 20)).copy(startDate = LocalDate.of(2026, 11, 21)), isNew = true),
        )
        assertTrue(save(todo(recurrence = Recurrence(Frequency.DAILY)).copy(title = "  Read  "), isNew = true).isEmpty())
        val saved = repo.todos.getValue("t1")
        assertEquals("Read", saved.title)
        assertNull(saved.recurrence)
        assertTrue(saved.alertOffsets.isEmpty())
        assertEquals(1, scheduler.calls)
    }

    @Test
    fun `overview sections and sort order`() {
        val o = ObserveTodoOverviewUseCase.build(
            listOf(
                todo(id = "late", due = LocalDate.of(2026, 11, 20)),
                todo(id = "today-low", due = LocalDate.of(2026, 11, 23), priority = Priority.LOW),
                todo(id = "today-high", due = LocalDate.of(2026, 11, 23), priority = Priority.HIGH),
                todo(id = "later", due = LocalDate.of(2026, 12, 1)),
                todo(id = "someday"),
                todo(id = "child", parent = "someday"),
                todo(id = "done", done = true),
            ),
            now, zone,
        )
        assertEquals(listOf("late"), o.overdue.map { it.todo.id })
        assertEquals(listOf("today-high", "today-low"), o.today.map { it.todo.id })
        assertEquals(listOf("later"), o.upcoming.map { it.todo.id })
        assertEquals(listOf("someday"), o.someday.map { it.todo.id })
        assertEquals(1, o.someday.single().subtaskCount)
        assertEquals(listOf("done"), o.completed.map { it.todo.id })
        assertEquals(5, o.openCount)
    }

    @Test
    fun `task alarms are planned and done tasks are skipped`() {
        val open = todo(due = LocalDate.of(2026, 11, 24), time = LocalTime.of(18, 0))
        val done = todo(id = "t2", due = LocalDate.of(2026, 11, 24), done = true)
        val alarms = ReminderMath.plan(emptyList(), emptyList(), emptyList(), todos = listOf(open, done), now = clock.instant(), zone = zone)
        assertEquals(listOf(AlarmType.TASK), alarms.map { it.type })
        assertEquals(LocalDate.of(2026, 11, 24).atTime(18, 0).atZone(zone).toInstant(), alarms.single().at)
        assertEquals("t1", alarms.single().sourceId)
    }
}
