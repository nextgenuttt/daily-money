package com.dailymoney.core.domain.sharing

import com.dailymoney.core.domain.groups.GroupResult
import java.time.Instant
import java.time.LocalTime
import kotlinx.coroutines.flow.Flow

/** What happened; the phone builds the text from this plus [AppNotification.params]. */
enum class NotificationType(val category: NotificationCategory) {
    APPROVAL_REQUESTED(NotificationCategory.APPROVALS),
    EXPENSE_APPROVED(NotificationCategory.APPROVALS),
    EXPENSE_REJECTED(NotificationCategory.APPROVALS),
    EXPENSE_ADDED(NotificationCategory.GROUP_ACTIVITY),
    SPLIT_SHARE(NotificationCategory.PAYMENTS),
    SETTLEMENT_REQUESTED(NotificationCategory.PAYMENTS),
    SETTLEMENT_RECORDED(NotificationCategory.PAYMENTS),
    SETTLEMENT_CONFIRMED(NotificationCategory.PAYMENTS),
    SETTLEMENT_CANCELLED(NotificationCategory.PAYMENTS),
    PAYMENT_REMINDER(NotificationCategory.PAYMENTS),
    MESSAGE(NotificationCategory.MESSAGES),
}

/** User-facing switches, matching the Android channels (LLD §10). */
enum class NotificationCategory { APPROVALS, PAYMENTS, MESSAGES, GROUP_ACTIVITY }

data class AppNotification(
    val id: String,
    val groupId: String?,
    val type: NotificationType,
    val entityType: String?,
    val entityId: String?,
    /** Non-sensitive placeholders from the server: actor, group, amountMinor, currency, category, shareMinor, reason, preview. */
    val params: Map<String, String>,
    val createdAt: Instant,
    val read: Boolean,
)

data class QuietHours(val enabled: Boolean = false, val startHour: Int = 22, val endHour: Int = 7) {
    /** True inside the window; a window like 22→7 crosses midnight. */
    fun contains(time: LocalTime): Boolean {
        if (!enabled || startHour == endHour) return false
        val h = time.hour
        return if (startHour < endHour) h in startHour until endHour else h >= startHour || h < endHour
    }
}

/** Kept on this phone (each person can tune each device). Everything still lands in the notification centre. */
data class NotificationPrefs(
    val disabled: Set<NotificationCategory> = emptySet(),
    val mutedGroups: Set<String> = emptySet(),
    val quietHours: QuietHours = QuietHours(),
    /** An evening summary of unread updates, for people who silence the rest. */
    val dailyDigest: Boolean = false,
)

object NotificationRules {

    /** Whether to pop a system notification now; otherwise it only appears in the centre (and the digest). */
    fun shouldAlert(n: AppNotification, prefs: NotificationPrefs, now: LocalTime): Boolean =
        !n.read &&
            n.type.category !in prefs.disabled &&
            (n.groupId == null || n.groupId !in prefs.mutedGroups) &&
            !prefs.quietHours.contains(now)
}

interface NotificationRepository {
    fun observe(): Flow<List<AppNotification>>
    fun observeUnreadCount(): Flow<Int>
    suspend fun markRead(ids: List<String>)
    suspend fun markAllRead()
    fun observePrefs(): Flow<NotificationPrefs>
    suspend fun savePrefs(prefs: NotificationPrefs)
}

data class ExpenseMessage(
    val id: String,
    val senderName: String,
    val mine: Boolean,
    val parentId: String?,
    /** Null once the sender deleted it. */
    val body: String?,
    val createdAt: Instant,
    val readBy: Int,
)

data class MessageThread(val canSend: Boolean, val canReply: Boolean, val messages: List<ExpenseMessage>)

/** Discussions are loaded live (they need the other people), with results as [GroupResult]. */
interface MessageRepository {
    suspend fun thread(expenseId: String): GroupResult<MessageThread>
    suspend fun send(expenseId: String, body: String, parentId: String?): GroupResult<Unit>
    suspend fun markRead(expenseId: String): GroupResult<Unit>
    suspend fun delete(messageId: String): GroupResult<Unit>
}
