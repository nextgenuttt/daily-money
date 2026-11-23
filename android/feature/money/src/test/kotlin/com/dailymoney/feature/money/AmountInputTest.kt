package com.dailymoney.feature.money

import com.dailymoney.feature.money.common.sanitizeAmountInput
import org.junit.Assert.assertEquals
import org.junit.Test

class AmountInputTest {

    @Test
    fun `keeps digits and one decimal point`() {
        assertEquals("1234.56", sanitizeAmountInput("1,234.56", 2))
        assertEquals("12.34", sanitizeAmountInput("12.3.4", 2))
        assertEquals("12.", sanitizeAmountInput("12.", 2))
        assertEquals("50", sanitizeAmountInput("₹ 50", 2))
    }

    @Test
    fun `limits decimals to the currency`() {
        assertEquals("10.99", sanitizeAmountInput("10.999", 2))
        assertEquals("1500", sanitizeAmountInput("1500.5", 0))
    }

    @Test
    fun `limits whole digits`() {
        assertEquals("12345678901", sanitizeAmountInput("123456789012345", 2))
    }
}
