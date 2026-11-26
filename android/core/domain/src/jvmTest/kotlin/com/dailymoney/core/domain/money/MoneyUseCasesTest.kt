package com.dailymoney.core.domain.money

import com.dailymoney.core.domain.config.AppConfig
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoneyUseCasesTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val clock = Clock.fixed(Instant.parse("2026-11-22T06:30:00Z"), zone)

    private class FakeTransactions : TransactionRepository {
        val saved = mutableListOf<Transaction>()
        override fun observeInRange(range: DateRange): Flow<List<Transaction>> = MutableStateFlow(saved.toList())
        override fun observeRecent(limit: Int): Flow<List<Transaction>> = MutableStateFlow(saved.take(limit))
        override suspend fun get(id: String) = saved.firstOrNull { it.id == id }
        override suspend fun save(transaction: Transaction) {
            saved.removeAll { it.id == transaction.id }
            saved += transaction
        }
        override suspend fun importAll(transactions: List<Transaction>) = 0
        override suspend fun importedHashes(hashes: Collection<String>) = emptySet<String>()
        override suspend fun labelledNotes(since: java.time.Instant) = emptyList<com.dailymoney.core.domain.smart.LabelledNote>()
        override suspend fun delete(id: String) {
            saved.removeAll { it.id == id }
        }
    }

    private fun draft(
        type: TransactionType = TransactionType.EXPENSE,
        amount: String = "250.50",
        category: String? = "food",
        account: String? = "cash",
        toAccount: String? = null,
    ) = TransactionDraft(null, type, amount, "INR", category, account, toAccount, LocalDate.of(2026, 11, 20), "  Lunch ", PaymentMethod.UPI)

    @Test
    fun `valid expense is saved with minor units, trimmed note and chosen date`() = runTest {
        val repo = FakeTransactions()
        val result = SaveTransactionUseCase(repo, clock)(draft())
        val t = repo.saved.single()
        assertEquals(SaveResult.Saved(t), result)
        assertEquals(25050L, t.amountMinor)
        assertEquals("Lunch", t.note)
        assertEquals(LocalDate.of(2026, 11, 20), t.occurredAt.atZone(zone).toLocalDate())
    }

    @Test
    fun `validation errors`() {
        val use = SaveTransactionUseCase(FakeTransactions(), clock)
        assertEquals(setOf(TransactionError.AMOUNT_REQUIRED), use.validate(draft(amount = "")))
        assertEquals(setOf(TransactionError.AMOUNT_INVALID), use.validate(draft(amount = "0")))
        assertEquals(setOf(TransactionError.CATEGORY_REQUIRED), use.validate(draft(category = null)))
        assertEquals(setOf(TransactionError.ACCOUNT_REQUIRED), use.validate(draft(account = null)))
        assertEquals(
            setOf(TransactionError.SAME_ACCOUNT),
            use.validate(draft(type = TransactionType.TRANSFER, category = null, toAccount = "cash")),
        )
        assertTrue(use.validate(draft(type = TransactionType.TRANSFER, category = null, toAccount = "bank")).isEmpty())
    }

    @Test
    fun `transfer drops category and payment method`() = runTest {
        val repo = FakeTransactions()
        SaveTransactionUseCase(repo, clock)(draft(type = TransactionType.TRANSFER, category = "food", toAccount = "bank"))
        val t = repo.saved.single()
        assertNull(t.categoryId)
        assertNull(t.paymentMethod)
        assertEquals("bank", t.toAccountId)
    }

    @Test
    fun `editing keeps original time of day`() = runTest {
        val repo = FakeTransactions()
        val original = Instant.parse("2026-11-20T15:45:00Z")
        SaveTransactionUseCase(repo, clock)(draft().copy(id = "t1"), existingOccurredAt = original)
        assertEquals(original, repo.saved.single().occurredAt)
    }

    @Test
    fun `defaults are created once`() = runTest {
        val categories = object : CategoryRepository {
            val list = mutableListOf<Category>()
            override fun observe(): Flow<List<Category>> = MutableStateFlow(list)
            override suspend fun save(category: Category) { list += category }
            override suspend fun deleteIfUnused(id: String) = true
            override suspend fun count() = list.size
        }
        val accounts = object : AccountRepository {
            val list = mutableListOf<Account>()
            override fun observeBalances(): Flow<List<AccountBalance>> = MutableStateFlow(emptyList())
            override suspend fun save(account: Account) { list += account }
            override suspend fun setArchived(id: String, archived: Boolean) = Unit
            override suspend fun count() = list.size
        }
        val use = EnsureDefaultDataUseCase(categories, accounts, AppConfig("a", "1", "", "", ""))
        use()
        use()
        assertEquals(DefaultData.categories.size, categories.list.size)
        assertEquals(listOf("Cash"), accounts.list.map { it.name })
    }

    @Test
    fun `budget rules`() = runTest {
        val budgets = object : BudgetRepository {
            val list = mutableListOf<Budget>()
            override fun observe(): Flow<List<Budget>> = MutableStateFlow(list)
            override suspend fun save(budget: Budget) { list += budget }
            override suspend fun delete(id: String) = Unit
        }
        val use = SaveBudgetUseCase(budgets)
        val existing = listOf(Budget("b1", "food", BudgetPeriod.MONTHLY, 100, "INR"))
        assertEquals(BudgetSaveResult.AmountInvalid, use(null, "food", BudgetPeriod.MONTHLY, "0", "INR", existing))
        assertEquals(BudgetSaveResult.Duplicate, use(null, "food", BudgetPeriod.MONTHLY, "500", "INR", existing))
        assertEquals(BudgetSaveResult.Saved, use("b1", "food", BudgetPeriod.MONTHLY, "500", "INR", existing))
        assertEquals(BudgetSaveResult.Saved, use(null, "food", BudgetPeriod.WEEKLY, "500", "INR", existing))
    }

    @Test
    fun `account names must be unique ignoring case`() {
        val use = SaveAccountUseCase(object : AccountRepository {
            override fun observeBalances(): Flow<List<AccountBalance>> = MutableStateFlow(emptyList())
            override suspend fun save(account: Account) = Unit
            override suspend fun setArchived(id: String, archived: Boolean) = Unit
            override suspend fun count() = 0
        })
        val existing = listOf(Account("a1", "HDFC", AccountType.BANK, "INR", 0))
        assertEquals(NameError.DUPLICATE, use.validate("hdfc ", existing, null))
        assertNull(use.validate("HDFC", existing, "a1"))
        assertEquals(NameError.REQUIRED, use.validate("  ", existing, null))
    }
}
