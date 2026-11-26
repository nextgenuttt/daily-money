package com.dailymoney.feature.groups

import com.dailymoney.core.domain.sharing.PhoneNumbers
import com.dailymoney.core.domain.sharing.ReminderText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ReminderMessageTest {

    @Test
    fun `message uses the first name and includes my upi id when set`() {
        assertEquals(
            "Hi Asha, a friendly reminder: you owe me ₹750.00 for \"Goa trip\".\nPay with any UPI app: https://x/pay?pa=ravi@okaxis\nMy UPI ID: ravi@okaxis\nThanks!",
            ReminderText.build("Asha Kumar", "₹750.00", "for \"Goa trip\"", "ravi@okaxis", "https://x/pay?pa=ravi@okaxis"),
        )
    }

    @Test
    fun `no upi line without an upi id`() {
        assertFalse(ReminderText.build("Asha", "₹10.00", "(lent on 1 Nov 2026)", null).contains("UPI"))
    }

    @Test
    fun `phone numbers are normalised the way people type them`() {
        assertEquals("+919876543210", PhoneNumbers.normalise("98765 43210"))
        assertEquals("+919876543210", PhoneNumbers.normalise("098765-43210"))
        assertEquals("+919876543210", PhoneNumbers.normalise("+91 98765 43210"))
        assertEquals("+919876543210", PhoneNumbers.normalise("919876543210"))
        assertEquals("+14155550123", PhoneNumbers.normalise("+1 415 555 0123"))
        assertNull(PhoneNumbers.normalise("12345"))
        assertNull(PhoneNumbers.normalise(""))
    }
}
