package com.dailymoney.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dailymoney.core.database.dao.PullPage
import com.dailymoney.core.database.dao.SharedChange
import com.dailymoney.core.database.dao.SyncDao
import com.dailymoney.core.database.dao.SyncStatus
import com.dailymoney.core.database.entity.NotificationEntity
import com.dailymoney.core.database.entity.PendingOpEntity
import com.dailymoney.core.database.entity.SettlementEntity
import com.dailymoney.core.database.entity.SharedDecisionEntity
import com.dailymoney.core.database.entity.SharedExpenseEntity
import com.dailymoney.core.database.entity.SharedSplitEntity
import com.dailymoney.core.database.entity.SyncMemberEntity
import com.dailymoney.core.database.entity.SyncGroupEntity
import com.dailymoney.core.database.entity.TransactionEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: SyncDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.syncDao()
    }

    @After
    fun tearDown() = db.close()

    private fun group(id: String, canView: Boolean = true) = SyncGroupEntity(id, id, "INR", canView, canAdd = true, canReport = true)

    private fun shared(id: String, group: String = "g1", mine: Boolean = false, version: Int = 1, amount: Long = 100) =
        SharedExpenseEntity(id, group, "Asha", mine, "DEBIT", amount, "INR", "Food", "food", 1_000, null, null, "ORGANIZATION", version)

    private fun mine(id: String, org: String? = "g1", serverVersion: Int = 0, status: String = SyncStatus.PENDING) = TransactionEntity(
        id = id, creatorId = "me", orgId = org, type = "DEBIT", amountMinor = 100, currency = "INR", categoryId = null,
        accountId = "cash", toAccountId = null, occurredAt = 1_000, note = null, paymentMethod = null,
        visibility = if (org == null) "PERSONAL" else "ORGANIZATION", localSyncStatus = status, createdAt = 1, updatedAt = 1,
        serverVersion = serverVersion,
    )

    private fun op(id: String, entity: String, at: Long = 1) = PendingOpEntity(id, "TRANSACTION", entity, "CHANGE", 0, "", at)

    @Test
    fun `changes apply in server order so a revoke followed by a re-share keeps the row`() = runTest {
        dao.applyPull(PullPage(listOf(group("g1")), listOf(SharedChange.Upsert(shared("a"))), nextCursor = 5))
        dao.applyPull(
            PullPage(
                listOf(group("g1")),
                listOf(SharedChange.Remove("a"), SharedChange.Upsert(shared("a", version = 3, amount = 250)), SharedChange.Upsert(shared("b")), SharedChange.Remove("b")),
                nextCursor = 9,
            ),
        )
        val rows = dao.observeShared("g1").first()
        assertEquals(listOf("a"), rows.map { it.id })
        assertEquals(250L, rows.single().amountMinor)
        assertEquals("9", dao.state(SyncDao.CURSOR))
    }

    @Test
    fun `leaving a group or losing view permission removes other people's rows`() = runTest {
        dao.applyPull(
            PullPage(
                listOf(group("g1"), group("g2")),
                listOf(SharedChange.Upsert(shared("a", "g1")), SharedChange.Upsert(shared("b", "g2")), SharedChange.Upsert(shared("c", "g2", mine = true))),
                nextCursor = 3,
            ),
        )
        dao.applyPull(PullPage(listOf(group("g2", canView = false)), emptyList(), nextCursor = 3))
        assertTrue(dao.observeShared("g1").first().isEmpty())
        assertEquals(listOf("c"), dao.observeShared("g2").first().map { it.id })
        assertEquals(listOf("g2"), dao.groups().map { it.id })
    }

    @Test
    fun `a newly visible group restarts the pull from the beginning once`() = runTest {
        assertFalse(dao.applyPull(PullPage(listOf(group("g1")), emptyList(), nextCursor = 10)))
        assertTrue(dao.applyPull(PullPage(listOf(group("g1"), group("g2")), emptyList(), nextCursor = 20)))
        assertEquals("0", dao.state(SyncDao.CURSOR))
        assertFalse(dao.applyPull(PullPage(listOf(group("g1"), group("g2")), emptyList(), nextCursor = 20)))
        assertEquals("20", dao.state(SyncDao.CURSOR))
    }

    @Test
    fun `my row coming back from the server is confirmed unless I edited it again`() = runTest {
        dao.saveTransaction(mine("m1"), null)
        dao.saveTransaction(mine("m2"), op("o2", "m2"))
        dao.applyPull(PullPage(listOf(group("g1")), listOf(SharedChange.Upsert(shared("m1", mine = true, version = 4)), SharedChange.Upsert(shared("m2", mine = true, version = 2))), 2))
        assertEquals(SyncStatus.SYNCED, dao.transaction("m1")!!.localSyncStatus)
        assertEquals(4, dao.transaction("m1")!!.serverVersion)
        assertEquals(SyncStatus.PENDING, dao.transaction("m2")!!.localSyncStatus)
        assertEquals(0, dao.transaction("m2")!!.serverVersion)
    }

    @Test
    fun `push results update the row and clear only the ops that were sent`() = runTest {
        dao.saveTransaction(mine("m1"), op("o1", "m1", at = 1))
        dao.enqueue(op("o2", "m1", at = 2))
        dao.pushApplied("m1", listOf("o1"), version = 1, approval = "PENDING_APPROVAL")
        assertEquals(SyncStatus.PENDING, dao.transaction("m1")!!.localSyncStatus)
        assertEquals(1, dao.transaction("m1")!!.serverVersion)
        dao.pushApplied("m1", listOf("o2"), version = 2, approval = null)
        assertEquals("PENDING_APPROVAL", dao.transaction("m1")!!.approvalStatus)
        assertEquals(SyncStatus.SYNCED, dao.transaction("m1")!!.localSyncStatus)
        assertTrue(dao.pending("TRANSACTION").isEmpty())

        dao.saveTransaction(mine("m2"), op("o3", "m2"))
        dao.pushRejected("m2", listOf("o3"))
        assertEquals(SyncStatus.FAILED, dao.transaction("m2")!!.localSyncStatus)

        dao.saveTransaction(mine("m3", serverVersion = 2, status = SyncStatus.SYNCED), op("o4", "m3"))
        dao.pushGone("m3", listOf("o4"))
        val gone = dao.transaction("m3")!!
        assertNull(gone.orgId)
        assertEquals("PERSONAL", gone.visibility)
        assertEquals(0, gone.serverVersion)
    }

    @Test
    fun `splits, members and settlements follow the pull`() = runTest {
        val settlement = SettlementEntity("s1", "g1", "me", "asha", 300, "INR", "UPI", "REQUESTED", null, "me", 5)
        dao.applyPull(
            PullPage(
                listOf(group("g1")),
                listOf(
                    SharedChange.Upsert(shared("a"), listOf(SharedSplitEntity("a", "me", 60, null), SharedSplitEntity("a", "asha", 40, null))),
                    SharedChange.SettlementChange(settlement),
                ),
                nextCursor = 4,
                members = listOf(SyncMemberEntity("g1", "me", "Me", null), SyncMemberEntity("g1", "asha", "Asha", "asha@ok")),
                me = "me",
                myUpiId = "me@ok",
            ),
        )
        assertEquals(listOf(60L, 40L), dao.observeSplits("g1").first().sortedByDescending { it.shareMinor }.map { it.shareMinor })
        assertEquals(2, dao.observeMembers().first().size)
        assertEquals("me", dao.state(SyncDao.ME))
        assertEquals("me@ok", dao.state(SyncDao.MY_UPI))

        // The split is replaced as a whole when the expense changes, and removed with it.
        dao.applyPull(
            PullPage(
                listOf(group("g1")),
                listOf(
                    SharedChange.Upsert(shared("a", version = 2), listOf(SharedSplitEntity("a", "asha", 100, null))),
                    SharedChange.SettlementChange(settlement.copy(status = "CONFIRMED")),
                ),
                nextCursor = 6,
            ),
        )
        assertEquals(listOf("asha"), dao.observeSplits("g1").first().map { it.userId })
        assertEquals("CONFIRMED", dao.observeSettlements("g1").first().single().status)
        dao.applyPull(PullPage(listOf(group("g1")), listOf(SharedChange.Remove("a")), nextCursor = 7))
        assertTrue(dao.observeSplits("g1").first().isEmpty())

        // Leaving the group drops its payments too.
        dao.applyPull(PullPage(emptyList(), emptyList(), nextCursor = 7))
        assertTrue(dao.observeSettlements("g1").first().isEmpty())
    }

    @Test
    fun `approval history is replaced with the row and my copy follows the decision`() = runTest {
        dao.saveTransaction(mine("m1", serverVersion = 1, status = SyncStatus.SYNCED), op("o1", "m1"))
        val decided = SharedDecisionEntity("m1", 1, "boss", "Boss", "REJECTED", "No receipt", 10)
        dao.applyPull(
            PullPage(listOf(group("g1")), listOf(SharedChange.Upsert(shared("m1", mine = true).copy(approvalStatus = "REJECTED"), decisions = listOf(decided))), 3),
        )
        assertEquals(listOf(decided), dao.observeDecisions("g1").first())
        // Still queued for push, but the rejection is shown straight away.
        assertEquals("REJECTED", dao.transaction("m1")!!.approvalStatus)
        assertEquals(SyncStatus.SYNCED, dao.transaction("m1")!!.localSyncStatus)

        dao.applyPull(PullPage(listOf(group("g1")), listOf(SharedChange.Remove("m1")), 4))
        assertTrue(dao.observeDecisions("g1").first().isEmpty())
    }

    @Test
    fun `a notification read or shown on this phone stays that way and leaves with its group`() = runTest {
        val note = NotificationEntity("n1", "g1", "SPLIT_SHARE", "TRANSACTION", "a", "{}", 100, read = false)
        dao.applyPull(PullPage(listOf(group("g1")), listOf(SharedChange.NotificationChange(note)), 2))
        assertEquals(1, dao.unshownSince(0).size)
        dao.markShown(listOf("n1"))
        dao.markNotificationsRead(listOf("n1"))
        // The server hasn't heard about the read yet and sends it again as unread.
        dao.applyPull(PullPage(listOf(group("g1")), listOf(SharedChange.NotificationChange(note)), 3))
        val kept = dao.notification("n1")!!
        assertTrue(kept.read)
        assertTrue(kept.shown)
        assertTrue(dao.unshownSince(0).isEmpty())

        dao.applyPull(PullPage(listOf(group("g1")), listOf(SharedChange.NotificationChange(note.copy(id = "n2", groupId = null))), 4))
        dao.applyPull(PullPage(emptyList(), emptyList(), 4))
        assertEquals(listOf("n2"), dao.observeNotifications().first().map { it.id })
    }

    @Test
    fun `deleting a shared row keeps it until the server hears about it`() = runTest {
        dao.saveTransaction(mine("m1", serverVersion = 1, status = SyncStatus.SYNCED), null)
        dao.deleteTransaction("m1", now = 50, op = op("o1", "m1"))
        val row = dao.transaction("m1")!!
        assertEquals(50L, row.deletedAt)
        assertEquals(SyncStatus.PENDING, row.localSyncStatus)
        assertEquals(1, dao.pending("TRANSACTION").size)
    }
}
