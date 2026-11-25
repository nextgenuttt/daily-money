package com.dailymoney.core.domain.sharing

import java.time.Instant
import java.time.LocalTime
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationRulesTest {

    private fun n(type: NotificationType = NotificationType.SPLIT_SHARE, group: String? = "g1", read: Boolean = false) =
        AppNotification("n", group, type, "TRANSACTION", "t", emptyMap(), Instant.EPOCH, read)

    private val noon = LocalTime.NOON

    @Test
    fun `quiet hours can cross midnight`() {
        val q = QuietHours(enabled = true, startHour = 22, endHour = 7)
        assertTrue(q.contains(LocalTime.of(23, 30)))
        assertTrue(q.contains(LocalTime.of(6, 59)))
        assertFalse(q.contains(LocalTime.of(7, 0)))
        assertFalse(q.contains(LocalTime.of(21, 59)))
        assertTrue(QuietHours(enabled = true, startHour = 13, endHour = 15).contains(LocalTime.of(14, 0)))
        assertFalse(QuietHours(enabled = false).contains(LocalTime.of(23, 0)))
    }

    @Test
    fun `alerts respect categories, muted groups, quiet hours and read state`() {
        assertTrue(NotificationRules.shouldAlert(n(), NotificationPrefs(), noon))
        assertFalse(NotificationRules.shouldAlert(n(read = true), NotificationPrefs(), noon))
        assertFalse(NotificationRules.shouldAlert(n(), NotificationPrefs(disabled = setOf(NotificationCategory.PAYMENTS)), noon))
        assertTrue(NotificationRules.shouldAlert(n(NotificationType.MESSAGE), NotificationPrefs(disabled = setOf(NotificationCategory.PAYMENTS)), noon))
        assertFalse(NotificationRules.shouldAlert(n(), NotificationPrefs(mutedGroups = setOf("g1")), noon))
        assertFalse(NotificationRules.shouldAlert(n(), NotificationPrefs(quietHours = QuietHours(true, 10, 14)), noon))
    }
}
