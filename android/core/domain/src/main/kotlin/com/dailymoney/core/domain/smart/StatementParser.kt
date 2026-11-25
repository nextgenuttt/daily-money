package com.dailymoney.core.domain.smart

import com.dailymoney.core.domain.money.MoneyParser
import com.dailymoney.core.domain.money.TransactionType
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale

/** RFC 4180 CSV: quoted fields, doubled quotes, commas/newlines inside quotes; also ; and tab files. */
object Csv {

    fun parse(text: String): List<List<String>> {
        val body = text.removePrefix("﻿")
        val delimiter = delimiterOf(body)
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < body.length) {
            val c = body[i]
            when {
                quoted && c == '"' && body.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
                c == '"' -> quoted = !quoted
                quoted -> cell.append(c)
                c == delimiter -> { row += cell.toString().trim(); cell.clear() }
                c == '\n' || c == '\r' -> {
                    if (c == '\r' && body.getOrNull(i + 1) == '\n') i++
                    row += cell.toString().trim(); cell.clear()
                    if (row.any { it.isNotEmpty() }) rows += row
                    row = mutableListOf()
                }
                else -> cell.append(c)
            }
            i++
        }
        row += cell.toString().trim()
        if (row.any { it.isNotEmpty() }) rows += row
        return rows
    }

    /** The separator that appears most outside quotes in the first lines. */
    private fun delimiterOf(text: String): Char {
        val sample = text.lineSequence().take(40).joinToString("\n").replace(Regex("\"[^\"]*\""), "")
        return listOf(',', ';', '\t', '|').maxBy { d -> sample.count { it == d } }
    }
}

/** How a statement's columns are laid out; indexes are column positions. */
data class ColumnMapping(
    val headerRow: Int,
    val date: Int,
    val description: Int,
    /** Separate withdrawal / deposit columns. */
    val debit: Int? = null,
    val credit: Int? = null,
    /** One amount column, signed or with a Dr/Cr column next to it. */
    val amount: Int? = null,
    val drCr: Int? = null,
    /** For a single signed amount column: whether plus means money in (bank) or spent (many card exports). */
    val positiveIsIncome: Boolean = true,
) {
    val isComplete get() = date >= 0 && description >= 0 && (debit != null || credit != null || amount != null)
}

data class StatementRow(val date: LocalDate, val description: String, val type: TransactionType, val amountMinor: Long)

sealed interface StatementResult {
    data class Ok(val rows: List<StatementRow>, val mapping: ColumnMapping, val skipped: Int) : StatementResult

    /** We couldn't tell which column is which; [columns] are the header names so the user can choose. */
    data class NeedsMapping(val headerRow: Int, val columns: List<String>) : StatementResult

    data object Empty : StatementResult
}

/** Reads bank statement CSVs (HDFC, ICICI, SBI, Axis, Kotak and most others) without a per-bank template. */
object StatementParser {

    private val PATTERNS = listOf(
        "d/M/yyyy", "d/M/yy", "d-M-yyyy", "d-M-yy", "d.M.yyyy", "d.M.yy", "yyyy-M-d", "yyyy/M/d",
        "d MMM yyyy", "d-MMM-yyyy", "d-MMM-yy", "d MMM yy", "d/MMM/yyyy", "d/MMM/yy", "d MMMM yyyy", "MMM d, yyyy", "M/d/yyyy", "M/d/yy",
    ).map { DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(it).toFormatter(Locale.ENGLISH) }
    private val TIME_SUFFIX = Regex("""[\sT]+\d{1,2}:\d{2}(:\d{2})?(\.\d+)?\s*([AaPp][Mm])?$""")
    private const val MAX_HEADER_SCAN = 40
    private val DEBIT_MARKS = setOf("dr", "d", "db", "debit", "withdrawal")
    private val CREDIT_MARKS = setOf("cr", "c", "credit", "deposit")

    fun read(rows: List<List<String>>, currency: String): StatementResult {
        if (rows.isEmpty()) return StatementResult.Empty
        val mapping = detect(rows) ?: run {
            val header = rows.take(MAX_HEADER_SCAN).indexOfFirst { r -> r.count { it.isNotBlank() } >= 3 }.coerceAtLeast(0)
            return StatementResult.NeedsMapping(header, rows[header])
        }
        return parse(rows, mapping, currency)
    }

    fun detect(rows: List<List<String>>): ColumnMapping? {
        rows.take(MAX_HEADER_SCAN).forEachIndexed { index, row ->
            val names = row.map(::normalize)
            val date = names.indexOfBest(listOf("transaction date", "txn date", "tran date", "trans date", "posting date", "booking date", "date"), avoid = "value")
                ?: names.indexOfBest(listOf("value date", "value dt"))
            val drCr = names.indexOfBest(listOf("dr cr", "cr dr", "debit credit", "credit debit", "transaction type", "txn type", "type"))
            val taken = setOfNotNull(drCr)
            val debit = names.indexOfBest(listOf("withdrawal", "withdrawals", "debit", "dr", "paid out", "money out"), avoid = "balance", except = taken)
            val credit = names.indexOfBest(listOf("deposit", "deposits", "credit", "cr", "paid in", "money in"), avoid = "balance", except = taken)
            val amount = names.indexOfBest(listOf("transaction amount", "txn amount", "amount"), avoid = "balance", except = taken + setOfNotNull(debit, credit))
            val description = names.indexOfBest(listOf("narration", "description", "particulars", "transaction remarks", "remarks", "details", "merchant", "payee", "info"))
            if (date != null && description != null && (debit != null || credit != null || amount != null)) {
                return if (debit != null || credit != null) ColumnMapping(index, date, description, debit = debit, credit = credit)
                else ColumnMapping(index, date, description, amount = amount, drCr = drCr)
            }
        }
        return null
    }

    fun parse(rows: List<List<String>>, mapping: ColumnMapping, currency: String): StatementResult {
        val body = rows.drop(mapping.headerRow + 1)
        val format = dateFormat(body.mapNotNull { it.getOrNull(mapping.date) }.filter { it.isNotBlank() })
            ?: return StatementResult.Ok(emptyList(), mapping, body.size)
        var skipped = 0
        val out = body.mapNotNull { row ->
            val parsed = row(row, mapping, format, currency)
            if (parsed == null) skipped++
            parsed
        }
        return StatementResult.Ok(out, mapping, skipped)
    }

    private fun row(cells: List<String>, m: ColumnMapping, format: DateTimeFormatter, currency: String): StatementRow? {
        val date = date(cells.getOrNull(m.date), format) ?: return null
        val description = cells.getOrNull(m.description).orEmpty().replace(Regex("\\s+"), " ").trim()
        val signed: Long = when {
            m.debit != null || m.credit != null -> {
                val out = m.debit?.let { amount(cells.getOrNull(it), currency) }?.let(Math::abs) ?: 0L
                val inn = m.credit?.let { amount(cells.getOrNull(it), currency) }?.let(Math::abs) ?: 0L
                inn - out
            }
            else -> {
                val value = amount(cells.getOrNull(m.amount!!), currency) ?: return null
                val marker = m.drCr?.let { normalize(cells.getOrNull(it).orEmpty()).substringBefore(' ') }
                when (marker) {
                    in DEBIT_MARKS -> -Math.abs(value)
                    in CREDIT_MARKS -> Math.abs(value)
                    else -> when {
                        m.positiveIsIncome -> value
                        else -> -value
                    }
                }
            }
        }
        if (signed == 0L) return null
        return StatementRow(date, description, if (signed < 0) TransactionType.EXPENSE else TransactionType.INCOME, Math.abs(signed))
    }

    /** "1,234.50", "(1,234.50)", "-1234.5", "1234.50 Dr", "₹ 1,234" → minor units, negative for money out. */
    internal fun amount(cell: String?, currency: String): Long? {
        var text = cell?.trim().orEmpty()
        if (text.isEmpty() || text == "-") return null
        var negative = false
        if (text.startsWith("(") && text.endsWith(")")) { negative = true; text = text.trim('(', ')') }
        Regex("""\s*(dr|cr)\.?$""", RegexOption.IGNORE_CASE).find(text)?.let { m ->
            negative = negative || m.groupValues[1].equals("dr", ignoreCase = true)
            text = text.removeRange(m.range)
        }
        text = text.replace(Regex("""(?i)inr|rs\.?|₹|\s"""), "")
        if (text.startsWith("-")) { negative = !negative; text = text.drop(1) }
        if (text.startsWith("+")) text = text.drop(1)
        val value = MoneyParser.parse(text, currency) ?: return null
        return if (negative) -value else value
    }

    /** The pattern that reads the most dates in the column; on a tie the earlier one (day-first) wins. */
    private fun dateFormat(values: List<String>): DateTimeFormatter? {
        val sample = values.take(200)
        return PATTERNS.map { f -> f to sample.count { date(it, f) != null } }.filter { it.second > 0 }.maxByOrNull { it.second }?.first
    }

    private fun date(text: String?, format: DateTimeFormatter): LocalDate? {
        val clean = text?.trim()?.replace(TIME_SUFFIX, "") ?: return null
        return runCatching { LocalDate.parse(clean, format) }.getOrNull()
    }

    private fun normalize(s: String) = s.lowercase().replace(Regex("[^a-z]+"), " ").trim()

    /** Index of the first column whose name contains the earliest keyword in [keys], as whole words. */
    private fun List<String>.indexOfBest(keys: List<String>, avoid: String? = null, except: Set<Int> = emptySet()): Int? {
        for (key in keys) {
            val i = indices.firstOrNull { i -> i !in except && " ${this[i]} ".contains(" $key ") && (avoid == null || avoid !in this[i]) } ?: -1
            if (i >= 0) return i
        }
        return null
    }
}
