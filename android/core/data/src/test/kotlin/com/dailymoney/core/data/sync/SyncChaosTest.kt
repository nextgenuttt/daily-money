package com.dailymoney.core.data.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dailymoney.core.database.AppDatabase
import com.dailymoney.core.database.dao.SyncStatus
import com.dailymoney.core.database.entity.PendingOpEntity
import com.dailymoney.core.database.entity.TransactionEntity
import com.dailymoney.core.network.SyncApi
import com.dailymoney.core.network.model.ExpenseViewDto
import com.dailymoney.core.network.model.MessageDto
import com.dailymoney.core.network.model.NewMessageDto
import com.dailymoney.core.network.model.NewSettlementDto
import com.dailymoney.core.network.model.OpResultDto
import com.dailymoney.core.network.model.ReadNotificationsDto
import com.dailymoney.core.network.model.SettlementDto
import com.dailymoney.core.network.model.SyncChangeDto
import com.dailymoney.core.network.model.SyncOrgDto
import com.dailymoney.core.network.model.SyncPullDto
import com.dailymoney.core.network.model.SyncPushRequestDto
import com.dailymoney.core.network.model.SyncPushResponseDto
import com.dailymoney.core.network.model.ThreadDto
import com.dailymoney.core.network.model.UpiRequestDto
import java.io.IOException
import java.time.Instant
import kotlin.random.Random
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response

/**
 * Offline chaos test (LLD §15 phase 12): random edits, deletes and un-shares while requests fail
 * before reaching the server or their replies are lost. Once the network is back, the phone and the
 * server must agree, the outbox must be empty, and no op may have been applied twice.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncChaosTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `phone and server converge after a flaky network`() = runTest {
        repeat(25) { seed -> runScenario(seed) }
    }

    @Test
    fun `a delete after a lost create reply still removes the group copy`() = runTest {
        val server = FakeServer()
        val phone = Phone(db, Random(1))
        phone.create("t1", 500)
        assertFalse(SyncRunner(db, FlakyApi(server, loseReplyOnce = true), "d1").tryPush())
        assertEquals(1, server.live().size)

        phone.delete("t1")
        SyncRunner(db, server, "d1").push()

        assertEquals(0, server.live().size)
        assertEquals(0, db.syncDao().pending("TRANSACTION").size)
    }

    @Test
    fun `making a row personal after a lost create reply removes the group copy`() = runTest {
        val server = FakeServer()
        val phone = Phone(db, Random(2))
        phone.create("t1", 500)
        assertFalse(SyncRunner(db, FlakyApi(server, loseReplyOnce = true), "d1").tryPush())

        phone.makePersonal("t1")
        SyncRunner(db, server, "d1").push()

        assertEquals(0, server.live().size)
        val row = db.syncDao().transaction("t1")!!
        assertEquals(null, row.orgId)
        assertEquals(SyncStatus.LOCAL, row.localSyncStatus)
    }

    private suspend fun runScenario(seed: Int) {
        db.clearAllTables()
        val rnd = Random(seed)
        val server = FakeServer()
        val flaky = FlakyApi(server, rnd = rnd)
        val phone = Phone(db, rnd)
        repeat(40) { step ->
            when (rnd.nextInt(10)) {
                in 0..2 -> phone.create("s$seed-$step", rnd.nextLong(1, 10_000))
                in 3..5 -> phone.edit(rnd.nextLong(1, 10_000))
                6 -> phone.deleteAny()
                7 -> phone.makePersonalAny()
                else -> SyncRunner(db, flaky, "d1").let { it.tryPush(); it.tryPull() }
            }
        }
        // Network back: a couple of clean syncs settle everything.
        repeat(2) { SyncRunner(db, server, "d1").let { it.push(); it.pull() } }

        val dao = db.syncDao()
        assertEquals("seed $seed: outbox", 0, dao.pending("TRANSACTION").size)
        val live = server.live()
        val local = phone.ids.mapNotNull { dao.transaction(it) }
        val shouldBeShared = local.filter { it.deletedAt == null && it.orgId != null }
        assertEquals("seed $seed: shared rows", shouldBeShared.map { it.id }.toSet(), live.keys)
        shouldBeShared.forEach { row ->
            val remote = live.getValue(row.id)
            assertEquals("seed $seed: amount of ${row.id}", row.amountMinor, remote.amountMinor)
            assertEquals("seed $seed: version of ${row.id}", remote.version, row.serverVersion)
            assertEquals("seed $seed: status of ${row.id}", SyncStatus.SYNCED, row.localSyncStatus)
        }
        assertTrue("seed $seed: an op was applied twice", server.appliedTwice.isEmpty())
    }

    private suspend fun SyncRunner.tryPush() = runCatching { push() }.isSuccess
    private suspend fun SyncRunner.tryPull() = runCatching { pull() }.isSuccess

    private fun assertFalse(value: Boolean) = assertTrue(!value)

    /** Mimics RoomTransactionRepository: the row and its outbox op are written together. */
    private class Phone(private val db: AppDatabase, private val rnd: Random) {
        val ids = mutableListOf<String>()
        private var clock = 1_000L

        suspend fun create(id: String, amount: Long) {
            ids += id
            save(TransactionEntity(
                id = id, creatorId = "me", orgId = GROUP, type = "DEBIT", amountMinor = amount, currency = "INR", categoryId = null,
                accountId = "cash", toAccountId = null, occurredAt = 1_000, note = null, paymentMethod = null, visibility = "ORGANIZATION",
                localSyncStatus = SyncStatus.PENDING, createdAt = 1, updatedAt = 1,
            ))
        }

        suspend fun edit(amount: Long) {
            val row = live().randomOrNull(rnd) ?: return
            if (row.orgId == null) return
            save(row.copy(amountMinor = amount, localSyncStatus = SyncStatus.PENDING, version = row.version + 1))
        }

        suspend fun deleteAny() = live().randomOrNull(rnd)?.let { delete(it.id) }

        suspend fun delete(id: String) {
            val row = db.syncDao().transaction(id) ?: return
            db.syncDao().deleteTransaction(id, ++clock, if (row.orgId != null) op(id) else null)
        }

        /** The app only allows this before the group has confirmed the row. */
        suspend fun makePersonalAny() = live().filter { it.orgId != null && it.serverVersion == 0 }.randomOrNull(rnd)?.let { makePersonal(it.id) }

        suspend fun makePersonal(id: String) {
            val row = db.syncDao().transaction(id) ?: return
            save(row.copy(orgId = null, visibility = "PERSONAL", localSyncStatus = SyncStatus.PENDING))
        }

        private suspend fun live() = ids.mapNotNull { db.syncDao().transaction(it) }.filter { it.deletedAt == null }

        private suspend fun save(row: TransactionEntity) = db.syncDao().saveTransaction(row, op(row.id))

        private fun op(id: String) = PendingOpEntity(
            opId = "op-${++clock}", entityType = "TRANSACTION", entityId = id, opType = "CHANGE", baseVersion = 0, payloadJson = "", createdAt = clock,
        )
    }

    /** Fails before the server sees the request, or after it applied it (the reply is lost). */
    private class FlakyApi(
        private val server: FakeServer,
        private val rnd: Random? = null,
        private var loseReplyOnce: Boolean = false,
    ) : SyncApi by server {
        override suspend fun push(body: SyncPushRequestDto): SyncPushResponseDto {
            if (rnd != null && rnd.nextInt(5) == 0) throw IOException("offline")
            val response = server.push(body)
            if (loseReplyOnce || (rnd != null && rnd.nextInt(4) == 0)) {
                loseReplyOnce = false
                throw IOException("reply lost")
            }
            return response
        }

        override suspend fun pull(cursor: Long, limit: Int): SyncPullDto {
            if (rnd != null && rnd.nextInt(5) == 0) throw IOException("offline")
            return server.pull(cursor, limit)
        }
    }

    /** The server rules from SyncService: idempotent op ids, creator-only edits, version checks. */
    private class FakeServer : SyncApi {
        data class Row(val id: String, val amountMinor: Long, val version: Int, val deleted: Boolean, val seq: Long)

        private val rows = mutableMapOf<String, Row>()
        private val remembered = mutableMapOf<String, OpResultDto>()
        private val appliedOps = mutableSetOf<String>()
        val appliedTwice = mutableListOf<String>()
        private var seq = 0L

        fun live() = rows.values.filter { !it.deleted }.associateBy { it.id }

        override suspend fun push(body: SyncPushRequestDto) = SyncPushResponseDto(body.ops.map { op ->
            remembered[op.opId] ?: apply(op).also { if (it.status == "APPLIED") remember(op.opId, it) }
        })

        private fun remember(opId: String, result: OpResultDto) {
            if (!appliedOps.add(opId)) appliedTwice += opId
            remembered[opId] = result
        }

        private fun apply(op: com.dailymoney.core.network.model.SyncOpDto): OpResultDto {
            val existing = rows[op.entityId]
            return when (op.opType) {
                "CREATE" -> when {
                    existing == null -> write(op.opId, Row(op.entityId, op.payload!!.amountMinor, 1, false, ++seq))
                    existing.deleted -> OpResultDto(op.opId, "GONE")
                    else -> OpResultDto(op.opId, "CONFLICT", current = view(existing))
                }
                "UPDATE" -> when {
                    existing == null || existing.deleted -> OpResultDto(op.opId, "GONE")
                    existing.version != op.baseVersion -> OpResultDto(op.opId, "CONFLICT", current = view(existing))
                    else -> write(op.opId, existing.copy(amountMinor = op.payload!!.amountMinor, version = existing.version + 1, seq = ++seq))
                }
                "DELETE" -> when {
                    existing == null -> OpResultDto(op.opId, "GONE")
                    existing.deleted -> OpResultDto(op.opId, "APPLIED", version = existing.version)
                    else -> write(op.opId, existing.copy(deleted = true, version = existing.version + 1, seq = ++seq))
                }
                else -> OpResultDto(op.opId, "REJECTED", errorCode = "INVALID_OP")
            }
        }

        private fun write(opId: String, row: Row): OpResultDto {
            rows[row.id] = row
            return OpResultDto(opId, "APPLIED", version = row.version, approvalStatus = "APPROVED")
        }

        private fun view(r: Row) = ExpenseViewDto(
            id = r.id, orgId = GROUP, creatorName = "Me", mine = true, type = "DEBIT", amountMinor = r.amountMinor, currency = "INR",
            occurredAt = Instant.ofEpochMilli(1_000).toString(), visibility = "ORGANIZATION", version = r.version, creatorId = "me",
        )

        override suspend fun pull(cursor: Long, limit: Int): SyncPullDto {
            val page = rows.values.filter { it.seq > cursor }.sortedBy { it.seq }.take(limit)
            return SyncPullDto(
                me = "me",
                orgs = listOf(SyncOrgDto(GROUP, "Home", "INR", canView = true, canAdd = true, canReport = true)),
                changes = page.map { r ->
                    if (r.deleted) SyncChangeDto("TRANSACTION", "DELETE", r.id) else SyncChangeDto("TRANSACTION", "UPSERT", r.id, data = view(r))
                },
                nextCursor = page.lastOrNull()?.seq ?: cursor,
                hasMore = rows.values.count { it.seq > cursor } > limit,
            )
        }

        override suspend fun recordSettlement(orgId: String, body: NewSettlementDto): SettlementDto = error("unused")
        override suspend fun confirmSettlement(id: String): SettlementDto = error("unused")
        override suspend fun cancelSettlement(id: String): SettlementDto = error("unused")
        override suspend fun setUpiId(body: UpiRequestDto): Response<Unit> = error("unused")
        override suspend fun readNotifications(body: ReadNotificationsDto): Response<Unit> = error("unused")
        override suspend fun thread(expenseId: String): ThreadDto = error("unused")
        override suspend fun sendMessage(expenseId: String, body: NewMessageDto): MessageDto = error("unused")
        override suspend fun readMessages(expenseId: String): Response<Unit> = error("unused")
        override suspend fun deleteMessage(messageId: String): Response<Unit> = error("unused")
    }

    private companion object {
        const val GROUP = "g1"
    }
}
