package com.dailymoney.core.domain.smart

import java.time.LocalDate

/** What we could read off a receipt photo; anything we aren't sure of is null. */
data class ReceiptScan(val amountMinor: Long?, val date: LocalDate?, val merchant: String?) {
    val isEmpty get() = amountMinor == null && date == null && merchant == null
}

/** One line of recognised text and where it sits on the photo (pixels). */
data class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int)

/** Reads the text on a receipt photo, on the phone. Null when the photo can't be read. */
interface ReceiptTextReader {
    suspend fun read(uri: String): List<OcrLine>?
}

object ReceiptParser {

    /** Highest priority first; a receipt's "Grand Total" beats its "Total". */
    private val TOTALS = listOf(
        "grand total", "net payable", "amount payable", "net amount", "total payable", "bill amount", "total amount", "net total",
        "amount due", "balance due", "to pay", "total",
    )
    /** Lines that mention "total" but aren't the bill total ("Total incl. GST" still counts). */
    private val NOT_TOTAL = listOf("sub total", "subtotal", "sub-total", "qty", "item", "tax", "gst", "saving", "saved", "discount", "round", "tendered", "change", "cash paid")
    private val NOT_MERCHANT = listOf("invoice", "receipt", "bill", "gstin", "gst", "fssai", "phone", "ph:", "tel", "mob", "date", "welcome", "cash memo", "order", "table", "token", "cin", "www", ".com", "@")
    private const val MAX_MINOR = 1_00_00_000_00L

    /**
     * ML Kit returns text in blocks, so a label and its amount ("TOTAL ... 450.00") often come back as
     * separate lines. This puts lines that sit on the same row of the photo back together, left to right.
     */
    fun rows(lines: List<OcrLine>): List<String> {
        val rows = mutableListOf<MutableList<OcrLine>>()
        lines.sortedBy { (it.top + it.bottom) / 2 }.forEach { line ->
            val mid = (line.top + line.bottom) / 2
            val row = rows.lastOrNull()?.takeIf { r -> r.any { mid in it.top..it.bottom } }
            if (row != null) row += line else rows += mutableListOf(line)
        }
        return rows.map { r -> r.sortedBy { it.left }.joinToString(" ") { it.text.trim() } }
    }

    fun parse(rows: List<String>, currency: String, today: LocalDate): ReceiptScan =
        ReceiptScan(total(rows, currency), date(rows, today), merchant(rows))

    private fun total(rows: List<String>, currency: String): Long? {
        val lower = rows.map { it.lowercase() }
        for (keyword in TOTALS) {
            val found = lower.indices.filter { i -> keyword in lower[i] && !notTotal(lower[i]) }.mapNotNull { i ->
                // The amount is usually on the same row, sometimes on the next one.
                TextScan.amounts(rows[i], currency).lastOrNull() ?: rows.getOrNull(i + 1)?.let { TextScan.amounts(it, currency).singleOrNull() }
            }.filter { it in 1..MAX_MINOR }
            found.maxOrNull()?.let { return it }
        }
        // No label we know: the biggest amount written with paise.
        return rows.flatMap { TextScan.decimalAmounts(it, currency) }.filter { it in 1..MAX_MINOR }.maxOrNull()
    }

    private fun notTotal(line: String) = "incl" !in line && NOT_TOTAL.any { it in line }

    private fun date(rows: List<String>, today: LocalDate): LocalDate? = rows.firstNotNullOfOrNull { TextScan.firstDate(it, today) }

    /** The shop name is normally one of the first lines, in capitals. */
    private fun merchant(rows: List<String>): String? = rows.take(5).map { it.trim() }.firstOrNull { line ->
        val lower = line.lowercase()
        line.count(Char::isLetter) >= 3 && line.count(Char::isDigit) <= 2 && NOT_MERCHANT.none { it in lower }
    }?.let(::tidy)

    private fun tidy(name: String): String {
        val clean = name.replace(Regex("\\s+"), " ").trim(' ', '-', '*', '.', ',').take(40)
        return if (clean == clean.uppercase()) clean.lowercase().split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } } else clean
    }
}
