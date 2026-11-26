package com.dailymoney.core.domain.money

import com.dailymoney.core.domain.smart.LabelledNote
import java.time.Instant
import kotlinx.coroutines.flow.Flow

interface AccountRepository {
    fun observeBalances(): Flow<List<AccountBalance>>
    suspend fun save(account: Account)
    suspend fun setArchived(id: String, archived: Boolean)
    suspend fun count(): Int
}

interface CategoryRepository {
    fun observe(): Flow<List<Category>>
    suspend fun save(category: Category)

    /** False when the category is still used by transactions (it is then kept). */
    suspend fun deleteIfUnused(id: String): Boolean
    suspend fun count(): Int
}

interface TransactionRepository {
    /** Transactions in [range], newest first, with no text filtering. */
    fun observeInRange(range: DateRange): Flow<List<Transaction>>
    fun observeRecent(limit: Int): Flow<List<Transaction>>
    suspend fun get(id: String): Transaction?
    suspend fun save(transaction: Transaction)
    suspend fun delete(id: String)

    /** Saves imported transactions in one go, skipping any whose dedupe hash is already there; returns how many were added. */
    suspend fun importAll(transactions: List<Transaction>): Int

    /** Which of [hashes] were imported before (and not deleted). */
    suspend fun importedHashes(hashes: Collection<String>): Set<String>

    /** Notes and the categories the user chose for them since [since], for learning categories. */
    suspend fun labelledNotes(since: Instant): List<LabelledNote>
}

interface BudgetRepository {
    fun observe(): Flow<List<Budget>>
    suspend fun save(budget: Budget)
    suspend fun delete(id: String)
}
