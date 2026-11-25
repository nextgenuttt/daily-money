package com.dailymoney.core.domain.sharing

import com.dailymoney.core.domain.money.DateRange
import com.dailymoney.core.domain.money.PaymentMethod
import com.dailymoney.core.domain.money.SaveResult
import com.dailymoney.core.domain.money.SaveTransactionUseCase
import com.dailymoney.core.domain.money.Transaction
import com.dailymoney.core.domain.money.TransactionDraft
import com.dailymoney.core.domain.money.TransactionError
import com.dailymoney.core.domain.money.TransactionRepository
import com.dailymoney.core.domain.money.TransactionType
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharingTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val november = DateRange.month(YearMonth.of(2026, 11), zone)

    private fun expense(
        amount: Long,
        by: String,
        category: String? = "Food",
        type: TransactionType = TransactionType.EXPENSE,
        currency: String = "INR",
        at: String = "2026-11-10T10:00:00Z",
    ) = SharedExpense(
        id = "$by-$amount-$at", groupId = "g", creatorId = by, creatorName = by, mine = by == "Me", type = type, amountMinor = amount,
        currency = currency, categoryName = category, categoryIcon = null, occurredAt = Instant.parse(at), note = null,
        paymentMethod = null, visibility = Visibility.ORGANIZATION,
    )

    @Test
    fun `dashboard adds up the month by category and by person`() {
        val d = GroupDashboard.build(
            listOf(
                expense(30_000, "Asha", "Groceries"),
                expense(10_000, "Me", "Groceries"),
                expense(5_000, "Me", "Fuel"),
                expense(2_000, "Asha", null),
                expense(7_000, "Asha", "Refund", type = TransactionType.INCOME),
                expense(99_000, "Asha", at = "2026-10-31T12:00:00Z"),
            ),
            november,
            "INR",
        )
        assertEquals(47_000, d.spentMinor)
        assertEquals(7_000, d.receivedMinor)
        assertEquals(5, d.count)
        assertEquals(listOf(Breakdown("Groceries", 40_000), Breakdown("Fuel", 5_000), Breakdown(GroupDashboard.UNCATEGORIZED, 2_000)), d.byCategory)
        assertEquals(listOf(Breakdown("Asha", 32_000), Breakdown("Me", 15_000)), d.byMember)
    }

    @Test
    fun `other currencies are listed but not added to the group total`() {
        val d = GroupDashboard.build(listOf(expense(1_000, "Me"), expense(50, "Asha", currency = "USD")), november, "INR")
        assertEquals(1_000, d.spentMinor)
        assertEquals(2, d.count)
        assertEquals(1, d.otherCurrencyCount)
    }

    private class Saved : TransactionRepository {
        var last: Transaction? = null
        override fun observeInRange(range: DateRange): Flow<List<Transaction>> = MutableStateFlow(emptyList())
        override fun observeRecent(limit: Int): Flow<List<Transaction>> = MutableStateFlow(emptyList())
        override suspend fun get(id: String) = last
        override suspend fun save(transaction: Transaction) {
            last = transaction
        }
        override suspend fun importAll(transactions: List<Transaction>) = 0
        override suspend fun importedHashes(hashes: Collection<String>) = emptySet<String>()
        override suspend fun labelledNotes(since: java.time.Instant) = emptyList<com.dailymoney.core.domain.smart.LabelledNote>()
        override suspend fun delete(id: String) = Unit
    }

    private val clock = Clock.fixed(Instant.parse("2026-11-22T06:30:00Z"), zone)

    private fun draft(type: TransactionType, sharing: Sharing?) = TransactionDraft(
        null, type, "100", "INR", "food".takeIf { type != TransactionType.TRANSFER }, "cash",
        "bank".takeIf { type == TransactionType.TRANSFER }, LocalDate.of(2026, 11, 20), "", PaymentMethod.CASH, sharing,
    )

    @Test
    fun `sharing with selected people needs at least one person`() {
        val use = SaveTransactionUseCase(Saved(), clock)
        assertEquals(
            setOf(TransactionError.MEMBERS_REQUIRED),
            use.validate(draft(TransactionType.EXPENSE, Sharing("g", Visibility.SELECTED_MEMBERS))),
        )
        assertEquals(emptySet<TransactionError>(), use.validate(draft(TransactionType.EXPENSE, Sharing("g", Visibility.SELECTED_MEMBERS, setOf("u2")))))
        assertEquals(emptySet<TransactionError>(), use.validate(draft(TransactionType.EXPENSE, Sharing("g", Visibility.ORGANIZATION))))
    }

    @Test
    fun `a split that doesn't add up can't be saved, and income is never split`() = runTest {
        val use = SaveTransactionUseCase(Saved(), clock)
        val badSplit = SplitSpec(SplitMethod.PERCENT, listOf(SplitEntry("me", java.math.BigDecimal(60)), SplitEntry("u2", java.math.BigDecimal(30))))
        assertEquals(setOf(TransactionError.SPLIT_INVALID), use.validate(draft(TransactionType.EXPENSE, Sharing("g", Visibility.ORGANIZATION, split = badSplit))))

        val repo = Saved()
        SaveTransactionUseCase(repo, clock)(draft(TransactionType.INCOME, Sharing("g", Visibility.ORGANIZATION, split = badSplit)))
        assertNull(repo.last!!.sharing!!.split)
    }

    @Test
    fun `transfers are never shared`() = runTest {
        val repo = Saved()
        val result = SaveTransactionUseCase(repo, clock)(draft(TransactionType.TRANSFER, Sharing("g", Visibility.ORGANIZATION)))
        assertEquals(SaveResult.Saved(repo.last!!), result)
        assertNull(repo.last!!.sharing)
    }
}
