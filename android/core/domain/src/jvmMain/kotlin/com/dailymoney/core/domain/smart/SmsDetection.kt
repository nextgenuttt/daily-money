package com.dailymoney.core.domain.smart

import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow

enum class SuggestionStatus { NEW, ADDED, DISMISSED }

/** A payment found in a bank SMS, waiting for the user to add or dismiss it. Only the parsed fields are kept, never the message. */
data class PaymentSuggestion(
    val id: String,
    /** Bank sender ID, e.g. "AX-HDFCBK". */
    val sender: String,
    val payment: MessageTransaction,
    val receivedAt: Instant,
    val status: SuggestionStatus = SuggestionStatus.NEW,
)

interface PaymentSuggestionRepository {
    fun observePending(): Flow<List<PaymentSuggestion>>
    fun observePendingCount(): Flow<Int>
    suspend fun get(id: String): PaymentSuggestion?

    /** Stores the ones not seen before (same message again is ignored); returns those that were new. */
    suspend fun addAll(suggestions: List<PaymentSuggestion>): List<PaymentSuggestion>
    suspend fun setStatus(id: String, status: SuggestionStatus)
    suspend fun dismissAll()

    fun observeEnabled(): Flow<Boolean>
    suspend fun isEnabled(): Boolean
    suspend fun setEnabled(enabled: Boolean)
}

/** Reading the phone's SMS (only in the build that declares the SMS permission). */
interface SmsInbox {
    /** This build can read SMS at all (the Play-reviewed variant). */
    val supported: Boolean
    fun hasPermission(): Boolean

    /** Looks at the last [days] of messages; returns how many new payments were found. */
    suspend fun scan(days: Int): Int

    /**
     * When the reading rules improved since the last look, re-check the waiting list against them: pending
     * suggestions are dropped and the last 30 days scanned again (added or dismissed ones are untouched).
     */
    suspend fun refreshIfRulesChanged()

    /** Test builds: pretend a bank SMS just arrived (no real message is read or sent). */
    suspend fun simulateIncoming(): Boolean
}

/**
 * Decides which SMS are bank payments. Only senders that are business IDs (DLT headers like
 * "AX-HDFCBK", never a phone number) are read at all, so messages from people are skipped unread.
 */
object SmsIntake {

    private val PHONE_NUMBER = Regex("""^\+?[0-9 ()-]{6,}$""")

    /** Offers, loan pitches and future credits use payment words but aren't payments. */
    private val PROMOTION = Regex(
        """\b(pre-?approved|offer|apply now|eligible|win|congratulations|will be credited|loan of|limit of|click|download)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** Real alerts name an account, card, UPI or reference; promotions rarely do. */
    private val ACCOUNT_MARK = Regex("""\b(a/c|acct|account|card|upi|ref|txn|imps|neft|vpa)\b""", RegexOption.IGNORE_CASE)

    fun isBankSender(address: String?): Boolean {
        val a = address?.trim().orEmpty()
        return a.isNotEmpty() && a.any(Char::isLetter) && !PHONE_NUMBER.matches(a)
    }

    fun suggestion(sender: String?, body: String, receivedAt: Instant, currency: String, zone: ZoneId): PaymentSuggestion? {
        if (!isBankSender(sender) || PROMOTION.containsMatchIn(body) || !ACCOUNT_MARK.containsMatchIn(body)) return null
        val today = receivedAt.atZone(zone).toLocalDate()
        val parsed = BankSmsParser.parse(body, currency, today) ?: return null
        val payment = if (parsed.date == null) parsed.copy(date = today) else parsed
        return PaymentSuggestion(id(sender!!.trim(), body, receivedAt), sender.trim(), payment, receivedAt)
    }

    /** The same message delivered or scanned twice gets the same id. */
    private fun id(sender: String, body: String, receivedAt: Instant): String =
        MessageDigest.getInstance("SHA-256").digest("$sender|${receivedAt.epochSecond / 60}|$body".toByteArray())
            .take(16).joinToString("") { "%02x".format(it) }
}
