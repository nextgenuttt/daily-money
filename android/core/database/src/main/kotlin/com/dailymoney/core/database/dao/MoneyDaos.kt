package com.dailymoney.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.dailymoney.core.database.entity.AccountEntity
import com.dailymoney.core.database.entity.AccountWithBalance
import com.dailymoney.core.database.entity.BudgetEntity
import com.dailymoney.core.database.entity.CategoryEntity
import com.dailymoney.core.database.entity.CategoryRuleEntity
import com.dailymoney.core.database.entity.TransactionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {

    /** Balance = opening + income − expenses − transfers out + transfers in. */
    @Query(
        """
        SELECT a.*, a.openingMinor
          + COALESCE((SELECT SUM(t.amountMinor) FROM transactions t
                      WHERE t.accountId = a.id AND t.type = 'CREDIT' AND t.deletedAt IS NULL), 0)
          - COALESCE((SELECT SUM(t.amountMinor) FROM transactions t
                      WHERE t.accountId = a.id AND t.type IN ('DEBIT', 'TRANSFER') AND t.deletedAt IS NULL), 0)
          + COALESCE((SELECT SUM(t.amountMinor) FROM transactions t
                      WHERE t.toAccountId = a.id AND t.type = 'TRANSFER' AND t.deletedAt IS NULL), 0)
          AS balanceMinor
        FROM accounts a
        WHERE a.deletedAt IS NULL
        ORDER BY a.archived, a.createdAt
        """,
    )
    fun observeWithBalances(): Flow<List<AccountWithBalance>>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun get(id: String): AccountEntity?

    @Upsert
    suspend fun upsert(account: AccountEntity)

    @Query("UPDATE accounts SET archived = :archived, updatedAt = :now WHERE id = :id")
    suspend fun setArchived(id: String, archived: Boolean, now: Long)

    @Query("SELECT COUNT(*) FROM accounts WHERE deletedAt IS NULL")
    suspend fun count(): Int
}

@Dao
interface CategoryDao {

    @Query("SELECT * FROM categories WHERE deletedAt IS NULL ORDER BY isSystem DESC, name COLLATE NOCASE")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun get(id: String): CategoryEntity?

    @Upsert
    suspend fun upsert(category: CategoryEntity)

    @Query("UPDATE categories SET deletedAt = :now, updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    @Query("SELECT COUNT(*) FROM categories WHERE deletedAt IS NULL")
    suspend fun count(): Int
}

@Dao
interface TransactionDao {

    @Query(
        """
        SELECT * FROM transactions
        WHERE deletedAt IS NULL AND occurredAt >= :start AND occurredAt < :end
        ORDER BY occurredAt DESC
        """,
    )
    fun observeInRange(start: Long, end: Long): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE deletedAt IS NULL ORDER BY occurredAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE id = :id AND deletedAt IS NULL")
    suspend fun get(id: String): TransactionEntity?

    @Upsert
    suspend fun upsert(transaction: TransactionEntity)

    @Query("UPDATE transactions SET deletedAt = :now, updatedAt = :now, version = version + 1 WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    @Query("SELECT COUNT(*) FROM transactions WHERE categoryId = :categoryId AND deletedAt IS NULL")
    suspend fun countForCategory(categoryId: String): Int

    @Query("SELECT dedupeHash FROM transactions WHERE deletedAt IS NULL AND dedupeHash IN (:hashes)")
    suspend fun importedHashes(hashes: List<String>): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<TransactionEntity>): List<Long>

    /** Adds the rows whose hash isn't already there (a second import of the same file adds nothing). */
    @Transaction
    suspend fun importAll(rows: List<TransactionEntity>): Int {
        val existing = rows.mapNotNull { it.dedupeHash }.chunked(HASH_CHUNK).flatMap { importedHashes(it) }.toSet()
        val seen = HashSet<String>()
        val fresh = rows.filter { r -> r.dedupeHash == null || (r.dedupeHash !in existing && seen.add(r.dedupeHash)) }
        return insertAll(fresh).count { it != -1L }
    }

    @Query(
        """
        SELECT note, categoryId FROM transactions
        WHERE deletedAt IS NULL AND note IS NOT NULL AND categoryId IS NOT NULL AND type != 'TRANSFER' AND occurredAt >= :since
        """,
    )
    suspend fun labelledNotes(since: Long): List<NoteCategory>
}

/** Well under SQLite's bound-variable limit. */
private const val HASH_CHUNK = 500

data class NoteCategory(val note: String, val categoryId: String)

@Dao
interface CategoryRuleDao {

    @Query("SELECT * FROM category_rules ORDER BY pattern COLLATE NOCASE")
    fun observeAll(): Flow<List<CategoryRuleEntity>>

    @Upsert
    suspend fun upsert(rule: CategoryRuleEntity)

    @Query("DELETE FROM category_rules WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM category_rules WHERE categoryId = :categoryId")
    suspend fun deleteForCategory(categoryId: String)
}

@Dao
interface BudgetDao {

    @Query("SELECT * FROM budgets WHERE deletedAt IS NULL ORDER BY createdAt")
    fun observeAll(): Flow<List<BudgetEntity>>

    @Upsert
    suspend fun upsert(budget: BudgetEntity)

    @Query("UPDATE budgets SET deletedAt = :now, updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    @Query("UPDATE budgets SET deletedAt = :now, updatedAt = :now WHERE categoryId = :categoryId AND deletedAt IS NULL")
    suspend fun softDeleteForCategory(categoryId: String, now: Long)
}
