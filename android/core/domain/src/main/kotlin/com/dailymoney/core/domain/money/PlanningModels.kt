package com.dailymoney.core.domain.money

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.flow.Flow

/**
 * One engine for recurring transactions and subscriptions. A subscription is a named rule that may
 * only be tracked ([autoPost] = false) instead of adding an expense on each renewal.
 */
data class RecurringRule(
    val id: String,
    val name: String,
    val isSubscription: Boolean,
    val type: TransactionType,
    val amountMinor: Long,
    val currency: String,
    val categoryId: String?,
    val accountId: String,
    val toAccountId: String?,
    val note: String?,
    val paymentMethod: PaymentMethod?,
    val recurrence: Recurrence,
    val startDate: LocalDate,
    /** How many occurrences have already become transactions; the next one is index [postedCount]. */
    val postedCount: Int,
    val autoPost: Boolean,
    val active: Boolean,
) {
    val nextPostDate: LocalDate get() = recurrence.occurrence(startDate, postedCount)

    /** Next renewal shown to the user: for tracked-only rules this is simply the next date from today. */
    fun nextDueDate(today: LocalDate): LocalDate =
        if (autoPost) nextPostDate else recurrence.occurrence(startDate, recurrence.firstIndexOnOrAfter(startDate, today))
}

data class SavingsGoal(
    val id: String,
    val name: String,
    val targetMinor: Long,
    val currency: String,
    val targetDate: LocalDate?,
    val colorIndex: Int,
)

data class GoalContribution(
    val id: String,
    val goalId: String,
    /** Positive adds money to the goal, negative takes it out. */
    val amountMinor: Long,
    val date: LocalDate,
    val note: String?,
)

data class GoalProgress(val goal: SavingsGoal, val contributions: List<GoalContribution>) {
    val savedMinor: Long get() = contributions.sumOf { it.amountMinor }
    val remainingMinor: Long get() = (goal.targetMinor - savedMinor).coerceAtLeast(0)
    val fraction: Float get() = if (goal.targetMinor <= 0) 0f else (savedMinor.toFloat() / goal.targetMinor).coerceIn(0f, 1f)
    val reached: Boolean get() = savedMinor >= goal.targetMinor

    /** Amount to put aside each month (counting this month) to reach the target on time; null without a date. */
    fun monthlyNeeded(today: LocalDate): Long? {
        val target = goal.targetDate ?: return null
        if (reached) return 0
        val months = ChronoUnit.MONTHS.between(today.withDayOfMonth(1), target.withDayOfMonth(1)) + 1
        return if (months <= 0) remainingMinor else (remainingMinor + months - 1) / months
    }
}

enum class DebtDirection { LENT, BORROWED }

data class Debt(
    val id: String,
    val person: String,
    val direction: DebtDirection,
    val principalMinor: Long,
    val currency: String,
    val startDate: LocalDate,
    val dueDate: LocalDate?,
    val note: String?,
)

data class DebtRepayment(val id: String, val debtId: String, val amountMinor: Long, val date: LocalDate, val note: String?)

data class DebtStatus(val debt: Debt, val repayments: List<DebtRepayment>) {
    val repaidMinor: Long get() = repayments.sumOf { it.amountMinor }
    val outstandingMinor: Long get() = (debt.principalMinor - repaidMinor).coerceAtLeast(0)
    val settled: Boolean get() = outstandingMinor == 0L
    fun overdue(today: LocalDate): Boolean = !settled && debt.dueDate?.isBefore(today) == true
}

data class DebtSummary(val toReceiveMinor: Long, val toPayMinor: Long)

data class Attachment(
    val id: String,
    val entityId: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
) {
    val isImage: Boolean get() = mimeType.startsWith("image/")
}

interface RecurringRepository {
    fun observe(): Flow<List<RecurringRule>>
    suspend fun all(): List<RecurringRule>
    suspend fun save(rule: RecurringRule)
    suspend fun delete(id: String)
}

interface GoalRepository {
    fun observe(): Flow<List<GoalProgress>>
    suspend fun save(goal: SavingsGoal)
    suspend fun delete(id: String)
    suspend fun addContribution(contribution: GoalContribution)
    suspend fun deleteContribution(id: String)
}

interface DebtRepository {
    fun observe(): Flow<List<DebtStatus>>
    suspend fun save(debt: Debt)
    suspend fun delete(id: String)
    suspend fun addRepayment(repayment: DebtRepayment)
    suspend fun deleteRepayment(id: String)
}

sealed interface AttachmentResult {
    data class Added(val attachment: Attachment) : AttachmentResult
    data object TooLarge : AttachmentResult
    data object Unsupported : AttachmentResult
    data object Failed : AttachmentResult
}

/** Receipts stored privately on the phone; the app only ever reads files it copied itself. */
interface AttachmentRepository {
    fun observeFor(entityId: String): Flow<List<Attachment>>

    /** Copies (and for photos, shrinks) the picked file. [sourceUri] is a content:// URI string. */
    suspend fun import(entityId: String, sourceUri: String): AttachmentResult
    suspend fun delete(id: String)

    /** Removes attachments of a transaction that was never saved, or was deleted. */
    suspend fun deleteFor(entityId: String)

    /** A content:// URI another app (viewer) can open, valid while permission is granted. */
    fun shareableUri(attachment: Attachment): String
    fun filePath(attachment: Attachment): String
}

interface BackgroundJobs {
    fun scheduleRecurring()
}

/** Writes an export to a user-chosen document (content:// URI string). */
interface ExportService {
    suspend fun write(targetUri: String, format: ExportFormat, data: ExportData): Boolean
}
