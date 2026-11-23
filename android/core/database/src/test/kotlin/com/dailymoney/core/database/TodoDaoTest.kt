package com.dailymoney.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dailymoney.core.database.entity.TodoChecklistItemEntity
import com.dailymoney.core.database.entity.TodoEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TodoDaoTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    private fun todo(id: String, parent: String? = null) = TodoEntity(
        id = id, title = id, notes = null, priority = "MEDIUM", category = null, startEpochDay = null, dueEpochDay = null,
        dueTimeMinutes = null, rrule = null, anchorEpochDay = null, alertOffsets = "", parentId = parent, completedAt = null,
        snoozedUntil = null, createdAt = 1, updatedAt = 1,
    )

    @Test
    fun `deleting a task deletes its subtasks only`() = runTest {
        val dao = db.todoDao()
        dao.upsert(todo("parent"))
        dao.upsert(todo("child", parent = "parent"))
        dao.upsert(todo("other"))
        dao.softDeleteWithChildren("parent", now = 5)
        assertEquals(listOf("other"), dao.observeTodos().first().map { it.id })
    }

    @Test
    fun `saving replaces the checklist in order`() = runTest {
        val dao = db.todoDao()
        dao.saveWithChecklist(todo("t"), listOf(TodoChecklistItemEntity("a", "t", "A", false, 0), TodoChecklistItemEntity("b", "t", "B", true, 1)))
        dao.saveWithChecklist(todo("t"), listOf(TodoChecklistItemEntity("c", "t", "C", false, 0)))
        assertEquals(listOf("c"), dao.checklistFor("t").map { it.id })
    }
}
