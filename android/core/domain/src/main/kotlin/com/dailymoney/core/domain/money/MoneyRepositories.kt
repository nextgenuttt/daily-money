package com.dailymoney.core.domain.money

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
}

interface BudgetRepository {
    fun observe(): Flow<List<Budget>>
    suspend fun save(budget: Budget)
    suspend fun delete(id: String)
}
