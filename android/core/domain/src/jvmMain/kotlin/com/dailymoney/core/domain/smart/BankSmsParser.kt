package com.dailymoney.core.domain.smart

import com.dailymoney.core.domain.money.MoneyParser
import com.dailymoney.core.domain.money.TransactionType
import java.time.LocalDate

/** A payment read from a bank or UPI message the user shared with the app. */
data class MessageTransaction(
    val type: TransactionType,
    val amountMinor: Long,
    /** Who was paid, or who paid me, when the message says. */
    val counterparty: String?,
    val date: LocalDate?,
    /** Last digits of the account or card, e.g. "1234". */
    val accountHint: String?,
)

/**
 * Reads Indian bank / UPI transaction alerts. Nothing reads the inbox: the user shares or pastes a
 * message, so the app needs no SMS permission (Play's SMS policy, LLD §15 R4).
 */
object BankSmsParser {

    private val AMOUNT = Regex("""(?:rs\.?|inr|₹)\s*([\d,]+(?:\.\d{1,2})?)""", RegexOption.IGNORE_CASE)
    /**
     * Only real money-movement verbs count. Bare "credit"/"debit" are nouns far too often ("Credit Card",
     * "credit limit"), so they only count as "debit of/for" or "credit of/to/in", or as Dr/Cr right after
     * the amount ("Rs 500.00 Dr").
     */
    private val DEBIT = Regex(
        """\b(debited|spent|paid|sent|withdrawn|transferred|purchased?|debit\s+(?:of|for)|deducted)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val CREDIT = Regex(
        """\b(credited|received|deposited|refund(?:ed)?|reversed|credit\s+(?:of|to|in))\b""",
        RegexOption.IGNORE_CASE,
    )
    private val AMOUNT_MARK = Regex("""(?:rs\.?|inr|₹)\s*[\d,]+(?:\.\d{1,2})?\s*(dr|cr)\b""", RegexOption.IGNORE_CASE)

    /** OTPs, payment requests, reminders, future debits and offers mention amounts but aren't payments. */
    private val NOT_A_PAYMENT = Regex(
        """\b(otp|one time password|verification code|requested|request|will be debited|will be credited|is due|due on|""" +
            """due date|reminder|min(imum)? amt|minimum amount|failed|declined|""" +
            """increase (?:your )?(?:credit )?limit|limit increase|enhance|spending power|for free|enjoy|upgrade|avail|""" +
            """pre-?approved|eligible|congratulations|offer|apply now|instant loan|loan of|earn|reward points?|cashback (?:of )?up to|""" +
            """t&c|tnc|win)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val ACCOUNT = Regex("""\b(?:a/c|ac|acct|account|card)\s*(?:no\.?)?\s*(?:ending\s*)?[x*.]*\s*(\d{3,4})\b""", RegexOption.IGNORE_CASE)
    private const val NAME = """\s+([A-Za-z0-9@._&' -]{2,60}?)(?=\s+(?:on|ref|via|from|to|avl|avbl|bal|if|using|upi|thru|not)\b|[.,;(\n]|$)"""

    /** Tried in order: "to VPA x" beats "at x"; "from x" beats "by NEFT". */
    private val PAYEE = listOf("to vpa", "to", "at", "towards", "info:?").map { Regex("""\b$it$NAME""", RegexOption.IGNORE_CASE) }
    private val PAYER = listOf("from vpa", "from", "by").map { Regex("""\b$it$NAME""", RegexOption.IGNORE_CASE) }

    fun parse(text: String, currency: String, today: LocalDate): MessageTransaction? {
        if (NOT_A_PAYMENT.containsMatchIn(text)) return null
        val amount = AMOUNT.find(text)?.groupValues?.get(1)?.let { MoneyParser.parse(it, currency) }?.takeIf { it > 0 } ?: return null
        // Whichever comes first decides: "debited from my a/c ... credited to VPA x" is a debit.
        val mark = AMOUNT_MARK.find(text)
        val markIsDebit = mark?.groupValues?.get(1)?.equals("dr", ignoreCase = true)
        val debit = listOfNotNull(DEBIT.find(text)?.range?.first, mark?.range?.first?.takeIf { markIsDebit == true }).minOrNull()
        val credit = listOfNotNull(CREDIT.find(text)?.range?.first, mark?.range?.first?.takeIf { markIsDebit == false }).minOrNull()
        val type = when {
            debit == null && credit == null -> return null
            credit == null -> TransactionType.EXPENSE
            debit == null -> TransactionType.INCOME
            debit < credit -> TransactionType.EXPENSE
            else -> TransactionType.INCOME
        }
        val party = (if (type == TransactionType.EXPENSE) PAYEE else PAYER).asSequence()
            .flatMap { it.findAll(text) }.mapNotNull { tidy(it.groupValues[1]) }.firstOrNull { !isAccountWords(it) && !isChannel(it) }
        return MessageTransaction(type, amount, party, TextScan.firstDate(text, today), ACCOUNT.find(text)?.groupValues?.get(1))
    }

    private fun isChannel(s: String) = Regex("""^(neft|imps|rtgs|upi|ach|nach)$""", RegexOption.IGNORE_CASE).matches(s)

    private fun isAccountWords(s: String) = Regex("""^(your|my|a/?c|ac|acct|account|card|bank)\b""", RegexOption.IGNORE_CASE).containsMatchIn(s)

    /** "swiggy@icici" → "swiggy"; drops masked numbers. */
    private fun tidy(raw: String): String? {
        val name = raw.substringBefore('@').replace(Regex("""[xX*]{2,}\d+"""), "").replace(Regex("\\s+"), " ").trim(' ', '.', '-', '\'')
        return name.takeIf { it.count(Char::isLetter) >= 2 }?.take(40)
    }
}
