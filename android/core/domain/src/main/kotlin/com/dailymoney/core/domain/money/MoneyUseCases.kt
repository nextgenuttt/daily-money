package com.dailymoney.core.domain.money

import com.dailymoney.core.domain.sharing.Sharing
import com.dailymoney.core.domain.sharing.SplitCalculator
import com.dailymoney.core.domain.sharing.SplitResult
import com.dailymoney.core.domain.sharing.Visibility
import com.dailymoney.core.domain.config.AppConfig
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

enum class TransactionError { AMOUNT_REQUIRED, AMOUNT_INVALID, ACCOUNT_REQUIRED, CATEGORY_REQUIRED, SAME_ACCOUNT, NOTE_TOO_LONG, MEMBERS_REQUIRED, SPLIT_INVALID }

enum class NameError { REQUIRED, TOO_LONG, DUPLICATE }

sealed interface SaveResult {
    data class Saved(val transaction: Transaction) : SaveResult
    data class Invalid(val errors: Set<TransactionError>) : SaveResult
}

data class TransactionDraft(
    val id: String?,
    val type: TransactionType,
    val amountText: String,
    val currency: String,
    val categoryId: String?,
    val accountId: String?,
    val toAccountId: String?,
    val date: LocalDate,
    val note: String,
    val paymentMethod: PaymentMethod?,
    val sharing: Sharing? = null,
    val source: TransactionSource = TransactionSource.MANUAL,
)

class SaveTransactionUseCase @Inject constructor(
    private val transactions: TransactionRepository,
    private val clock: Clock,
) {
    fun validate(d: TransactionDraft): Set<TransactionError> = buildSet {
        val amount = MoneyParser.parse(d.amountText, d.currency)
        when {
            d.amountText.isBlank() -> add(TransactionError.AMOUNT_REQUIRED)
            amount == null || amount <= 0 -> add(TransactionError.AMOUNT_INVALID)
        }
        if (d.accountId == null) add(TransactionError.ACCOUNT_REQUIRED)
        if (d.type == TransactionType.TRANSFER) {
            if (d.toAccountId == null || d.toAccountId == d.accountId) add(TransactionError.SAME_ACCOUNT)
        } else if (d.categoryId == null) {
            add(TransactionError.CATEGORY_REQUIRED)
        }
        if (d.note.length > NOTE_MAX) add(TransactionError.NOTE_TOO_LONG)
        val sharing = d.sharing
        if (sharing != null && d.type != TransactionType.TRANSFER && sharing.visibility == Visibility.SELECTED_MEMBERS && sharing.memberIds.isEmpty()) {
            add(TransactionError.MEMBERS_REQUIRED)
        }
        val split = sharing?.split
        if (split != null && d.type == TransactionType.EXPENSE && amount != null && amount > 0 &&
            SplitCalculator.split(amount, split.method, split.entries) is SplitResult.Invalid
        ) {
            add(TransactionError.SPLIT_INVALID)
        }
    }

    suspend operator fun invoke(d: TransactionDraft, existingOccurredAt: java.time.Instant? = null): SaveResult {
        val errors = validate(d)
        if (errors.isNotEmpty()) return SaveResult.Invalid(errors)
        val zone = clock.zone
        // Keep the original time of day when only the date is edited; new entries use "now" on the chosen date.
        val time = (existingOccurredAt?.atZone(zone) ?: clock.instant().atZone(zone)).toLocalTime()
        val transaction = Transaction(
            id = d.id ?: Ids.newId(clock.millis()),
            type = d.type,
            amountMinor = MoneyParser.parse(d.amountText, d.currency)!!,
            currency = d.currency,
            categoryId = if (d.type == TransactionType.TRANSFER) null else d.categoryId,
            accountId = d.accountId!!,
            toAccountId = if (d.type == TransactionType.TRANSFER) d.toAccountId else null,
            occurredAt = d.date.atTime(time).atZone(zone).toInstant(),
            note = d.note.trim().ifEmpty { null },
            paymentMethod = if (d.type == TransactionType.TRANSFER) null else d.paymentMethod,
            // Transfers move money between my own accounts, so there is nothing to share.
            sharing = when (d.type) {
                TransactionType.TRANSFER -> null
                // Only expenses are split; income shared with a group is for reporting.
                TransactionType.INCOME -> d.sharing?.copy(split = null)
                TransactionType.EXPENSE -> d.sharing
            },
            source = d.source,
        )
        transactions.save(transaction)
        return SaveResult.Saved(transaction)
    }

    companion object {
        const val NOTE_MAX = 200
    }
}

class DeleteTransactionUseCase @Inject constructor(private val transactions: TransactionRepository) {
    suspend operator fun invoke(id: String) = transactions.delete(id)
}

/** Creates the default categories and a Cash account the first time a user opens the app. */
class EnsureDefaultDataUseCase @Inject constructor(
    private val categories: CategoryRepository,
    private val accounts: AccountRepository,
    private val config: AppConfig,
) {
    suspend operator fun invoke() {
        if (categories.count() == 0) {
            DefaultData.categories.forEach {
                categories.save(Category("sys_${it.key}", it.name, it.kind, it.key, it.colorIndex, isSystem = true))
            }
        }
        if (accounts.count() == 0) {
            accounts.save(Account(Ids.newId(), "Cash", AccountType.CASH, config.defaultCurrency, 0))
        }
    }
}

class SaveAccountUseCase @Inject constructor(private val accounts: AccountRepository) {
    fun validate(name: String, existing: List<Account>, id: String?): NameError? = nameError(name, existing.filter { it.id != id }.map { it.name })

    suspend operator fun invoke(account: Account, existing: List<Account>): NameError? {
        validate(account.name, existing, account.id)?.let { return it }
        accounts.save(account.copy(name = account.name.trim()))
        return null
    }
}

class SaveCategoryUseCase @Inject constructor(private val categories: CategoryRepository) {
    suspend operator fun invoke(category: Category, existing: List<Category>): NameError? {
        val sameKind = existing.filter { it.kind == category.kind && it.id != category.id }.map { it.name }
        nameError(category.name, sameKind)?.let { return it }
        categories.save(category.copy(name = category.name.trim()))
        return null
    }
}

sealed interface BudgetSaveResult {
    data object Saved : BudgetSaveResult
    data object AmountInvalid : BudgetSaveResult
    data object Duplicate : BudgetSaveResult
}

class SaveBudgetUseCase @Inject constructor(private val budgets: BudgetRepository) {
    suspend operator fun invoke(
        id: String?,
        categoryId: String?,
        period: BudgetPeriod,
        limitText: String,
        currency: String,
        existing: List<Budget>,
    ): BudgetSaveResult {
        val limit = MoneyParser.parse(limitText, currency)
        if (limit == null || limit <= 0) return BudgetSaveResult.AmountInvalid
        if (existing.any { it.id != id && it.categoryId == categoryId && it.period == period }) return BudgetSaveResult.Duplicate
        budgets.save(Budget(id ?: Ids.newId(), categoryId, period, limit, currency))
        return BudgetSaveResult.Saved
    }
}

data class DashboardData(
    val month: YearMonth,
    val totals: Totals,
    val overallBudget: BudgetProgress?,
    val totalBalanceMinor: Long,
    val topCategories: List<CategorySlice>,
    val recent: List<TransactionDetails>,
    val hasAnyTransaction: Boolean,
)

class ObserveDashboardUseCase @Inject constructor(
    private val transactions: TransactionRepository,
    private val categories: CategoryRepository,
    private val accounts: AccountRepository,
    private val budgets: BudgetRepository,
    private val clock: Clock,
) {
    operator fun invoke(): Flow<DashboardData> {
        val today = LocalDate.now(clock)
        val month = YearMonth.from(today)
        val window = MoneyMath.budgetWindow(today, clock.zone)
        val monthRange = DateRange.month(month, clock.zone)
        return combine(
            transactions.observeInRange(DateRange(minOf(window.start, monthRange.start), maxOf(window.endExclusive, monthRange.endExclusive))),
            transactions.observeRecent(5),
            categories.observe(),
            accounts.observeBalances(),
            budgets.observe(),
        ) { inWindow, recent, cats, balances, budgetList ->
            val byId = cats.associateBy { it.id }
            val accountsById = balances.associate { it.account.id to it.account }
            val monthTxns = inWindow.filter { it.occurredAt in monthRange }
            DashboardData(
                month = month,
                totals = MoneyMath.totals(monthTxns),
                overallBudget = MoneyMath.budgetProgress(
                    budgetList.filter { it.categoryId == null && it.period == BudgetPeriod.MONTHLY },
                    inWindow, byId, today, clock.zone,
                ).firstOrNull(),
                totalBalanceMinor = balances.filterNot { it.account.archived }.sumOf { it.balanceMinor },
                topCategories = MoneyMath.byCategory(monthTxns, byId).take(4),
                recent = recent.map { it.details(byId, accountsById) },
                hasAnyTransaction = recent.isNotEmpty(),
            )
        }
    }
}

class ObserveBudgetProgressUseCase @Inject constructor(
    private val transactions: TransactionRepository,
    private val categories: CategoryRepository,
    private val budgets: BudgetRepository,
    private val clock: Clock,
) {
    operator fun invoke(): Flow<List<BudgetProgress>> {
        val today = LocalDate.now(clock)
        return combine(
            transactions.observeInRange(MoneyMath.budgetWindow(today, clock.zone)),
            categories.observe(),
            budgets.observe(),
        ) { txns, cats, budgetList ->
            MoneyMath.budgetProgress(budgetList, txns, cats.associateBy { it.id }, today, clock.zone)
        }
    }
}

data class MonthTransactions(
    val totals: Totals,
    val days: List<Pair<LocalDate, List<TransactionDetails>>>,
)

class ObserveMonthTransactionsUseCase @Inject constructor(
    private val transactions: TransactionRepository,
    private val categories: CategoryRepository,
    private val accounts: AccountRepository,
    private val clock: Clock,
) {
    operator fun invoke(month: YearMonth, type: TransactionType?, query: String): Flow<MonthTransactions> =
        combine(
            transactions.observeInRange(DateRange.month(month, clock.zone)),
            categories.observe(),
            accounts.observeBalances(),
        ) { txns, cats, balances ->
            val byId = cats.associateBy { it.id }
            val accountsById = balances.associate { it.account.id to it.account }
            val q = query.trim().lowercase()
            val details = txns.map { it.details(byId, accountsById) }
                .filter { type == null || it.transaction.type == type }
                .filter { q.isEmpty() || it.matches(q) }
            MonthTransactions(
                totals = MoneyMath.totals(details.map { it.transaction }),
                days = details.groupBy { it.transaction.occurredAt.atZone(clock.zone).toLocalDate() }
                    .toList()
                    .sortedByDescending { it.first },
            )
        }
}

data class MonthlyReport(
    val month: YearMonth,
    val totals: Totals,
    val expenseByCategory: List<CategorySlice>,
    val incomeByCategory: List<CategorySlice>,
    val trend: List<MonthPoint>,
    val dailyAverageMinor: Long,
)

class ObserveMonthlyReportUseCase @Inject constructor(
    private val transactions: TransactionRepository,
    private val categories: CategoryRepository,
    private val clock: Clock,
) {
    operator fun invoke(month: YearMonth): Flow<MonthlyReport> {
        val range = DateRange(
            DateRange.month(month.minusMonths(TREND_MONTHS - 1L), clock.zone).start,
            DateRange.month(month, clock.zone).endExclusive,
        )
        val monthRange = DateRange.month(month, clock.zone)
        return combine(transactions.observeInRange(range), categories.observe()) { txns, cats ->
            val byId = cats.associateBy { it.id }
            val inMonth = txns.filter { it.occurredAt in monthRange }
            val totals = MoneyMath.totals(inMonth)
            MonthlyReport(
                month = month,
                totals = totals,
                expenseByCategory = MoneyMath.byCategory(inMonth, byId, TransactionType.EXPENSE),
                incomeByCategory = MoneyMath.byCategory(inMonth, byId, TransactionType.INCOME),
                trend = MoneyMath.monthlyTrend(txns, month, TREND_MONTHS, clock.zone),
                dailyAverageMinor = MoneyMath.dailyAverage(totals.expenseMinor, month, clock),
            )
        }
    }

    companion object {
        const val TREND_MONTHS = 6
    }
}

private fun Transaction.details(categories: Map<String, Category>, accounts: Map<String, Account>) = TransactionDetails(
    transaction = this,
    category = categoryId?.let { categories[it] },
    account = accounts[accountId],
    toAccount = toAccountId?.let { accounts[it] },
)

private fun TransactionDetails.matches(q: String): Boolean =
    transaction.note?.lowercase()?.contains(q) == true ||
        category?.name?.lowercase()?.contains(q) == true ||
        account?.name?.lowercase()?.contains(q) == true ||
        MoneyFormatter.toInput(transaction.amountMinor, transaction.currency).contains(q)

private const val NAME_MAX = 40

private fun nameError(name: String, others: List<String>): NameError? {
    val trimmed = name.trim()
    return when {
        trimmed.isEmpty() -> NameError.REQUIRED
        trimmed.length > NAME_MAX -> NameError.TOO_LONG
        others.any { it.equals(trimmed, ignoreCase = true) } -> NameError.DUPLICATE
        else -> null
    }
}
