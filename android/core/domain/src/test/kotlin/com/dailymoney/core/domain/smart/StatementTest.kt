package com.dailymoney.core.domain.smart

import com.dailymoney.core.domain.money.Category
import com.dailymoney.core.domain.money.CategoryKind
import com.dailymoney.core.domain.money.Transaction
import com.dailymoney.core.domain.money.TransactionSource
import com.dailymoney.core.domain.money.TransactionType
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatementTest {

    private val zone = ZoneId.of("Asia/Kolkata")

    private val hdfc = """
        HDFC BANK Ltd.,,,,,,
        Statement of account,,,,,,
        Date,Narration,Chq./Ref.No.,Value Dt,Withdrawal Amt.,Deposit Amt.,Closing Balance
        01/11/26,UPI-SWIGGY-swiggy@ybl-YESB0000001-412345678901-Payment,0000412345678901,01/11/26,250.00,,"1,20,000.00"
        01/11/26,"NEFT CR-ACME TECH PVT LTD-SALARY NOV",N123456,01/11/26,,"52,000.00","1,72,000.00"
        03/11/26,UPI-SWIGGY-swiggy@ybl-YESB0000001-412345678999-Payment,0000412345678999,03/11/26,250.00,,"1,71,750.00"
        ,,,,,,
        *** End of statement ***,,,,,,
    """.trimIndent()

    @Test
    fun `csv handles quotes, embedded commas and doubled quotes`() {
        val rows = Csv.parse("a,\"b, c\",\"say \"\"hi\"\"\"\r\n1,2,3\n")
        assertEquals(listOf(listOf("a", "b, c", "say \"hi\""), listOf("1", "2", "3")), rows)
    }

    @Test
    fun `semicolon files are detected`() {
        assertEquals(listOf(listOf("Date", "Amount"), listOf("1.11.2026", "-5,00")), Csv.parse("Date;Amount\n1.11.2026;-5,00"))
    }

    @Test
    fun `hdfc style statement with separate withdrawal and deposit columns`() {
        val result = StatementParser.read(Csv.parse(hdfc), "INR") as StatementResult.Ok
        assertEquals(3, result.rows.size)
        assertEquals(1, result.skipped)
        val first = result.rows[0]
        assertEquals(LocalDate.of(2026, 11, 1), first.date)
        assertEquals(TransactionType.EXPENSE, first.type)
        assertEquals(25000L, first.amountMinor)
        assertEquals(TransactionType.INCOME, result.rows[1].type)
        assertEquals(5200000L, result.rows[1].amountMinor)
    }

    @Test
    fun `single amount column with a dr cr column`() {
        val csv = "Txn Date,Value Date,Description,Amount,Dr/Cr,Balance\n" +
            "05-Nov-2026,05-Nov-2026,ATM WDL,\"2,000.00\",DR,1000\n" +
            "06-Nov-2026,06-Nov-2026,INT.PD,12.50,CR,1012.50\n"
        val result = StatementParser.read(Csv.parse(csv), "INR") as StatementResult.Ok
        assertEquals(listOf(TransactionType.EXPENSE, TransactionType.INCOME), result.rows.map { it.type })
        assertEquals(LocalDate.of(2026, 11, 5), result.rows[0].date)
        assertEquals(200000L, result.rows[0].amountMinor)
    }

    @Test
    fun `signed amounts and sign markers`() {
        assertEquals(-123450L, StatementParser.amount("(1,234.50)", "INR"))
        assertEquals(-123450L, StatementParser.amount("1,234.50 Dr", "INR"))
        assertEquals(123450L, StatementParser.amount("₹ 1,234.50 Cr", "INR"))
        assertEquals(-50L, StatementParser.amount("-0.50", "INR"))
        assertNull(StatementParser.amount("-", "INR"))
    }

    @Test
    fun `card export where plus means spent`() {
        val rows = Csv.parse("Date,Description,Amount\n2026-11-02,NETFLIX,649.00\n2026-11-03,PAYMENT RECEIVED,-5000.00\n")
        val mapping = StatementParser.detect(rows)!!.copy(positiveIsIncome = false)
        val result = StatementParser.parse(rows, mapping, "INR") as StatementResult.Ok
        assertEquals(listOf(TransactionType.EXPENSE, TransactionType.INCOME), result.rows.map { it.type })
    }

    @Test
    fun `unknown headers ask the user to map columns`() {
        val result = StatementParser.read(Csv.parse("When,What,How much\n1/11/2026,Tea,10\n"), "INR")
        assertTrue(result is StatementResult.NeedsMapping)
        assertEquals(listOf("When", "What", "How much"), (result as StatementResult.NeedsMapping).columns)
    }

    @Test
    fun `hashes are stable across imports and keep identical lines apart`() {
        val rows = (StatementParser.read(Csv.parse(hdfc), "INR") as StatementResult.Ok).rows
        val twice = listOf(rows[0], rows[0])
        val h = StatementImport.hashes("acc", twice)
        assertNotEquals(h[0], h[1])
        assertEquals(h, StatementImport.hashes("acc", twice))
        assertNotEquals(h[0], StatementImport.hashes("other", twice)[0])
    }

    @Test
    fun `candidates flag already imported lines and hand-entered look-alikes`() {
        val rows = (StatementParser.read(Csv.parse(hdfc), "INR") as StatementResult.Ok).rows
        val hashes = StatementImport.hashes("acc", rows)
        val handEntered = Transaction(
            "t1", TransactionType.EXPENSE, 25000, "INR", "sys_food", "acc", null,
            LocalDate.of(2026, 11, 2).atTime(20, 0).atZone(zone).toInstant(), "dinner", null,
        )
        val categories = listOf(
            Category("sys_food", "Food", CategoryKind.EXPENSE, "food", 0, true),
            Category("sys_others", "Others", CategoryKind.EXPENSE, "others", 0, true),
            Category("sys_salary", "Salary", CategoryKind.INCOME, "salary", 0, true),
        )
        val out = StatementImport.candidates(
            "acc", rows, setOf(hashes[1]), listOf(handEntered), Categorizer(emptyList(), emptyList(), categories),
            mapOf(CategoryKind.EXPENSE to categories[1], CategoryKind.INCOME to null), zone,
        )
        // Only one of the two ₹250 Swiggy lines can be the one typed in by hand.
        assertEquals(listOf(ImportStatus.POSSIBLE_DUPLICATE, ImportStatus.ALREADY_IMPORTED, ImportStatus.NEW), out.map { it.status })
        assertEquals(listOf(false, false, true), out.map { it.selected })
        assertEquals("sys_food", out[0].categoryId)
        assertEquals("sys_salary", out[1].categoryId)

        val clock = Clock.fixed(Instant.parse("2026-11-25T10:00:00Z"), zone)
        val txns = StatementImport.toTransactions("acc", "INR", out.filter { it.selected }, clock)
        assertEquals(1, txns.size)
        assertEquals(TransactionSource.STATEMENT_IMPORT, txns[0].source)
        assertEquals(hashes[2], txns[0].dedupeHash)
        assertEquals(LocalDate.of(2026, 11, 3), txns[0].occurredAt.atZone(zone).toLocalDate())
        assertFalse(txns[0].note.isNullOrBlank())
    }
}
