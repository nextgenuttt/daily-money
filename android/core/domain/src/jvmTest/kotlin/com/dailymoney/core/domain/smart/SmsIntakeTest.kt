package com.dailymoney.core.domain.smart

import com.dailymoney.core.domain.money.TransactionType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsIntakeTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val at = Instant.parse("2026-11-25T06:30:00Z")
    private val debit = "Rs.250.00 debited from A/c XX1234 on 25-11-26 to VPA swiggy@icici (UPI Ref 412345678901)."

    @Test
    fun `only business sender ids are read, never people`() {
        assertTrue(SmsIntake.isBankSender("AX-HDFCBK"))
        assertTrue(SmsIntake.isBankSender("JM-SBIUPI-S"))
        assertTrue(SmsIntake.isBankSender("ICICIB"))
        assertFalse(SmsIntake.isBankSender("+91 98765 43210"))
        assertFalse(SmsIntake.isBankSender("9876543210"))
        assertFalse(SmsIntake.isBankSender(null))
        assertNull(SmsIntake.suggestion("+919876543210", debit, at, "INR", zone))
    }

    @Test
    fun `a bank debit becomes a suggestion with a stable id`() {
        val s = SmsIntake.suggestion("AX-HDFCBK", debit, at, "INR", zone)
        assertNotNull(s)
        assertEquals(TransactionType.EXPENSE, s!!.payment.type)
        assertEquals(25000L, s.payment.amountMinor)
        assertEquals("swiggy", s.payment.counterparty)
        assertEquals(s.id, SmsIntake.suggestion("AX-HDFCBK", debit, at.plusSeconds(20), "INR", zone)!!.id)
    }

    @Test
    fun `missing date falls back to the day it arrived`() {
        val s = SmsIntake.suggestion("VM-ICICIB", "INR 99.00 spent on ICICI Card XX9876 at NETFLIX.", at, "INR", zone)!!
        assertEquals(LocalDate.of(2026, 11, 25), s.payment.date)
    }

    @Test
    fun `promotions and otps are skipped`() {
        assertNull(SmsIntake.suggestion("AD-HDFCBK", "Congratulations! Pre-approved loan of Rs 5,00,000 will be credited. Apply now", at, "INR", zone))
        assertNull(SmsIntake.suggestion("AD-HDFCBK", "Get Rs 100 cashback credited on your first order. Click here", at, "INR", zone))
        assertNull(SmsIntake.suggestion("AD-HDFCBK", "123456 is your OTP for Rs 500 txn on card XX12", at, "INR", zone))
    }

    @Test
    fun `card offers and limit messages are not payments`() {
        val offers = listOf(
            "Dear Customer, enjoy more spending power! Increase limit on HDFC Bank Credit Card 5461 to Rs.163000 for FREE https://1.hdfc.bank.in/HDFCBK/s/6Xeg7axJ",
            "Your HDFC Bank Credit Card XX5461 credit limit is Rs 1,00,000. Upgrade now",
            "Get a Personal Loan of Rs 5,00,000 on your ICICI Bank Credit Card XX12 in 2 mins",
            "Earn 5X reward points on spends above Rs 2,000 with your Axis Debit Card XX99",
            "Rs 2,000 will be credited to your a/c XX12 on 30-11-26 as salary advance",
        )
        offers.forEach { assertNull(it, SmsIntake.suggestion("JD-HDFCBK", it, at, "INR", zone)) }
    }

    @Test
    fun `real card and account alerts still work`() {
        val spend = SmsIntake.suggestion("AD-HDFCBK", "Rs.1,250.00 spent on HDFC Bank Credit Card XX5461 at AMAZON on 2026-11-24. Avl Limit: Rs 1,20,000", at, "INR", zone)!!
        assertEquals(TransactionType.EXPENSE, spend.payment.type)
        assertEquals(125000L, spend.payment.amountMinor)
        assertEquals("5461", spend.payment.accountHint)
        val marked = SmsIntake.suggestion("VM-SBIINB", "A/c XX1234 Rs 500.00 Dr on 24Nov26 to UPI/ravi. Bal Rs 9,000", at, "INR", zone)!!
        assertEquals(TransactionType.EXPENSE, marked.payment.type)
        val refund = SmsIntake.suggestion("AX-ICICIB", "Rs 349.00 refund credited to your ICICI Bank Credit Card XX0001 from FLIPKART", at, "INR", zone)!!
        assertEquals(TransactionType.INCOME, refund.payment.type)
    }
}
