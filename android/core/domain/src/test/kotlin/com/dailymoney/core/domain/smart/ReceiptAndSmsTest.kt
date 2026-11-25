package com.dailymoney.core.domain.smart

import com.dailymoney.core.domain.money.TransactionType
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReceiptAndSmsTest {

    private val today = LocalDate.of(2026, 11, 25)

    @Test
    fun `restaurant bill reads grand total, date and shop`() {
        val rows = listOf(
            "SARAVANA BHAVAN",
            "No 12, T Nagar, Chennai",
            "GSTIN: 33ABCDE1234F1Z5",
            "Bill No: 4521   Date: 23/11/2026 13:45",
            "Masala Dosa   2   180.00",
            "Filter Coffee 2    60.00",
            "Sub Total         240.00",
            "CGST 2.5%           6.00",
            "SGST 2.5%           6.00",
            "Grand Total     ₹ 252.00",
            "Thank you! Visit again",
        )
        val scan = ReceiptParser.parse(rows, "INR", today)
        assertEquals(25200L, scan.amountMinor)
        assertEquals(LocalDate.of(2026, 11, 23), scan.date)
        assertEquals("Saravana Bhavan", scan.merchant)
    }

    @Test
    fun `total on the next line and words for the date`() {
        val rows = listOf("Fresh Mart Supermarket", "Tax Invoice", "12 Nov 2026", "Items: 3", "TOTAL", "1,245.50", "Total Savings 55.00")
        val scan = ReceiptParser.parse(rows, "INR", today)
        assertEquals(124550L, scan.amountMinor)
        assertEquals(LocalDate.of(2026, 11, 12), scan.date)
        assertEquals("Fresh Mart Supermarket", scan.merchant)
    }

    @Test
    fun `no total label falls back to the biggest amount with paise and ignores phone numbers`() {
        val rows = listOf("Ph 9876543210", "Bread 45.00", "Milk 30.00", "Paid 75.00")
        assertEquals(7500L, ReceiptParser.parse(rows, "INR", today).amountMinor)
    }

    @Test
    fun `future dates are not believed`() {
        assertNull(ReceiptParser.parse(listOf("Total 10.00", "Valid till 31/12/2027"), "INR", today).date)
    }

    @Test
    fun `ocr lines on one row are joined left to right`() {
        val lines = listOf(
            OcrLine("450.00", 300, 102, 360, 120),
            OcrLine("TOTAL", 10, 100, 80, 118),
            OcrLine("Thanks", 10, 140, 80, 158),
        )
        assertEquals(listOf("TOTAL 450.00", "Thanks"), ReceiptParser.rows(lines))
    }

    @Test
    fun `upi debit message`() {
        val sms = "Rs.250.00 debited from A/c XX1234 on 24-11-26 to VPA swiggy@icici (UPI Ref 412345678901). Not you? Call 18001234"
        val t = BankSmsParser.parse(sms, "INR", today)!!
        assertEquals(TransactionType.EXPENSE, t.type)
        assertEquals(25000L, t.amountMinor)
        assertEquals("swiggy", t.counterparty)
        assertEquals(LocalDate.of(2026, 11, 24), t.date)
        assertEquals("1234", t.accountHint)
    }

    @Test
    fun `card spend message`() {
        val sms = "INR 1,499.00 spent on HDFC Bank Card x5678 at AMAZON PAY INDIA on 2026-11-20:10:21:33. Avl Lmt: INR 45,000"
        val t = BankSmsParser.parse(sms, "INR", today)!!
        assertEquals(TransactionType.EXPENSE, t.type)
        assertEquals(149900L, t.amountMinor)
        assertEquals("AMAZON PAY INDIA", t.counterparty)
        assertEquals("5678", t.accountHint)
    }

    @Test
    fun `salary credit message`() {
        val sms = "Your a/c no. XXXXXX4321 is credited with INR 52,000.00 on 01-11-2026 by NEFT from ACME TECH PVT LTD. Avl Bal INR 80,120.55"
        val t = BankSmsParser.parse(sms, "INR", today)!!
        assertEquals(TransactionType.INCOME, t.type)
        assertEquals(5200000L, t.amountMinor)
        assertEquals("ACME TECH PVT LTD", t.counterparty)
        assertEquals(LocalDate.of(2026, 11, 1), t.date)
    }

    @Test
    fun `otp, requests and reminders are not payments`() {
        assertNull(BankSmsParser.parse("123456 is your OTP for a payment of Rs 500 at Flipkart", "INR", today))
        assertNull(BankSmsParser.parse("Ravi has requested Rs 300 from you on Google Pay", "INR", today))
        assertNull(BankSmsParser.parse("Your card bill of Rs 4,500 is due on 05-12-2026", "INR", today))
        assertNull(BankSmsParser.parse("Hello, see you at 5", "INR", today))
    }
}
