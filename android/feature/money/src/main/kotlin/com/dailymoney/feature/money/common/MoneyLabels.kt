package com.dailymoney.feature.money.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.dailymoney.core.domain.money.AccountType
import com.dailymoney.core.domain.money.BudgetPeriod
import com.dailymoney.core.domain.money.NameError
import com.dailymoney.core.domain.money.PaymentMethod
import com.dailymoney.core.domain.money.TransactionError
import com.dailymoney.core.domain.money.TransactionType
import com.dailymoney.feature.money.R
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
fun TransactionType.label(): String = stringResource(
    when (this) {
        TransactionType.EXPENSE -> R.string.money_expense
        TransactionType.INCOME -> R.string.money_income
        TransactionType.TRANSFER -> R.string.money_transfer
    },
)

@Composable
fun PaymentMethod.label(): String = stringResource(
    when (this) {
        PaymentMethod.CASH -> R.string.pay_cash
        PaymentMethod.UPI -> R.string.pay_upi
        PaymentMethod.CARD -> R.string.pay_card
        PaymentMethod.BANK_TRANSFER -> R.string.pay_bank
        PaymentMethod.WALLET -> R.string.pay_wallet
        PaymentMethod.OTHER -> R.string.pay_other
    },
)

@Composable
fun AccountType.label(): String = stringResource(
    when (this) {
        AccountType.CASH -> R.string.account_cash
        AccountType.BANK -> R.string.account_bank
        AccountType.UPI -> R.string.account_upi
        AccountType.CREDIT_CARD -> R.string.account_card
        AccountType.WALLET -> R.string.account_wallet
    },
)

@Composable
fun BudgetPeriod.label(): String = stringResource(
    when (this) {
        BudgetPeriod.WEEKLY -> R.string.budgets_weekly
        BudgetPeriod.MONTHLY -> R.string.budgets_monthly
        BudgetPeriod.YEARLY -> R.string.budgets_yearly
    },
)

@Composable
fun NameError.message(): String = stringResource(
    when (this) {
        NameError.REQUIRED -> R.string.name_err_required
        NameError.TOO_LONG -> R.string.name_err_long
        NameError.DUPLICATE -> R.string.name_err_duplicate
    },
)

@Composable
fun TransactionError.message(): String = stringResource(
    when (this) {
        TransactionError.AMOUNT_REQUIRED -> R.string.edit_err_amount_required
        TransactionError.AMOUNT_INVALID -> R.string.edit_err_amount_invalid
        TransactionError.ACCOUNT_REQUIRED -> R.string.edit_err_account
        TransactionError.CATEGORY_REQUIRED -> R.string.edit_err_category
        TransactionError.SAME_ACCOUNT -> R.string.edit_err_same_account
        TransactionError.NOTE_TOO_LONG -> R.string.edit_err_note
    },
)

fun YearMonth.label(): String = format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()))

@Composable
fun LocalDate.dayLabel(today: LocalDate): String = when (this) {
    today -> stringResource(R.string.money_today)
    today.minusDays(1) -> stringResource(R.string.money_yesterday)
    else -> format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault()))
}

fun LocalDate.mediumLabel(): String = format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

@Composable
fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.money_back))
    }
}

/** Keeps only digits and one decimal point, so the amount field can't hold text the parser rejects. */
fun sanitizeAmountInput(raw: String, maxDecimals: Int): String {
    val filtered = raw.filter { it.isDigit() || it == '.' }
    val firstDot = filtered.indexOf('.')
    if (firstDot < 0) return filtered.take(11)
    val whole = filtered.substring(0, firstDot).take(11)
    if (maxDecimals == 0) return whole
    val fraction = filtered.substring(firstDot + 1).replace(".", "").take(maxDecimals)
    return "$whole.$fraction"
}
