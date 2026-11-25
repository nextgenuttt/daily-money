package com.dailymoney.core.domain.money

import com.dailymoney.core.domain.sharing.Sharing
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

enum class TransactionType { EXPENSE, INCOME, TRANSFER }

enum class AccountType { CASH, BANK, UPI, CREDIT_CARD, WALLET }

enum class CategoryKind { EXPENSE, INCOME }

enum class PaymentMethod { CASH, UPI, CARD, BANK_TRANSFER, WALLET, OTHER }

/** Where a transaction came from (LLD §3); imports also carry a dedupe hash. */
enum class TransactionSource { MANUAL, RECURRING, OCR, STATEMENT_IMPORT, SMS }

enum class BudgetPeriod { WEEKLY, MONTHLY, YEARLY }

data class Account(
    val id: String,
    val name: String,
    val type: AccountType,
    val currency: String,
    val openingMinor: Long,
    val archived: Boolean = false,
)

data class AccountBalance(val account: Account, val balanceMinor: Long)

data class Category(
    val id: String,
    val name: String,
    val kind: CategoryKind,
    val iconKey: String,
    val colorIndex: Int,
    val isSystem: Boolean,
    val parentId: String? = null,
)

data class Transaction(
    val id: String,
    val type: TransactionType,
    val amountMinor: Long,
    val currency: String,
    val categoryId: String?,
    val accountId: String,
    val toAccountId: String?,
    val occurredAt: Instant,
    val note: String?,
    val paymentMethod: PaymentMethod?,
    /** Null for personal transactions, which never leave the phone. */
    val sharing: Sharing? = null,
    val source: TransactionSource = TransactionSource.MANUAL,
    /** Set on statement imports so the same line is never imported twice. */
    val dedupeHash: String? = null,
)

data class TransactionDetails(
    val transaction: Transaction,
    val category: Category?,
    val account: Account?,
    val toAccount: Account?,
)

data class Budget(
    val id: String,
    val categoryId: String?,
    val period: BudgetPeriod,
    val limitMinor: Long,
    val currency: String,
)

/** Half-open time range [start, endExclusive). */
data class DateRange(val start: Instant, val endExclusive: Instant) {
    operator fun contains(instant: Instant) = !instant.isBefore(start) && instant.isBefore(endExclusive)

    companion object {
        fun month(month: YearMonth, zone: ZoneId) = DateRange(
            month.atDay(1).atStartOfDay(zone).toInstant(),
            month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant(),
        )

        fun period(period: BudgetPeriod, today: LocalDate, zone: ZoneId): DateRange = when (period) {
            BudgetPeriod.MONTHLY -> month(YearMonth.from(today), zone)
            BudgetPeriod.WEEKLY -> {
                val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
                DateRange(monday.atStartOfDay(zone).toInstant(), monday.plusWeeks(1).atStartOfDay(zone).toInstant())
            }
            BudgetPeriod.YEARLY -> {
                val first = today.withDayOfYear(1)
                DateRange(first.atStartOfDay(zone).toInstant(), first.plusYears(1).atStartOfDay(zone).toInstant())
            }
        }
    }
}

data class TransactionFilter(
    val range: DateRange,
    val type: TransactionType? = null,
    val query: String = "",
)
