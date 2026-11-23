package com.dailymoney.core.data.planning

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import com.dailymoney.core.data.money.io
import com.dailymoney.core.domain.money.CsvExporter
import com.dailymoney.core.domain.money.ExportData
import com.dailymoney.core.domain.money.ExportFormat
import com.dailymoney.core.domain.money.ExportService
import com.dailymoney.core.domain.money.MoneyFormatter
import com.dailymoney.core.domain.money.TransactionType
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.OutputStream
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject
import javax.inject.Singleton

/** Writes an export to a file the user picked with the system "Save as" screen (no storage permission needed). */
@Singleton
class ExportWriter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clock: Clock,
) : ExportService {
    override suspend fun write(targetUri: String, format: ExportFormat, data: ExportData): Boolean = io {
        runCatching {
            context.contentResolver.openOutputStream(Uri.parse(targetUri), "wt").use { out ->
                requireNotNull(out)
                when (format) {
                    ExportFormat.CSV -> out.write(CsvExporter.build(data, clock.zone).toByteArray(Charsets.UTF_8))
                    ExportFormat.PDF -> writePdf(data, out)
                }
            }
        }.isSuccess
    }

    private fun writePdf(data: ExportData, out: OutputStream) {
        val doc = PdfDocument()
        val dates = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        val title = Paint().apply { textSize = 18f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
        val body = Paint().apply { textSize = 9.5f; isAntiAlias = true }
        val bold = Paint(body).apply { typeface = Typeface.DEFAULT_BOLD }
        val muted = Paint(body).apply { color = Color.rgb(90, 102, 97) }
        val line = Paint().apply { color = Color.rgb(195, 204, 200); strokeWidth = 0.5f }
        val right = Paint(body).apply { textAlign = Paint.Align.RIGHT }
        val cols = floatArrayOf(MARGIN, 120f, 300f, PAGE_W - MARGIN)

        var pageNo = 0
        var page: PdfDocument.Page? = null
        var y = 0f

        fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W.toInt(), PAGE_H.toInt(), pageNo).create())
            val c = page!!.canvas
            y = MARGIN + 6f
            if (pageNo == 1) {
                c.drawText("Daily Money statement", MARGIN, y + 12f, title)
                y += 30f
                c.drawText("${data.from.format(dates)} – ${data.to.format(dates)}", MARGIN, y, muted)
                y += 14f
                c.drawText("Generated ${LocalDate.now(clock).format(dates)}", MARGIN, y, muted)
                y += 20f
                c.drawText("Income ${MoneyFormatter.format(data.totals.incomeMinor, data.currency)}", MARGIN, y, bold)
                c.drawText("Expenses ${MoneyFormatter.format(data.totals.expenseMinor, data.currency)}", 200f, y, bold)
                c.drawText("Net ${MoneyFormatter.format(data.totals.netMinor, data.currency)}", 400f, y, bold)
                y += 22f
            }
            listOf("Date", "Category / note", "Account").forEachIndexed { i, h -> c.drawText(h, cols[i], y, bold) }
            c.drawText("Amount", cols[3], y, Paint(bold).apply { textAlign = Paint.Align.RIGHT })
            y += 5f
            c.drawLine(MARGIN, y, PAGE_W - MARGIN, y, line)
            y += ROW_H
            c.drawText("Page $pageNo", PAGE_W - MARGIN, PAGE_H - 20f, Paint(muted).apply { textAlign = Paint.Align.RIGHT })
        }

        newPage()
        if (data.rows.isEmpty()) page!!.canvas.drawText("No transactions in this period.", MARGIN, y, muted)
        data.rows.forEach { d ->
            if (y > PAGE_H - 50f) newPage()
            val c = page!!.canvas
            val t = d.transaction
            val what = when (t.type) {
                TransactionType.TRANSFER -> "Transfer to ${d.toAccount?.name.orEmpty()}"
                else -> listOfNotNull(d.category?.name, t.note).joinToString(" · ")
            }
            c.drawText(t.occurredAt.atZone(clock.zone).toLocalDate().format(dates), cols[0], y, body)
            c.drawText(fit(what, body, cols[2] - cols[1] - 8f), cols[1], y, body)
            c.drawText(fit(d.account?.name.orEmpty(), body, cols[3] - cols[2] - 90f), cols[2], y, body)
            val signed = when (t.type) {
                TransactionType.INCOME -> MoneyFormatter.format(t.amountMinor, t.currency, signed = true)
                TransactionType.EXPENSE -> MoneyFormatter.format(-t.amountMinor, t.currency, signed = true)
                TransactionType.TRANSFER -> MoneyFormatter.format(t.amountMinor, t.currency)
            }
            c.drawText(signed, cols[3], y, right)
            y += ROW_H
        }
        doc.finishPage(page)
        doc.writeTo(out)
        doc.close()
    }

    private fun fit(text: String, paint: Paint, width: Float): String {
        if (paint.measureText(text) <= width) return text
        var end = text.length
        while (end > 0 && paint.measureText(text, 0, end) + paint.measureText("…") > width) end--
        return text.substring(0, end) + "…"
    }

    private companion object {
        const val PAGE_W = 595f
        const val PAGE_H = 842f
        const val MARGIN = 40f
        const val ROW_H = 16f
    }
}
