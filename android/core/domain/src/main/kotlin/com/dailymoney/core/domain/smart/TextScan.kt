package com.dailymoney.core.domain.smart

import com.dailymoney.core.domain.money.MoneyParser
import java.time.LocalDate
import java.time.Month

/** Finds dates and amounts in free text (receipts, bank messages). Day-first, as written in India. */
internal object TextScan {

    private val MONTHS = "jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec"
    private val NUMERIC = Regex("""(?<!\d)(\d{1,4})[/\-.](\d{1,2})[/\-.](\d{2,4})(?!\d)""")
    private val DAY_MONTH = Regex("""(?<!\d)(\d{1,2})[\s\-/]?($MONTHS)[a-z]*[\s\-/,']*(\d{2,4})(?!\d)""", RegexOption.IGNORE_CASE)
    private val MONTH_DAY = Regex("""\b($MONTHS)[a-z]*\.?\s+(\d{1,2}),?\s+(\d{4})(?!\d)""", RegexOption.IGNORE_CASE)
    private val TIME = Regex("""\b\d{1,2}:\d{2}(:\d{2})?\s*([ap]m)?\b""", RegexOption.IGNORE_CASE)
    private val CURRENCY = Regex("""\b(rs|inr)\.?|₹""", RegexOption.IGNORE_CASE)

    /** Amounts like 1,23,456.78 or 450 (not part of a longer number, date or id). */
    private val AMOUNT = Regex("""(?<![\d.,/\-:])(\d{1,3}(?:,\d{2,3})+|\d+)(\.\d{1,2})?(?![\d,]|\.\d|/|-\d|:)""")

    /** The first believable date: not in the future and not older than two years. */
    fun firstDate(text: String, today: LocalDate): LocalDate? = dates(text).firstOrNull { !it.isAfter(today.plusDays(1)) && it.isAfter(today.minusYears(2)) }

    fun dates(text: String): List<LocalDate> {
        val found = mutableListOf<Pair<Int, LocalDate>>()
        NUMERIC.findAll(text).forEach { m ->
            val (a, b, c) = m.destructured
            val date = if (a.length == 4) of(a.toInt(), b.toInt(), c.toInt()) else of(year(c), b.toInt(), a.toInt()) ?: of(year(c), a.toInt(), b.toInt())
            date?.let { found += m.range.first to it }
        }
        DAY_MONTH.findAll(text).forEach { m ->
            val (d, mon, y) = m.destructured
            of(year(y), month(mon), d.toInt())?.let { found += m.range.first to it }
        }
        MONTH_DAY.findAll(text).forEach { m ->
            val (mon, d, y) = m.destructured
            of(year(y), month(mon), d.toInt())?.let { found += m.range.first to it }
        }
        return found.sortedBy { it.first }.map { it.second }
    }

    /** Amounts on a line, in minor units, after dates and times are taken out. */
    fun amounts(line: String, currency: String): List<Long> {
        return AMOUNT.findAll(prepare(line)).mapNotNull { m -> MoneyParser.parse(m.value, currency)?.takeIf { it > 0 } }.toList()
    }

    /** Amounts written with paise (450.00), which on a receipt are almost always money rather than counts or ids. */
    fun decimalAmounts(line: String, currency: String): List<Long> =
        AMOUNT.findAll(prepare(line)).filter { it.groupValues[2].isNotEmpty() }.mapNotNull { MoneyParser.parse(it.value, currency) }.toList()

    /** Takes out dates, times and currency marks (so "Rs.450" reads as 450). */
    private fun prepare(line: String): String =
        listOf(NUMERIC, DAY_MONTH, MONTH_DAY, TIME, CURRENCY).fold(line) { acc, r -> r.replace(acc, " ") }

    private fun month(name: String) = Month.entries[MONTHS.split('|').indexOf(name.take(3).lowercase())].value

    private fun year(text: String) = text.toInt().let { if (text.length == 2) 2000 + it else it }

    private fun of(year: Int, month: Int, day: Int): LocalDate? = runCatching { LocalDate.of(year, month, day) }.getOrNull()
}
