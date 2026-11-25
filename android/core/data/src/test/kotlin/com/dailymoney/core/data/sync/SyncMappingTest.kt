package com.dailymoney.core.data.sync

import com.dailymoney.core.database.dao.SharedChange
import com.dailymoney.core.database.entity.SharedSplitEntity
import com.dailymoney.core.database.entity.SyncMemberEntity
import com.dailymoney.core.domain.sharing.SplitEntry
import com.dailymoney.core.domain.sharing.SplitMethod
import com.dailymoney.core.domain.sharing.SplitSpec
import com.dailymoney.core.network.model.DecisionDto
import com.dailymoney.core.data.notify.toDomain
import com.dailymoney.core.domain.sharing.NotificationType
import com.dailymoney.core.network.model.ExpenseViewDto
import com.dailymoney.core.network.model.NotificationDto
import com.dailymoney.core.network.model.SettlementDto
import com.dailymoney.core.network.model.ShareDto
import com.dailymoney.core.network.model.SplitViewDto
import com.dailymoney.core.network.model.SyncMemberDto
import com.dailymoney.core.network.model.SyncChangeDto
import com.dailymoney.core.network.model.SyncOrgDto
import com.dailymoney.core.network.model.SyncPullDto
import java.math.BigDecimal
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncMappingTest {

    private val view = ExpenseViewDto(
        id = "e1", orgId = "g1", creatorName = "Asha", mine = false, type = "DEBIT", amountMinor = 4500, currency = "INR",
        occurredAt = "2026-11-20T10:15:30Z", visibility = "ORGANIZATION", version = 2, creatorId = "asha",
        split = SplitViewDto("PERCENT", listOf(ShareDto("asha", JsonPrimitive(60), 2700), ShareDto("me", JsonPrimitive(40), 1800))),
    )

    @Test
    fun `splits, members and settlements come through`() {
        val page = SyncPullDto(
            me = "me",
            myUpiId = "me@ok",
            orgs = listOf(SyncOrgDto("g1", "Home", "INR", canView = true, canAdd = true, canReport = true, canSplit = true, members = listOf(SyncMemberDto("asha", "Asha", "asha@ok")))),
            changes = listOf(
                SyncChangeDto("TRANSACTION", "UPSERT", "e1", view),
                SyncChangeDto("SETTLEMENT", "UPSERT", "s1", settlement = SettlementDto("s1", "g1", "me", "asha", 1800, "INR", "UPI", "REQUESTED", null, "me", "2026-11-21T08:00:00Z")),
            ),
            nextCursor = 9,
            hasMore = false,
        ).toPullPage()
        assertEquals("me", page.me)
        assertEquals(listOf(SyncMemberEntity("g1", "asha", "Asha", "asha@ok")), page.members)
        assertEquals(true, page.groups.single().canSplit)
        val upsert = page.changes[0] as SharedChange.Upsert
        assertEquals("asha", upsert.expense.creatorId)
        assertEquals("PERCENT", upsert.expense.splitMethod)
        assertEquals(listOf(SharedSplitEntity("e1", "asha", 2700, "60"), SharedSplitEntity("e1", "me", 1800, "40")), upsert.splits)
        val settlement = (page.changes[1] as SharedChange.SettlementChange).settlement
        assertEquals("REQUESTED", settlement.status)
        assertEquals(1800L, settlement.amountMinor)
    }

    @Test
    fun `approval status, history and group approval settings come through`() {
        val pending = view.copy(
            approvalStatus = "PENDING_APPROVAL",
            approvals = listOf(DecisionDto("boss", "Boss", "APPROVED", null, "2026-11-21T08:00:00Z", 2)),
        )
        val page = SyncPullDto(
            orgs = listOf(SyncOrgDto("g1", "Office", "INR", true, true, true, approvalMode = "CONDITIONAL", requiredApprovals = 2, isApprover = true)),
            changes = listOf(SyncChangeDto("TRANSACTION", "UPSERT", "e1", pending)),
            nextCursor = 3,
            hasMore = false,
        ).toPullPage()
        val g = page.groups.single()
        assertEquals("CONDITIONAL", g.approvalMode)
        assertEquals(2, g.requiredApprovals)
        assertEquals(true, g.isApprover)
        val upsert = page.changes.single() as SharedChange.Upsert
        assertEquals("PENDING_APPROVAL", upsert.expense.approvalStatus)
        assertEquals("Boss", upsert.decisions.single().approverName)
        assertEquals(2, upsert.decisions.single().version)
    }

    @Test
    fun `notifications come through with their placeholders`() {
        val dto = NotificationDto(
            id = "n1", orgId = "g1", type = "SPLIT_SHARE", entityType = "TRANSACTION", entityId = "e1",
            params = buildJsonObject { put("actor", "Asha"); put("shareMinor", 75000); put("currency", "INR") },
            createdAt = "2026-11-21T08:00:00Z",
        )
        val page = SyncPullDto(orgs = emptyList(), changes = listOf(SyncChangeDto("NOTIFICATION", "UPSERT", "n1", notification = dto)), nextCursor = 1, hasMore = false)
            .toPullPage()
        val entity = (page.changes.single() as SharedChange.NotificationChange).notification
        val domain = entity.toDomain(Json)!!
        assertEquals(NotificationType.SPLIT_SHARE, domain.type)
        assertEquals("Asha", domain.params["actor"])
        assertEquals("75000", domain.params["shareMinor"])
        assertEquals(null, entity.copy(type = "FROM_A_NEWER_SERVER").toDomain(Json))
    }

    @Test
    fun `split spec survives the round trip through the local column`() {
        val spec = SplitSpec(SplitMethod.PERCENT, listOf(SplitEntry("a", BigDecimal("33.3333")), SplitEntry("b", BigDecimal("66.6667"))))
        assertEquals(spec, SplitSpecCodec.decode(SplitSpecCodec.encode(spec)))
        assertEquals(null, SplitSpecCodec.decode(null))
        assertEquals(null, SplitSpecCodec.decode("NOPE;a=1"))
    }

    @Test
    fun `pull response keeps server order and turns deletes and revokes into removals`() {
        val page = SyncPullDto(
            orgs = listOf(SyncOrgDto("g1", "Home", "INR", canView = true, canAdd = false, canReport = true)),
            changes = listOf(
                SyncChangeDto("TRANSACTION", "REVOKE", "e0"),
                SyncChangeDto("TRANSACTION", "UPSERT", "e1", view),
                SyncChangeDto("TRANSACTION", "DELETE", "e2"),
                SyncChangeDto("REMINDER", "UPSERT", "r1"),
            ),
            nextCursor = 42,
            hasMore = false,
        ).toPullPage()

        assertEquals(42L, page.nextCursor)
        assertEquals(listOf("g1"), page.groups.map { it.id })
        assertEquals(3, page.changes.size)
        assertEquals(SharedChange.Remove("e0"), page.changes[0])
        val upsert = (page.changes[1] as SharedChange.Upsert).expense
        assertEquals("g1", upsert.groupId)
        assertEquals(Instant.parse("2026-11-20T10:15:30Z").toEpochMilli(), upsert.occurredAt)
        assertEquals(2, upsert.version)
        assertEquals(SharedChange.Remove("e2"), page.changes[2])
    }
}
