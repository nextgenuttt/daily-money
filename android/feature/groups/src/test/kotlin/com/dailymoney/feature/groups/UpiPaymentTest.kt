package com.dailymoney.feature.groups

import com.dailymoney.core.domain.sharing.UpiPayment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpiPaymentTest {

    @Test
    fun `app link carries payee, amount and note encoded`() {
        assertEquals(
            "upi://pay?pa=ravi.k@okaxis&pn=Ravi%20Kumar&am=500.00&cu=INR&tn=Goa%20trip%20%26%20food",
            UpiPayment.appUri("ravi.k@okaxis", "Ravi Kumar", 50000, "Goa trip & food"),
        )
    }

    @Test
    fun `web link is a normal https link with the same fields`() {
        assertEquals(
            "https://dailymonyapp.web.app/pay?pa=ravi@ybl&am=40.05&cu=INR",
            UpiPayment.webLink("https://dailymonyapp.web.app/pay/", "ravi@ybl", null, 4005, " "),
        )
    }

    @Test
    fun `upi ids are checked`() {
        assertTrue(UpiPayment.isValidId("ravi.k@okaxis"))
        assertFalse(UpiPayment.isValidId("ravi"))
        assertFalse(UpiPayment.isValidId("ravi@"))
    }
}
