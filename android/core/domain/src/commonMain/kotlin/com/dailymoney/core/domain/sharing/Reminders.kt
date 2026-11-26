package com.dailymoney.core.domain.sharing

/** Phone numbers as people type them ("98765 43210", "098765-43210") → "+919876543210". */
object PhoneNumbers {

    /** Null when it doesn't look like a phone number. Ten-digit numbers are taken as Indian mobiles. */
    fun normalise(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val digits = text.filter(Char::isDigit)
        return when {
            text.startsWith("+") && digits.length in 8..15 -> "+$digits"
            digits.length == 10 -> "+91$digits"
            digits.length == 11 && digits.startsWith("0") -> "+91${digits.drop(1)}"
            digits.length == 12 && digits.startsWith("91") -> "+$digits"
            else -> null
        }
    }
}

/** The "you owe me" text sent from the user's own phone. Plain text: UPI links aren't clickable in chats. */
object ReminderText {

    /**
     * [about] says what it's for, e.g. "for \"Goa trip\"" or "(lent on 12 Nov 2026)". [payLink] is a
     * tappable https link to the pay page; the UPI ID is still written out for people who'd rather type it.
     */
    fun build(name: String, amount: String, about: String, myUpiId: String?, payLink: String? = null): String = buildString {
        append("Hi ${name.trim().substringBefore(' ')}, a friendly reminder: you owe me $amount $about.")
        if (!payLink.isNullOrBlank()) append("\nPay with any UPI app: $payLink")
        if (!myUpiId.isNullOrBlank()) append("\nMy UPI ID: $myUpiId")
        append("\nThanks!")
    }
}

enum class SmsSendResult { SENT, FAILED, NO_PERMISSION, NOT_SUPPORTED }

/** Sends an SMS from the phone's own SIM (only in the "personal" build, never the Play one). */
interface SmsSender {
    val supported: Boolean
    fun hasPermission(): Boolean
    suspend fun send(phone: String, text: String): SmsSendResult
}
