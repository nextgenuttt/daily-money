package com.dailymoney.core.domain.sharing

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

enum class SplitMethod { EQUAL, PERCENT, AMOUNT, SHARES }

/** One person in a split and what was entered for them (percent, share count or amount in minor units). */
data class SplitEntry(val userId: String, val value: BigDecimal = BigDecimal.ONE)

/** How the creator wants an expense shared; the server recalculates and stores the shares. */
data class SplitSpec(val method: SplitMethod, val entries: List<SplitEntry>)

enum class SplitProblem { NOBODY, TOO_MANY, DUPLICATE, NOT_POSITIVE, PERCENT_NOT_100, AMOUNT_NOT_TOTAL, AMOUNT_NOT_WHOLE }

sealed interface SplitResult {
    data class Ok(val shares: List<Long>) : SplitResult
    data class Invalid(val problem: SplitProblem) : SplitResult
}

/**
 * Largest-remainder split (LLD §8.1), the same rules as the server's SplitCalculator: shares always add
 * up to the total; leftover minor units go to the largest fractions, ties to the lowest user id.
 * docs/split-test-vectors.json is run against both.
 */
object SplitCalculator {

    const val MAX_PEOPLE = 50
    private val HUNDRED = BigDecimal(100)

    fun split(totalMinor: Long, method: SplitMethod, entries: List<SplitEntry>): SplitResult {
        if (entries.isEmpty()) return SplitResult.Invalid(SplitProblem.NOBODY)
        if (entries.size > MAX_PEOPLE) return SplitResult.Invalid(SplitProblem.TOO_MANY)
        if (entries.map { it.userId }.toSet().size != entries.size) return SplitResult.Invalid(SplitProblem.DUPLICATE)
        if (method != SplitMethod.EQUAL && entries.any { it.value.signum() <= 0 }) return SplitResult.Invalid(SplitProblem.NOT_POSITIVE)
        if (method == SplitMethod.AMOUNT) {
            val amounts = entries.map { runCatching { it.value.longValueExact() }.getOrNull() ?: return SplitResult.Invalid(SplitProblem.AMOUNT_NOT_WHOLE) }
            return if (amounts.sum() == totalMinor) SplitResult.Ok(amounts) else SplitResult.Invalid(SplitProblem.AMOUNT_NOT_TOTAL)
        }
        val weights = entries.map { if (method == SplitMethod.EQUAL) BigDecimal.ONE else it.value }
        val sum = weights.fold(BigDecimal.ZERO, BigDecimal::add)
        if (method == SplitMethod.PERCENT && sum.compareTo(HUNDRED) != 0) return SplitResult.Invalid(SplitProblem.PERCENT_NOT_100)
        val total = BigDecimal.valueOf(totalMinor)
        val exact = weights.map { total.multiply(it).divide(sum, 10, RoundingMode.DOWN) }
        val result = exact.map { it.setScale(0, RoundingMode.DOWN).longValueExact() }.toMutableList()
        var remainder = totalMinor - result.sum()
        val order = entries.indices.sortedWith(
            compareByDescending<Int> { exact[it] - BigDecimal.valueOf(result[it]) }.thenBy { entries[it].userId },
        )
        for (i in order) {
            if (remainder == 0L) break
            result[i]++
            remainder--
        }
        return SplitResult.Ok(result)
    }
}

/** Someone's part of a group expense, as stored by the server. */
data class Share(val userId: String, val shareMinor: Long)

/** [phone] is E.164 (+919876543210) when that member added one, for WhatsApp/SMS reminders. */
data class GroupPerson(val userId: String, val name: String, val upiId: String?, val isMe: Boolean, val phone: String? = null)

enum class SettleMethod { CASH, UPI, BANK, OTHER }

enum class SettlementStatus { REQUESTED, CONFIRMED, CANCELLED }

data class Settlement(
    val id: String,
    val groupId: String,
    val payerId: String,
    val receiverId: String,
    val amountMinor: Long,
    val currency: String,
    val method: SettleMethod,
    val status: SettlementStatus,
    val note: String?,
    val createdBy: String,
    val createdAt: Instant,
)

/** Positive: they owe me. Negative: I owe them. */
data class PersonBalance(val userId: String, val netMinor: Long)

object Balances {

    /**
     * My balance with each person (LLD §8.2), from split expenses I can see and confirmed settlements.
     * Only split expenses count; shared-for-reporting ones have no shares and no effect.
     */
    fun compute(meId: String, expenses: List<SharedExpense>, settlements: List<Settlement>, currency: String): List<PersonBalance> {
        val net = mutableMapOf<String, Long>()
        fun add(user: String, delta: Long) = net.merge(user, delta, Long::plus)
        // Only approved expenses create debts (spec §14).
        expenses.filter { it.currency == currency && it.shares.isNotEmpty() && it.approval == ApprovalStatus.APPROVED }.forEach { e ->
            if (e.creatorId == meId) {
                e.shares.filter { it.userId != meId }.forEach { add(it.userId, it.shareMinor) }
            } else {
                e.shares.firstOrNull { it.userId == meId }?.let { add(e.creatorId, -it.shareMinor) }
            }
        }
        settlements.filter { it.status == SettlementStatus.CONFIRMED && it.currency == currency }.forEach { s ->
            when (meId) {
                s.payerId -> add(s.receiverId, s.amountMinor)
                s.receiverId -> add(s.payerId, -s.amountMinor)
            }
        }
        return net.filter { it.value != 0L }.map { PersonBalance(it.key, it.value) }.sortedBy { it.netMinor }
    }
}
