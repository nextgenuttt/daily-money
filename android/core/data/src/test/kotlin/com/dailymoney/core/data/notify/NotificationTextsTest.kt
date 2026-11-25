package com.dailymoney.core.data.notify

import androidx.test.core.app.ApplicationProvider
import com.dailymoney.core.domain.sharing.AppNotification
import com.dailymoney.core.domain.sharing.NotificationType
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationTextsTest {

    private fun n(type: NotificationType, vararg params: Pair<String, String>) =
        AppNotification("n", "g", type, "TRANSACTION", "e", mapOf(*params), Instant.EPOCH, read = false)

    @Test
    fun `text is built on the phone from the placeholders`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val share = NotificationTexts.of(context, n(NotificationType.SPLIT_SHARE, "actor" to "Asha", "group" to "Trip", "category" to "Fuel", "shareMinor" to "75000", "currency" to "INR"))
        assertEquals("Your share in Trip", share.title)
        assertEquals(true, share.body.contains("Fuel") && share.body.contains("Asha") && share.body.contains("750"))

        val rejected = NotificationTexts.of(context, n(NotificationType.EXPENSE_REJECTED, "amountMinor" to "1000", "reason" to "No bill"))
        assertEquals(true, rejected.body.contains("an expense") && rejected.body.endsWith("No bill"))

        val message = NotificationTexts.of(context, n(NotificationType.MESSAGE, "actor" to "Ravi", "category" to "Rent", "preview" to "Paid?"))
        assertEquals("Ravi on Rent", message.title)
        assertEquals("Paid?", message.body)
    }
}
