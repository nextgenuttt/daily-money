package com.dailymoney.core.domain.money

import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

enum class ExportFormat { CSV, PDF }

enum class ExportPeriod { THIS_MONTH, LAST_MONTH, THIS_YEAR, LAST_12_MONTHS, ALL_TIME }

data class ExportData(
    val from: LocalDate,
    val to: LocalDate,
    val rows: List<TransactionDetails>,
    val totals: Totals,
    val currency: String,
)

class BuildExportUseCase @Inject constructor(
    private val transactions: TransactionRepository,
    private val categories: CategoryRepository,
    private val accounts: AccountRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(period: ExportPeriod, currency: String): ExportData {
        val today = LocalDate.now(clock)
        val (from, to) = range(period, today)
        val zone = clock.zone
        val dateRange = DateRange(from.atStartOfDay(zone).toInstant(), to.plusDays(1).atStartOfDay(zone).toInstant())
        return combine(transactions.observeInRange(dateRange), categories.observe(), accounts.observeBalances()) { t, c, a ->
            val cats = c.associateBy { it.id }
            val accs = a.associate { it.account.id to it.account }
            val rows = t.sortedBy { it.occurredAt }.map {
                TransactionDetails(it, it.categoryId?.let(cats::get), accs[it.accountId], it.toAccountId?.let(accs::get))
            }
            ExportData(from, to, rows, MoneyMath.totals(t), currency)
        }.first()
    }

    companion object {
        fun range(period: ExportPeriod, today: LocalDate): Pair<LocalDate, LocalDate> = when (period) {
            ExportPeriod.THIS_MONTH -> today.withDayOfMonth(1) to today
            ExportPeriod.LAST_MONTH -> YearMonth.from(today).minusMonths(1).let { it.atDay(1) to it.atEndOfMonth() }
            ExportPeriod.THIS_YEAR -> today.withDayOfYear(1) to today
            ExportPeriod.LAST_12_MONTHS -> today.minusMonths(12).plusDays(1) to today
            ExportPeriod.ALL_TIME -> LocalDate.of(2000, 1, 1) to today
        }
    }
}

/**
 * Excel-compatible CSV: UTF-8 with BOM (so ₹ shows correctly in Excel), CRLF line ends, quoted
 * fields, and cells that start with = + - @ prefixed with ' so a spreadsheet never runs them as
 * formulas (CSV injection).
 */
object CsvExporter {

    private val HEADER = listOf("Date", "Type", "Amount", "Currency", "Category", "Account", "To account", "Paid with", "Note")

    fun build(data: ExportData, zone: ZoneId): String {
        val dateFormat = DateTimeFormatter.ISO_LOCAL_DATE
        val sb = StringBuilder("﻿")
        sb.append(HEADER.joinToString(",") { escape(it) }).append("\r\n")
        data.rows.forEach { d ->
            val t = d.transaction
            val amount = BigDecimal.valueOf(t.amountMinor, Currencies.exponent(t.currency)).toPlainString()
            sb.append(
                listOf(
                    t.occurredAt.atZone(zone).toLocalDate().format(dateFormat),
                    t.type.name.lowercase().replaceFirstChar { it.uppercase() },
                    amount,
                    t.currency,
                    d.category?.name.orEmpty(),
                    d.account?.name.orEmpty(),
                    d.toAccount?.name.orEmpty(),
                    t.paymentMethod?.name?.lowercase()?.replace('_', ' ').orEmpty(),
                    t.note.orEmpty(),
                ).joinToString(",") { escape(it) },
            ).append("\r\n")
        }
        return sb.toString()
    }

    fun escape(value: String): String {
        val safe = if (value.isNotEmpty() && value[0] in "=+-@\t\r") "'$value" else value
        return "\"" + safe.replace("\"", "\"\"") + "\""
    }
}
