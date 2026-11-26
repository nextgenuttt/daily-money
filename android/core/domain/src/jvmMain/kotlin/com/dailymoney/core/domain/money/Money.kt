package com.dailymoney.core.domain.money

import java.math.BigDecimal

/** Amounts are whole minor units (paise, cents) so sums are exact. Never use Float/Double for money. */
data class Money(val minor: Long, val currency: String) {
    operator fun plus(other: Money): Money {
        require(currency == other.currency) { "Currency mismatch: $currency vs ${other.currency}" }
        return Money(Math.addExact(minor, other.minor), currency)
    }

    operator fun minus(other: Money): Money {
        require(currency == other.currency) { "Currency mismatch: $currency vs ${other.currency}" }
        return Money(Math.subtractExact(minor, other.minor), currency)
    }

    fun toDecimal(): BigDecimal = BigDecimal.valueOf(minor, Currencies.exponent(currency))
}

object Currencies {
    const val DEFAULT = "INR"

    private val zeroDecimal = setOf("JPY", "KRW", "VND", "CLP", "ISK", "UGX")
    private val threeDecimal = setOf("KWD", "BHD", "OMR", "JOD", "TND", "IQD", "LYD")
    private val symbols = mapOf(
        "INR" to "₹", "USD" to "$", "EUR" to "€", "GBP" to "£", "JPY" to "¥",
        "AED" to "AED ", "SGD" to "S$", "AUD" to "A$", "CAD" to "C$",
    )

    fun exponent(code: String): Int = when (code) {
        in zeroDecimal -> 0
        in threeDecimal -> 3
        else -> 2
    }

    fun symbol(code: String): String = symbols[code] ?: "$code "
}

object MoneyFormatter {

    /**
     * ₹1,23,456.50 for INR (Indian lakh grouping), $123,456.50 for others.
     * Written by hand because java.text.DecimalFormat can't do lakh grouping on the JVM.
     */
    fun format(minor: Long, currency: String, signed: Boolean = false, showDecimals: Boolean = true): String {
        val exp = Currencies.exponent(currency)
        val negative = minor < 0
        val abs = BigDecimal.valueOf(minor, exp).abs()
        val whole = abs.toBigInteger().toString()
        val fraction = if (exp == 0) "" else abs.remainder(BigDecimal.ONE).movePointRight(exp).toBigInteger()
            .toString().padStart(exp, '0')
        val grouped = if (currency == "INR") groupIndian(whole) else groupThousands(whole)
        val body = if (showDecimals && fraction.isNotEmpty()) "$grouped.$fraction" else grouped
        val sign = when {
            negative -> "−"
            signed && minor > 0 -> "+"
            else -> ""
        }
        return sign + Currencies.symbol(currency) + body
    }

    fun format(money: Money, signed: Boolean = false): String = format(money.minor, money.currency, signed)

    /** Plain number for an input field: 1234.5 → "1234.50", no symbol, no grouping. */
    fun toInput(minor: Long, currency: String): String =
        BigDecimal.valueOf(minor, Currencies.exponent(currency)).toPlainString()

    private fun groupThousands(digits: String): String =
        digits.reversed().chunked(3).joinToString(",").reversed()

    private fun groupIndian(digits: String): String {
        if (digits.length <= 3) return digits
        val last3 = digits.takeLast(3)
        val rest = digits.dropLast(3).reversed().chunked(2).joinToString(",").reversed()
        return "$rest,$last3"
    }
}

object MoneyParser {

    /** Largest amount we accept: ₹10,00,00,00,000 (1e10 major units). */
    private val MAX = BigDecimal("10000000000")

    /** "1,234.5" → 123450 for INR. Returns null for empty, invalid, negative, too many decimals or too large. */
    fun parse(text: String, currency: String): Long? {
        val cleaned = text.trim().replace(",", "").replace(" ", "")
        if (cleaned.isEmpty() || !cleaned.matches(Regex("^\\d*\\.?\\d*$")) || cleaned == ".") return null
        val value = cleaned.toBigDecimalOrNull() ?: return null
        val exp = Currencies.exponent(currency)
        if (value.scale() > exp || value > MAX) return null
        return value.movePointRight(exp).longValueExact()
    }
}
