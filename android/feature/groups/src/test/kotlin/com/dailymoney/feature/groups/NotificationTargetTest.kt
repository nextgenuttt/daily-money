package com.dailymoney.feature.groups

import com.dailymoney.core.domain.sharing.AppNotification
import com.dailymoney.core.domain.sharing.NotificationType
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationTargetTest {

    private fun n(type: NotificationType, group: String?, entity: String?) =
        AppNotification("n", group, type, "TRANSACTION", entity, mapOf("category" to "Rent"), Instant.EPOCH, read = false)

    @Test
    fun `messages open the thread, everything else the group`() {
        assertEquals(NotificationTarget.Messages("e1", "Rent"), n(NotificationType.MESSAGE, "g1", "e1").target())
        assertEquals(NotificationTarget.Group("g1"), n(NotificationType.APPROVAL_REQUESTED, "g1", "e1").target())
        assertEquals(null, n(NotificationType.EXPENSE_ADDED, null, null).target())
    }
}
