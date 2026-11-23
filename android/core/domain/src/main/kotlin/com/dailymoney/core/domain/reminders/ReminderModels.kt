package com.dailymoney.core.domain.reminders

import com.dailymoney.core.domain.money.Recurrence
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlinx.coroutines.flow.Flow

/** What the reminder is about. Drives icons, the completion question and whether "overdue" applies. */
enum class ReminderKind(val group: ReminderGroup) {
    BILL(ReminderGroup.PAYMENT),
    EMI(ReminderGroup.PAYMENT),
    CREDIT_CARD(ReminderGroup.PAYMENT),
    INSURANCE(ReminderGroup.PAYMENT),
    RECHARGE(ReminderGroup.PAYMENT),
    COLLECTION(ReminderGroup.COLLECTION),
    BIRTHDAY(ReminderGroup.CELEBRATION),
    ANNIVERSARY(ReminderGroup.CELEBRATION),
    FESTIVAL(ReminderGroup.CELEBRATION),
    APPOINTMENT(ReminderGroup.APPOINTMENT),
    MEETING(ReminderGroup.EVENT),
    TRAVEL(ReminderGroup.EVENT),
    EXAM(ReminderGroup.EVENT),
    CUSTOM(ReminderGroup.TASK);

    val hasAmount: Boolean get() = group == ReminderGroup.PAYMENT || group == ReminderGroup.COLLECTION

    /** Events can have an end time, a location and preparation steps. */
    val isEvent: Boolean get() = group == ReminderGroup.CELEBRATION || group == ReminderGroup.EVENT || group == ReminderGroup.APPOINTMENT

    /** Missed payments, collections, appointments and tasks show as overdue; past birthdays and meetings just become history. */
    val tracksOverdue: Boolean get() = group == ReminderGroup.PAYMENT || group == ReminderGroup.COLLECTION ||
        group == ReminderGroup.APPOINTMENT || group == ReminderGroup.TASK
}

enum class ReminderGroup { PAYMENT, COLLECTION, CELEBRATION, APPOINTMENT, EVENT, TASK }

data class PrepStep(val id: String, val title: String, val daysBefore: Int)

data class Reminder(
    val id: String,
    val kind: ReminderKind,
    val title: String,
    val notes: String?,
    val amountMinor: Long?,
    val currency: String,
    val categoryId: String?,
    val accountId: String?,
    val startDate: LocalDate,
    /** Null means all day (alerts fire at [ReminderMath.ALL_DAY_ALERT_TIME]). */
    val time: LocalTime?,
    val endTime: LocalTime?,
    val location: String?,
    val recurrence: Recurrence?,
    /** Minutes before the occurrence; 0 = at the time. */
    val alertOffsets: List<Int>,
    val steps: List<PrepStep>,
    val active: Boolean = true,
)

enum class OccurrenceAction { DONE, SKIPPED }

/** What the user did with one occurrence, keyed by its original date. Missing record = nothing done yet. */
data class OccurrenceRecord(
    val reminderId: String,
    val originalDate: LocalDate,
    val action: OccurrenceAction? = null,
    val completedAt: Instant? = null,
    val snoozedUntil: Instant? = null,
    val rescheduledTo: LocalDateTime? = null,
    val paidAmountMinor: Long? = null,
    val transactionId: String? = null,
    val doneStepIds: Set<String> = emptySet(),
)

enum class OccurrenceStatus { UPCOMING, DUE_TODAY, OVERDUE, DONE, SKIPPED, PAST }

data class Occurrence(
    val reminder: Reminder,
    val originalDate: LocalDate,
    /** Effective date-time (after any reschedule). For all-day items the time is the all-day alert time. */
    val at: LocalDateTime,
    val status: OccurrenceStatus,
    val record: OccurrenceRecord?,
) {
    val date: LocalDate get() = at.toLocalDate()
    val isAllDay: Boolean get() = reminder.time == null && record?.rescheduledTo == null
}

interface ReminderRepository {
    fun observe(): Flow<List<Reminder>>
    fun observeRecords(): Flow<List<OccurrenceRecord>>
    suspend fun all(): List<Reminder>
    suspend fun allRecords(): List<OccurrenceRecord>
    suspend fun get(id: String): Reminder?
    suspend fun record(reminderId: String, originalDate: LocalDate): OccurrenceRecord?
    suspend fun save(reminder: Reminder)
    suspend fun delete(id: String)
    suspend fun saveRecord(record: OccurrenceRecord)
    suspend fun deleteRecord(reminderId: String, originalDate: LocalDate)
}

/** Re-plans all local alarms; called after any change that could move one. */
interface ReminderScheduler {
    suspend fun reschedule()
}
