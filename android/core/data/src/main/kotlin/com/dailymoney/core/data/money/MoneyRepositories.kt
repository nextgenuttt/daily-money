package com.dailymoney.core.data.money

import com.dailymoney.core.database.AppDatabase
import com.dailymoney.core.database.DatabaseManager
import com.dailymoney.core.database.entity.AccountEntity
import com.dailymoney.core.database.entity.BudgetEntity
import com.dailymoney.core.database.entity.CategoryEntity
import com.dailymoney.core.database.entity.TransactionEntity
import com.dailymoney.core.domain.money.Account
import com.dailymoney.core.domain.money.AccountBalance
import com.dailymoney.core.domain.money.AccountRepository
import com.dailymoney.core.domain.money.AccountType
import com.dailymoney.core.domain.money.Budget
import com.dailymoney.core.domain.money.BudgetPeriod
import com.dailymoney.core.domain.money.BudgetRepository
import com.dailymoney.core.domain.money.Category
import com.dailymoney.core.domain.money.CategoryKind
import com.dailymoney.core.domain.money.CategoryRepository
import com.dailymoney.core.domain.money.DateRange
import com.dailymoney.core.domain.money.PaymentMethod
import com.dailymoney.core.domain.money.Transaction
import com.dailymoney.core.domain.money.TransactionRepository
import com.dailymoney.core.domain.money.TransactionType
import com.google.firebase.auth.FirebaseAuth
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/*
 * DAOs are fetched from DatabaseManager on every call: the database is closed and re-created
 * when the user signs out, so a DAO must never be cached.
 */

@Singleton
class RoomAccountRepository @Inject constructor(
    private val db: DatabaseManager,
    private val clock: Clock,
) : AccountRepository {

    override fun observeBalances(): Flow<List<AccountBalance>> = db.stream { accountDao().observeWithBalances() }
        .map { rows -> rows.map { AccountBalance(it.account.toDomain(), it.balanceMinor) } }

    override suspend fun save(account: Account) = io {
        val now = clock.millis()
        val existing = db.get().accountDao().get(account.id)
        db.get().accountDao().upsert(
            AccountEntity(
                id = account.id,
                name = account.name,
                type = account.type.name,
                currency = account.currency,
                openingMinor = account.openingMinor,
                archived = account.archived,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
                version = (existing?.version ?: 0) + 1,
            ),
        )
    }

    override suspend fun setArchived(id: String, archived: Boolean) = io {
        db.get().accountDao().setArchived(id, archived, clock.millis())
    }

    override suspend fun count(): Int = io { db.get().accountDao().count() }
}

@Singleton
class RoomCategoryRepository @Inject constructor(
    private val db: DatabaseManager,
    private val clock: Clock,
) : CategoryRepository {

    override fun observe(): Flow<List<Category>> = db.stream { categoryDao().observeAll() }
        .map { rows -> rows.map { it.toDomain() } }

    override suspend fun save(category: Category) = io {
        val existing = db.get().categoryDao().get(category.id)
        db.get().categoryDao().upsert(
            CategoryEntity(
                id = category.id,
                name = category.name,
                kind = category.kind.name,
                iconKey = category.iconKey,
                colorIndex = category.colorIndex,
                isSystem = category.isSystem,
                parentId = category.parentId,
                updatedAt = clock.millis(),
                version = (existing?.version ?: 0) + 1,
            ),
        )
    }

    override suspend fun deleteIfUnused(id: String): Boolean = io {
        val database = db.get()
        if (database.transactionDao().countForCategory(id) > 0) return@io false
        val now = clock.millis()
        database.budgetDao().softDeleteForCategory(id, now)
        database.categoryDao().softDelete(id, now)
        true
    }

    override suspend fun count(): Int = io { db.get().categoryDao().count() }
}

@Singleton
class RoomTransactionRepository @Inject constructor(
    private val db: DatabaseManager,
    private val clock: Clock,
    private val auth: FirebaseAuth,
) : TransactionRepository {

    override fun observeInRange(range: DateRange): Flow<List<Transaction>> =
        db.stream { transactionDao().observeInRange(range.start.toEpochMilli(), range.endExclusive.toEpochMilli()) }
            .map { rows -> rows.map { it.toDomain() } }

    override fun observeRecent(limit: Int): Flow<List<Transaction>> = db.stream { transactionDao().observeRecent(limit) }
        .map { rows -> rows.map { it.toDomain() } }

    override suspend fun get(id: String): Transaction? = io { db.get().transactionDao().get(id)?.toDomain() }

    override suspend fun save(transaction: Transaction) = io {
        val now = clock.millis()
        val existing = db.get().transactionDao().get(transaction.id)
        db.get().transactionDao().upsert(
            TransactionEntity(
                id = transaction.id,
                creatorId = existing?.creatorId ?: auth.currentUser?.uid.orEmpty(),
                type = transaction.type.toDb(),
                amountMinor = transaction.amountMinor,
                currency = transaction.currency,
                categoryId = transaction.categoryId,
                accountId = transaction.accountId,
                toAccountId = transaction.toAccountId,
                occurredAt = transaction.occurredAt.toEpochMilli(),
                note = transaction.note,
                paymentMethod = transaction.paymentMethod?.name,
                version = (existing?.version ?: 0) + 1,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            ),
        )
    }

    override suspend fun delete(id: String) = io { db.get().transactionDao().softDelete(id, clock.millis()) }
}

@Singleton
class RoomBudgetRepository @Inject constructor(
    private val db: DatabaseManager,
    private val clock: Clock,
) : BudgetRepository {

    override fun observe(): Flow<List<Budget>> = db.stream { budgetDao().observeAll() }
        .map { rows -> rows.map { it.toDomain() } }

    override suspend fun save(budget: Budget) = io {
        val now = clock.millis()
        db.get().budgetDao().upsert(
            BudgetEntity(
                id = budget.id,
                categoryId = budget.categoryId,
                period = budget.period.name,
                limitMinor = budget.limitMinor,
                currency = budget.currency,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    override suspend fun delete(id: String) = io { db.get().budgetDao().softDelete(id, clock.millis()) }
}

/** Opens the database lazily on a background thread, then streams the DAO's flow. */
internal fun <T> DatabaseManager.stream(block: AppDatabase.() -> Flow<T>): Flow<T> =
    flow<T> { emitAll(get().block()) }.flowOn(Dispatchers.IO)

internal suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

internal fun TransactionType.toDb() = when (this) {
    TransactionType.EXPENSE -> "DEBIT"
    TransactionType.INCOME -> "CREDIT"
    TransactionType.TRANSFER -> "TRANSFER"
}

internal fun String.toTransactionType() = when (this) {
    "CREDIT" -> TransactionType.INCOME
    "TRANSFER" -> TransactionType.TRANSFER
    else -> TransactionType.EXPENSE
}

private fun AccountEntity.toDomain() = Account(
    id = id,
    name = name,
    type = runCatching { AccountType.valueOf(type) }.getOrDefault(AccountType.CASH),
    currency = currency,
    openingMinor = openingMinor,
    archived = archived,
)

private fun CategoryEntity.toDomain() = Category(
    id = id,
    name = name,
    kind = runCatching { CategoryKind.valueOf(kind) }.getOrDefault(CategoryKind.EXPENSE),
    iconKey = iconKey,
    colorIndex = colorIndex,
    isSystem = isSystem,
    parentId = parentId,
)

private fun TransactionEntity.toDomain() = Transaction(
    id = id,
    type = type.toTransactionType(),
    amountMinor = amountMinor,
    currency = currency,
    categoryId = categoryId,
    accountId = accountId,
    toAccountId = toAccountId,
    occurredAt = Instant.ofEpochMilli(occurredAt),
    note = note,
    paymentMethod = paymentMethod?.let { runCatching { PaymentMethod.valueOf(it) }.getOrNull() },
)

private fun BudgetEntity.toDomain() = Budget(
    id = id,
    categoryId = categoryId,
    period = runCatching { BudgetPeriod.valueOf(period) }.getOrDefault(BudgetPeriod.MONTHLY),
    limitMinor = limitMinor,
    currency = currency,
)
