package com.dailymoney.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dailymoney.core.database.entity.AccountEntity
import com.dailymoney.core.database.entity.CategoryEntity
import com.dailymoney.core.database.entity.TransactionEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Runs the real SQL (plain SQLite in memory; encryption is a separate concern). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MoneyDaoTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    private fun account(id: String, opening: Long) = AccountEntity(id, id, "BANK", "INR", opening, false, 1, 1)

    private fun txn(id: String, type: String, amount: Long, account: String, to: String? = null, at: Long = 1000, deleted: Long? = null, category: String? = null) =
        TransactionEntity(
            id = id, creatorId = "u", type = type, amountMinor = amount, currency = "INR",
            categoryId = category, accountId = account, toAccountId = to, occurredAt = at, note = null,
            paymentMethod = null, createdAt = 1, updatedAt = 1, deletedAt = deleted,
        )

    @Test
    fun `balance includes income, expenses and transfers and ignores deleted rows`() = runTest {
        val accounts = db.accountDao()
        val txns = db.transactionDao()
        accounts.upsert(account("cash", 10_000))
        accounts.upsert(account("bank", 0))
        txns.upsert(txn("t1", "CREDIT", 50_000, "bank"))
        txns.upsert(txn("t2", "DEBIT", 2_500, "cash"))
        txns.upsert(txn("t3", "TRANSFER", 20_000, "bank", to = "cash"))
        txns.upsert(txn("t4", "DEBIT", 99_999, "cash", deleted = 5))

        val balances = accounts.observeWithBalances().first().associate { it.account.id to it.balanceMinor }
        assertEquals(10_000 - 2_500 + 20_000L, balances["cash"])
        assertEquals(50_000 - 20_000L, balances["bank"])
    }

    @Test
    fun `range query is half open and newest first`() = runTest {
        db.accountDao().upsert(account("cash", 0))
        val txns = db.transactionDao()
        txns.upsert(txn("a", "DEBIT", 1, "cash", at = 100))
        txns.upsert(txn("b", "DEBIT", 1, "cash", at = 199))
        txns.upsert(txn("c", "DEBIT", 1, "cash", at = 200))

        assertEquals(listOf("b", "a"), txns.observeInRange(100, 200).first().map { it.id })
    }

    @Test
    fun `soft delete hides a transaction and frees its category`() = runTest {
        db.accountDao().upsert(account("cash", 0))
        db.categoryDao().upsert(CategoryEntity("food", "Food", "EXPENSE", "food", 1, true, null, 1))
        val txns = db.transactionDao()
        txns.upsert(txn("t1", "DEBIT", 100, "cash", category = "food"))
        assertEquals(1, txns.countForCategory("food"))

        txns.softDelete("t1", now = 50)
        assertEquals(null, txns.get("t1"))
        assertEquals(0, txns.countForCategory("food"))
        assertEquals(emptyList<TransactionEntity>(), txns.observeRecent(10).first())
    }
}
