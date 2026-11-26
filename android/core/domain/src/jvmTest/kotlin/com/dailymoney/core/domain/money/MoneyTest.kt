package com.dailymoney.core.domain.money

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyTest {

    @Test
    fun `parse amounts into minor units`() {
        assertEquals(123450L, MoneyParser.parse("1,234.5", "INR"))
        assertEquals(100L, MoneyParser.parse("1", "INR"))
        assertEquals(5L, MoneyParser.parse(".05", "INR"))
        assertEquals(1500L, MoneyParser.parse("1500", "JPY"))
        assertEquals(1234L, MoneyParser.parse("1.234", "KWD"))
    }

    @Test
    fun `reject invalid amounts`() {
        assertNull(MoneyParser.parse("", "INR"))
        assertNull(MoneyParser.parse(".", "INR"))
        assertNull(MoneyParser.parse("-5", "INR"))
        assertNull(MoneyParser.parse("1.234", "INR"))
        assertNull(MoneyParser.parse("1.5", "JPY"))
        assertNull(MoneyParser.parse("12abc", "INR"))
        assertNull(MoneyParser.parse("1e5", "INR"))
        assertNull(MoneyParser.parse("10000000001", "INR"))
    }

    @Test
    fun `format INR with lakh grouping`() {
        assertEquals("₹1,23,45,678.90", MoneyFormatter.format(1234567890L, "INR"))
        assertEquals("₹999.00", MoneyFormatter.format(99900L, "INR"))
        assertEquals("₹1,000.05", MoneyFormatter.format(100005L, "INR"))
        assertEquals("₹0.00", MoneyFormatter.format(0L, "INR"))
    }

    @Test
    fun `format other currencies with thousands grouping`() {
        assertEquals("$1,234,567.89", MoneyFormatter.format(123456789L, "USD"))
        assertEquals("¥1,500", MoneyFormatter.format(1500L, "JPY"))
    }

    @Test
    fun `signs and whole numbers`() {
        assertEquals("+₹50.00", MoneyFormatter.format(5000L, "INR", signed = true))
        assertEquals("−₹50.00", MoneyFormatter.format(-5000L, "INR", signed = true))
        assertEquals("₹1,50,000", MoneyFormatter.format(15000000L, "INR", showDecimals = false))
    }

    @Test
    fun `input text round trips`() {
        assertEquals("1234.50", MoneyFormatter.toInput(123450L, "INR"))
        assertEquals(123450L, MoneyParser.parse(MoneyFormatter.toInput(123450L, "INR"), "INR"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `cannot add different currencies`() {
        Money(100, "INR") + Money(100, "USD")
    }

    @Test
    fun `ids are unique and version 7`() {
        val ids = (1..1000).map { Ids.newId() }.toSet()
        assertEquals(1000, ids.size)
        assertEquals('7', ids.first()[14])
    }
}
