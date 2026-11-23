package com.dailymoney.core.domain.reminders

import com.dailymoney.core.domain.money.Category
import com.dailymoney.core.domain.money.CategoryRepository
import com.dailymoney.core.domain.money.DeleteTransactionUseCase
import com.dailymoney.core.domain.money.MoneyParser
import com.dailymoney.core.domain.money.SaveResult
import com.dailymoney.core.domain.money.SaveTransactionUseCase
import com.dailymoney.core.domain.money.TransactionDraft
import com.dailymoney.core.domain.money.TransactionType
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

enum class ReminderError { TITLE_REQUIRED, TITLE_TOO_LONG, AMOUNT_INVALID, END_BEFORE_START, STEP_INVALID }

class SaveReminderUseCase @Inject constructor(
    private val repository: ReminderRepository,
    private val scheduler: ReminderScheduler,
) {
    fun validate(r: Reminder, amountText: String): Set<ReminderError> = buildSet {
        when {
            r.title.isBlank() -> add(ReminderError.TITLE_REQUIRED)
            r.title.trim().length > 60 -> add(ReminderError.TITLE_TOO_LONG)
        }
        if (amountText.isNotBlank() && (MoneyParser.parse(amountText, r.currency) ?: 0) <= 0) add(ReminderError.AMOUNT_INVALID)
        if (r.time != null && r.endTime != null && !r.endTime.isAfter(r.time)) add(ReminderError.END_BEFORE_START)
        if (r.steps.any { it.title.isBlank() || it.daysBefore !in 0..60 }) add(ReminderError.STEP_INVALID)
    }

    suspend operator fun invoke(r: Reminder, amountText: String): Set<ReminderError> {
        val errors = validate(r, amountText)
        if (errors.isNotEmpty()) return errors
        repository.save(
            r.copy(
                title = r.title.trim(),
                notes = r.notes?.trim()?.ifEmpty { null },
                location = r.location?.trim()?.ifEmpty { null },
                amountMinor = if (r.kind.hasAmount) MoneyParser.parse(amountText, r.currency) else null,
                endTime = if (r.kind.isEvent) r.endTime else null,
                steps = if (r.kind.isEvent) r.steps.map { it.copy(title = it.title.trim()) } else emptyList(),
            ),
        )
        scheduler.reschedule()
        return emptySet()
    }
}

class DeleteReminderUseCase @Inject constructor(
    private val repository: ReminderRepository,
    private val scheduler: ReminderScheduler,
) {
    suspend operator fun invoke(id: String) {
        repository.delete(id)
        scheduler.reschedule()
    }
}

/** Optional "add it to my money" part of completing a payment or collection reminder. */
data class PaymentEntry(val amountText: String, val date: LocalDate, val accountId: String, val categoryId: String?)

sealed interface CompleteResult {
    data object Done : CompleteResult
    data object AmountInvalid : CompleteResult
}

/**
 * "Have you paid?" → yes. Marks this occurrence done and, if asked, adds the expense (or income for
 * money collected) so the reminder and the books stay in step (spec §7).
 */
class CompleteOccurrenceUseCase @Inject constructor(
    private val repository: ReminderRepository,
    private val saveTransaction: SaveTransactionUseCase,
    private val scheduler: ReminderScheduler,
    private val clock: Clock,
) {
    suspend operator fun invoke(reminder: Reminder, originalDate: LocalDate, entry: PaymentEntry? = null): CompleteResult {
        var transactionId: String? = null
        var paid: Long? = null
        if (entry != null) {
            paid = MoneyParser.parse(entry.amountText, reminder.currency)?.takeIf { it > 0 } ?: return CompleteResult.AmountInvalid
            val draft = TransactionDraft(
                id = null,
                type = if (reminder.kind.group == ReminderGroup.COLLECTION) TransactionType.INCOME else TransactionType.EXPENSE,
                amountText = entry.amountText,
                currency = reminder.currency,
                categoryId = entry.categoryId,
                accountId = entry.accountId,
                toAccountId = null,
                date = entry.date,
                note = reminder.title,
                paymentMethod = null,
            )
            when (val result = saveTransaction(draft)) {
                is SaveResult.Saved -> transactionId = result.transaction.id
                is SaveResult.Invalid -> return CompleteResult.AmountInvalid
            }
        }
        val existing = repository.record(reminder.id, originalDate)
        repository.saveRecord(
            (existing ?: OccurrenceRecord(reminder.id, originalDate)).copy(
                action = OccurrenceAction.DONE,
                completedAt = clock.instant(),
                snoozedUntil = null,
                paidAmountMinor = paid,
                transactionId = transactionId,
            ),
        )
        scheduler.reschedule()
        return CompleteResult.Done
    }
}

/** Undo for "done" / "skipped": also removes an expense that completing created. */
class ReopenOccurrenceUseCase @Inject constructor(
    private val repository: ReminderRepository,
    private val deleteTransaction: DeleteTransactionUseCase,
    private val scheduler: ReminderScheduler,
) {
    suspend operator fun invoke(reminderId: String, originalDate: LocalDate) {
        val record = repository.record(reminderId, originalDate) ?: return
        record.transactionId?.let { deleteTransaction(it) }
        repository.saveRecord(record.copy(action = null, completedAt = null, paidAmountMinor = null, transactionId = null))
        scheduler.reschedule()
    }
}

class UpdateOccurrenceUseCase @Inject constructor(
    private val repository: ReminderRepository,
    private val scheduler: ReminderScheduler,
    private val clock: Clock,
) {
    suspend fun snooze(occurrence: Occurrence) = update(occurrence.reminder.id, occurrence.originalDate) {
        it.copy(snoozedUntil = ReminderMath.snoozeUntil(occurrence, clock.instant(), clock.zone))
    }

    suspend fun skip(reminderId: String, originalDate: LocalDate) = update(reminderId, originalDate) {
        it.copy(action = OccurrenceAction.SKIPPED, completedAt = clock.instant(), snoozedUntil = null)
    }

    /** Moves only this occurrence; the series keeps its schedule. */
    suspend fun reschedule(reminderId: String, originalDate: LocalDate, to: LocalDateTime) = update(reminderId, originalDate) {
        it.copy(rescheduledTo = to, snoozedUntil = null)
    }

    suspend fun toggleStep(reminderId: String, originalDate: LocalDate, stepId: String) = update(reminderId, originalDate) {
        it.copy(doneStepIds = if (stepId in it.doneStepIds) it.doneStepIds - stepId else it.doneStepIds + stepId)
    }

    private suspend fun update(reminderId: String, originalDate: LocalDate, block: (OccurrenceRecord) -> OccurrenceRecord) {
        val existing = repository.record(reminderId, originalDate) ?: OccurrenceRecord(reminderId, originalDate)
        repository.saveRecord(block(existing))
        scheduler.reschedule()
    }
}

data class RemindersOverview(
    val overdue: List<Occurrence>,
    val today: List<Occurrence>,
    val upcoming: List<Occurrence>,
    val done: List<Occurrence>,
    val categories: Map<String, Category>,
) {
    val upcomingCount get() = today.size + upcoming.size
}

class ObserveRemindersOverviewUseCase @Inject constructor(
    private val repository: ReminderRepository,
    private val categories: CategoryRepository,
    private val clock: Clock,
) {
    operator fun invoke(): Flow<RemindersOverview> =
        combine(repository.observe(), repository.observeRecords(), categories.observe()) { reminders, records, cats ->
            build(reminders, records, LocalDateTime.now(clock)).copy(categories = cats.associateBy { it.id })
        }

    companion object {
        /** Each reminder contributes its overdue occurrences, its next open one, and recent completions. */
        fun build(reminders: List<Reminder>, records: List<OccurrenceRecord>, now: LocalDateTime): RemindersOverview {
            val today = now.toLocalDate()
            val byReminder = records.groupBy { it.reminderId }.mapValues { (_, l) -> l.associateBy { it.originalDate } }
            val overdue = mutableListOf<Occurrence>()
            val todayList = mutableListOf<Occurrence>()
            val upcoming = mutableListOf<Occurrence>()
            val done = mutableListOf<Occurrence>()
            reminders.filter { it.active }.forEach { r ->
                val recs = byReminder[r.id].orEmpty()
                val recent = ReminderMath.occurrences(r, recs, today.minusDays(ReminderMath.OVERDUE_WINDOW_DAYS), today, now)
                overdue += recent.filter { it.status == OccurrenceStatus.OVERDUE }
                done += recent.filter { it.status == OccurrenceStatus.DONE }
                ReminderMath.nextOpen(r, recs, now)?.let { if (it.status == OccurrenceStatus.DUE_TODAY) todayList += it else upcoming += it }
                // Occurrences rescheduled into the future from the past window are still "open".
                recent.filter { it.status == OccurrenceStatus.UPCOMING }.forEach { o -> if (upcoming.none { it.reminder.id == r.id && it.originalDate == o.originalDate }) upcoming += o }
            }
            return RemindersOverview(
                overdue = overdue.sortedBy { it.at },
                today = todayList.sortedBy { it.at },
                upcoming = upcoming.sortedBy { it.at },
                done = done.sortedByDescending { it.record?.completedAt },
                categories = emptyMap(),
            )
        }
    }
}

class ObserveCalendarMonthUseCase @Inject constructor(
    private val repository: ReminderRepository,
    private val clock: Clock,
) {
    /** Every occurrence whose effective date falls in [month], grouped by day. */
    operator fun invoke(month: YearMonth): Flow<Map<LocalDate, List<Occurrence>>> =
        combine(repository.observe(), repository.observeRecords()) { reminders, records ->
            val now = LocalDateTime.now(clock)
            val byReminder = records.groupBy { it.reminderId }.mapValues { (_, l) -> l.associateBy { it.originalDate } }
            // Look a month either side so occurrences rescheduled into this month are included.
            val from = month.atDay(1).minusMonths(1)
            val to = month.atEndOfMonth().plusMonths(1)
            reminders.filter { it.active }
                .flatMap { r -> ReminderMath.occurrences(r, byReminder[r.id].orEmpty(), from, to, now) }
                .filter { YearMonth.from(it.date) == month }
                .groupBy { it.date }
                .mapValues { (_, l) -> l.sortedBy { it.at } }
        }
}

class ObserveReminderUseCase @Inject constructor(
    private val repository: ReminderRepository,
    private val clock: Clock,
) {
    data class Details(
        val reminder: Reminder,
        val next: Occurrence?,
        val history: List<Occurrence>,
        val records: Map<LocalDate, OccurrenceRecord>,
    ) {
        /** A specific occurrence (e.g. opened from a notification), or the next one that needs attention. */
        fun occurrenceOn(originalDate: LocalDate?, now: LocalDateTime): Occurrence? =
            originalDate?.let { ReminderMath.occurrences(reminder, records, it, it, now).firstOrNull() } ?: next
    }

    operator fun invoke(id: String): Flow<Details?> = combine(repository.observe(), repository.observeRecords()) { reminders, records ->
        val r = reminders.firstOrNull { it.id == id } ?: return@combine null
        val now = LocalDateTime.now(clock)
        val recs = records.filter { it.reminderId == id }.associateBy { it.originalDate }
        val overdue = ReminderMath.occurrences(r, recs, now.toLocalDate().minusDays(ReminderMath.OVERDUE_WINDOW_DAYS), now.toLocalDate(), now)
            .firstOrNull { it.status == OccurrenceStatus.OVERDUE }
        val history = recs.values.filter { it.action != null }.sortedByDescending { it.originalDate }.take(20).map { rec ->
            val at = rec.rescheduledTo ?: rec.originalDate.atTime(r.time ?: ReminderMath.ALL_DAY_ALERT_TIME)
            Occurrence(r, rec.originalDate, at, ReminderMath.status(r, rec, at, now), rec)
        }
        Details(r, overdue ?: ReminderMath.nextOpen(r, recs, now), history, recs)
    }
}

/** Ready-made preparation steps (spec §8 example: birthday on Dec 10 → gift Dec 5, cake Dec 9, wish Dec 10). */
object PrepTemplates {
    fun forKind(kind: ReminderKind): List<Pair<String, Int>> = when (kind) {
        ReminderKind.BIRTHDAY -> listOf("Buy a gift" to 5, "Order the cake" to 1, "Wish them" to 0)
        ReminderKind.ANNIVERSARY -> listOf("Plan something special" to 7, "Buy a gift" to 3)
        ReminderKind.FESTIVAL -> listOf("Shopping" to 7, "Clean and decorate" to 2)
        ReminderKind.TRAVEL -> listOf("Pack bags" to 1, "Check in online" to 1, "Book cab" to 1)
        ReminderKind.EXAM -> listOf("Revise" to 7, "Keep hall ticket ready" to 1)
        ReminderKind.MEETING -> listOf("Prepare notes" to 1)
        ReminderKind.APPOINTMENT -> listOf("Keep documents ready" to 1)
        else -> emptyList()
    }
}
