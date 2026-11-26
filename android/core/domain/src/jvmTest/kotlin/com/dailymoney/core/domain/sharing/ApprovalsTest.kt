package com.dailymoney.core.domain.sharing

import com.dailymoney.core.domain.money.DateRange
import com.dailymoney.core.domain.money.TransactionType
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalsTest {

    private fun expense(
        id: String,
        creator: String,
        approval: ApprovalStatus,
        amount: Long = 1000,
        version: Int = 1,
        decisions: List<ApprovalDecision> = emptyList(),
        shares: List<Share> = emptyList(),
    ) = SharedExpense(
        id = id, groupId = "g", creatorId = creator, creatorName = creator, mine = creator == "me", type = TransactionType.EXPENSE,
        amountMinor = amount, currency = "INR", categoryName = "Food", categoryIcon = null, occurredAt = Instant.parse("2026-11-10T10:00:00Z"),
        note = null, paymentMethod = null, visibility = Visibility.ORGANIZATION, shares = shares, approval = approval,
        decisions = decisions, version = version,
    )

    private fun group(isApprover: Boolean = true, self: Boolean = false) =
        SharedGroup("g", "Office", "INR", canView = true, canAdd = true, canReport = true, isApprover = isApprover, allowSelfApproval = self)

    private fun approvedBy(who: String, version: Int) = ApprovalDecision(who, who, approved = true, reason = null, decidedAt = Instant.EPOCH, version = version)

    @Test
    fun `my queue skips decided, my own unless allowed, and anything not pending`() {
        val all = listOf(
            expense("a", "asha", ApprovalStatus.PENDING_APPROVAL),
            expense("b", "asha", ApprovalStatus.PENDING_APPROVAL, decisions = listOf(approvedBy("me", 1))),
            // My approval of version 1 doesn't count after an edit to version 2.
            expense("c", "asha", ApprovalStatus.PENDING_APPROVAL, version = 2, decisions = listOf(approvedBy("me", 1))),
            expense("d", "me", ApprovalStatus.PENDING_APPROVAL),
            expense("e", "asha", ApprovalStatus.APPROVED),
        )
        assertEquals(listOf("a", "c"), Approvals.waitingFor("me", group(), all).map { it.id })
        assertEquals(listOf("a", "c", "d"), Approvals.waitingFor("me", group(self = true), all).map { it.id })
        assertTrue(Approvals.waitingFor("me", group(isApprover = false), all).isEmpty())
    }

    @Test
    fun `only approved expenses count in totals and balances`() {
        val all = listOf(
            expense("a", "asha", ApprovalStatus.APPROVED, 1000, shares = listOf(Share("me", 500), Share("asha", 500))),
            expense("b", "asha", ApprovalStatus.PENDING_APPROVAL, 3000, shares = listOf(Share("me", 1500), Share("asha", 1500))),
            expense("c", "asha", ApprovalStatus.REJECTED, 7000, shares = listOf(Share("me", 7000))),
        )
        val d = GroupDashboard.build(all, DateRange.month(YearMonth.of(2026, 11), ZoneId.of("Asia/Kolkata")), "INR")
        assertEquals(1000, d.spentMinor)
        assertEquals(3000, d.pendingMinor)
        assertEquals(2, d.count)
        assertEquals(listOf(PersonBalance("asha", -500)), Balances.compute("me", all, emptyList(), "INR"))
    }
}
