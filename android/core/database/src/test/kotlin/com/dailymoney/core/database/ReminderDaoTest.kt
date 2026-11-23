package com.dailymoney.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dailymoney.core.database.entity.OccurrenceRecordEntity
import com.dailymoney.core.database.entity.ReminderEntity
import com.dailymoney.core.database.entity.ReminderStepEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReminderDaoTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    private fun reminder(id: String) = ReminderEntity(
        id = id, kind = "BIRTHDAY", title = "Ana", notes = null, amountMinor = null, currency = "INR", categoryId = null,
        accountId = null, startEpochDay = 20_000, timeMinutes = null, endTimeMinutes = null, location = null,
        rrule = "FREQ=YEARLY;INTERVAL=1", alertOffsets = "0", active = true, createdAt = 1, updatedAt = 1,
    )

    @Test
    fun `saving replaces steps and soft delete hides the reminder`() = runTest {
        val dao = db.reminderDao()
        dao.saveWithSteps(reminder("r1"), listOf(ReminderStepEntity("s1", "r1", "Gift", 5, 0), ReminderStepEntity("s2", "r1", "Cake", 1, 1)))
        assertEquals(listOf("s1", "s2"), dao.stepsFor("r1").map { it.id })

        dao.saveWithSteps(reminder("r1").copy(title = "Ana B"), listOf(ReminderStepEntity("s3", "r1", "Wish", 0, 0)))
        assertEquals(listOf("s3"), dao.stepsFor("r1").map { it.id })
        assertEquals("Ana B", dao.get("r1")?.title)

        dao.softDelete("r1", now = 9)
        assertNull(dao.get("r1"))
        assertEquals(emptyList<ReminderEntity>(), dao.observeReminders().first())
    }

    @Test
    fun `one record per occurrence`() = runTest {
        val dao = db.reminderDao()
        val record = OccurrenceRecordEntity("r1", 20_000, "DONE", 5, null, null, 100, "t1", "", 5)
        dao.upsertRecord(record)
        dao.upsertRecord(record.copy(action = null, transactionId = null))
        assertEquals(1, dao.records().size)
        assertNull(dao.record("r1", 20_000)?.action)
        dao.deleteRecord("r1", 20_000)
        assertNull(dao.record("r1", 20_000))
    }
}
