package com.dailymoney.core.domain.sharing

/**
 * UPI payment requests (NPCI deep-link spec): `upi://pay?pa=…&pn=…&am=…&cu=INR&tn=…`. The same fields
 * go on the web pay page (dailymonyapp.web.app/pay) so a reminder link works for people without the app.
 */
object UpiPayment {

    private val UPI_ID = Regex("^[A-Za-z0-9._-]{2,64}@[A-Za-z][A-Za-z0-9.-]{1,32}$")

    fun isValidId(upiId: String?): Boolean = upiId != null && UPI_ID.matches(upiId.trim())

    /** Opens a UPI app with the payee and amount filled in; the payer still confirms with their PIN. */
    fun appUri(upiId: String, payeeName: String?, amountMinor: Long, note: String?): String =
        "upi://pay?" + query(upiId, payeeName, amountMinor, note)

    /** A normal https link (clickable in WhatsApp/SMS) to the pay page, which offers the same UPI hand-off. */
    fun webLink(pageUrl: String, upiId: String, payeeName: String?, amountMinor: Long, note: String?): String =
        pageUrl.trimEnd('/') + "?" + query(upiId, payeeName, amountMinor, note)

    /** UPI amounts are rupees with two decimals: 50000 paise → "500.00". */
    fun amount(minor: Long): String = "${minor / 100}.${(minor % 100).toString().padStart(2, '0')}"

    private fun query(upiId: String, payeeName: String?, amountMinor: Long, note: String?): String = buildList {
        add("pa" to upiId.trim())
        payeeName?.trim()?.takeIf { it.isNotEmpty() }?.let { add("pn" to it.take(40)) }
        add("am" to amount(amountMinor))
        add("cu" to "INR")
        note?.trim()?.takeIf { it.isNotEmpty() }?.let { add("tn" to it.take(40)) }
    }.joinToString("&") { (k, v) -> "$k=${encode(v)}" }

    /** RFC 3986 percent-encoding of everything except unreserved characters (and '@' for readability). */
    internal fun encode(value: String): String = buildString {
        for (b in value.encodeToByteArray()) {
            val c = (b.toInt() and 0xFF)
            val ch = c.toChar()
            if (ch.isLetterOrDigit() && c < 0x80 || ch in "-._~@") append(ch)
            else append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 0x0F])
        }
    }
}
