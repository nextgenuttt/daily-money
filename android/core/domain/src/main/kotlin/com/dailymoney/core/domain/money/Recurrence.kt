package com.dailymoney.core.domain.money

import java.time.LocalDate
import java.time.temporal.ChronoUnit

enum class Frequency { DAILY, WEEKLY, MONTHLY, YEARLY }

/**
 * A repeat rule, stored as an RFC 5545 RRULE subset (FREQ + INTERVAL) so Phase 3 reminders can
 * extend the same format (LLD §9). Occurrence n is always computed from the original start date,
 * so "monthly on the 31st" gives 28/29 Feb and then the 31st again, never drifting.
 */
data class Recurrence(val frequency: Frequency, val interval: Int = 1) {

    init {
        require(interval in 1..365) { "interval out of range: $interval" }
    }

    fun occurrence(start: LocalDate, n: Int): LocalDate {
        val steps = n.toLong() * interval
        return when (frequency) {
            Frequency.DAILY -> start.plusDays(steps)
            Frequency.WEEKLY -> start.plusWeeks(steps)
            Frequency.MONTHLY -> start.plusMonths(steps)
            Frequency.YEARLY -> start.plusYears(steps)
        }
    }

    /** Index of the first occurrence on or after [date] (0 when [date] is on or before [start]). */
    fun firstIndexOnOrAfter(start: LocalDate, date: LocalDate): Int {
        if (!date.isAfter(start)) return 0
        val unit = when (frequency) {
            Frequency.DAILY -> ChronoUnit.DAYS
            Frequency.WEEKLY -> ChronoUnit.WEEKS
            Frequency.MONTHLY -> ChronoUnit.MONTHS
            Frequency.YEARLY -> ChronoUnit.YEARS
        }
        var n = (unit.between(start, date) / interval).toInt().coerceAtLeast(0)
        while (occurrence(start, n).isBefore(date)) n++
        while (n > 0 && !occurrence(start, n - 1).isBefore(date)) n--
        return n
    }

    /** Average cost per month, for "₹X/month on subscriptions". */
    fun monthlyEquivalent(amountMinor: Long): Long = when (frequency) {
        Frequency.DAILY -> amountMinor * 365 / 12 / interval
        Frequency.WEEKLY -> amountMinor * 52 / 12 / interval
        Frequency.MONTHLY -> amountMinor / interval
        Frequency.YEARLY -> amountMinor / 12 / interval
    }

    fun toRRule(): String = "FREQ=${frequency.name};INTERVAL=$interval"

    companion object {
        fun parse(rrule: String): Recurrence? {
            val parts = rrule.split(';').mapNotNull { p -> p.split('=').takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
            val freq = parts["FREQ"]?.let { runCatching { Frequency.valueOf(it) }.getOrNull() } ?: return null
            val interval = parts["INTERVAL"]?.toIntOrNull() ?: 1
            return runCatching { Recurrence(freq, interval) }.getOrNull()
        }
    }
}
