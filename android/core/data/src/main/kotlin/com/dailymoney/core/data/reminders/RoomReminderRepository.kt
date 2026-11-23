package com.dailymoney.core.data.reminders

import com.dailymoney.core.data.money.io
import com.dailymoney.core.data.money.stream
import com.dailymoney.core.database.DatabaseManager
import com.dailymoney.core.database.entity.BudgetAlertEntity
import com.dailymoney.core.database.entity.OccurrenceRecordEntity
import com.dailymoney.core.database.entity.ReminderEntity
import com.dailymoney.core.database.entity.ReminderStepEntity
import com.dailymoney.core.domain.money.Recurrence
import com.dailymoney.core.domain.reminders.BudgetAlertMark
import com.dailymoney.core.domain.reminders.BudgetAlertStore
import com.dailymoney.core.domain.reminders.OccurrenceAction
import com.dailymoney.core.domain.reminders.OccurrenceRecord
import com.dailymoney.core.domain.reminders.PrepStep
import com.dailymoney.core.domain.reminders.Reminder
import com.dailymoney.core.domain.reminders.ReminderKind
import com.dailymoney.core.domain.reminders.ReminderRepository
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

@Singleton
class RoomReminderRepository @Inject constructor(
    private val db: DatabaseManager,
    private val clock: Clock,
) : ReminderRepository {

    override fun observe(): Flow<List<Reminder>> = combine(
        db.stream { reminderDao().observeReminders() },
        db.stream { reminderDao().observeSteps() },
    ) { reminders, steps -> join(reminders, steps) }

    override fun observeRecords(): Flow<List<OccurrenceRecord>> = db.stream { reminderDao().observeRecords() }.map { it.map(::toDomain) }

    override suspend fun all(): List<Reminder> = io { join(db.get().reminderDao().reminders(), db.get().reminderDao().steps()) }

    override suspend fun allRecords(): List<OccurrenceRecord> = io { db.get().reminderDao().records().map(::toDomain) }

    override suspend fun get(id: String): Reminder? = io {
        val dao = db.get().reminderDao()
        dao.get(id)?.let { join(listOf(it), dao.stepsFor(id)).single() }
    }

    override suspend fun record(reminderId: String, originalDate: LocalDate): OccurrenceRecord? = io {
        db.get().reminderDao().record(reminderId, originalDate.toEpochDay())?.let(::toDomain)
    }

    override suspend fun save(reminder: Reminder) = io {
        val now = clock.millis()
        val dao = db.get().reminderDao()
        val existing = dao.get(reminder.id)
        dao.saveWithSteps(
            ReminderEntity(
                id = reminder.id,
                kind = reminder.kind.name,
                title = reminder.title,
                notes = reminder.notes,
                amountMinor = reminder.amountMinor,
                currency = reminder.currency,
                categoryId = reminder.categoryId,
                accountId = reminder.accountId,
                startEpochDay = reminder.startDate.toEpochDay(),
                timeMinutes = reminder.time?.let { it.hour * 60 + it.minute },
                endTimeMinutes = reminder.endTime?.let { it.hour * 60 + it.minute },
                location = reminder.location,
                rrule = reminder.recurrence?.toRRule(),
                alertOffsets = reminder.alertOffsets.joinToString(","),
                active = reminder.active,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
                version = (existing?.version ?: 0) + 1,
            ),
            reminder.steps.mapIndexed { i, s -> ReminderStepEntity(s.id, reminder.id, s.title, s.daysBefore, i) },
        )
    }

    override suspend fun delete(id: String) = io { db.get().reminderDao().softDelete(id, clock.millis()) }

    override suspend fun saveRecord(record: OccurrenceRecord) = io {
        db.get().reminderDao().upsertRecord(
            OccurrenceRecordEntity(
                reminderId = record.reminderId,
                originalEpochDay = record.originalDate.toEpochDay(),
                action = record.action?.name,
                completedAt = record.completedAt?.toEpochMilli(),
                snoozedUntil = record.snoozedUntil?.toEpochMilli(),
                rescheduledTo = record.rescheduledTo?.toString(),
                paidAmountMinor = record.paidAmountMinor,
                transactionId = record.transactionId,
                doneStepIds = record.doneStepIds.joinToString(","),
                updatedAt = clock.millis(),
            ),
        )
    }

    override suspend fun deleteRecord(reminderId: String, originalDate: LocalDate) = io {
        db.get().reminderDao().deleteRecord(reminderId, originalDate.toEpochDay())
    }

    private fun join(reminders: List<ReminderEntity>, steps: List<ReminderStepEntity>): List<Reminder> {
        val byReminder = steps.groupBy { it.reminderId }
        return reminders.map { e ->
            Reminder(
                id = e.id,
                kind = runCatching { ReminderKind.valueOf(e.kind) }.getOrDefault(ReminderKind.CUSTOM),
                title = e.title,
                notes = e.notes,
                amountMinor = e.amountMinor,
                currency = e.currency,
                categoryId = e.categoryId,
                accountId = e.accountId,
                startDate = LocalDate.ofEpochDay(e.startEpochDay),
                time = e.timeMinutes?.let { LocalTime.of(it / 60, it % 60) },
                endTime = e.endTimeMinutes?.let { LocalTime.of(it / 60, it % 60) },
                location = e.location,
                recurrence = e.rrule?.let(Recurrence::parse),
                alertOffsets = e.alertOffsets.split(',').mapNotNull { it.trim().toIntOrNull() },
                steps = byReminder[e.id].orEmpty().map { PrepStep(it.id, it.title, it.daysBefore) },
                active = e.active,
            )
        }
    }

    private fun toDomain(e: OccurrenceRecordEntity) = OccurrenceRecord(
        reminderId = e.reminderId,
        originalDate = LocalDate.ofEpochDay(e.originalEpochDay),
        action = e.action?.let { runCatching { OccurrenceAction.valueOf(it) }.getOrNull() },
        completedAt = e.completedAt?.let(Instant::ofEpochMilli),
        snoozedUntil = e.snoozedUntil?.let(Instant::ofEpochMilli),
        rescheduledTo = e.rescheduledTo?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() },
        paidAmountMinor = e.paidAmountMinor,
        transactionId = e.transactionId,
        doneStepIds = e.doneStepIds.split(',').filter { it.isNotBlank() }.toSet(),
    )
}

@Singleton
class RoomBudgetAlertStore @Inject constructor(private val db: DatabaseManager) : BudgetAlertStore {
    override suspend fun marks(): List<BudgetAlertMark> = io {
        db.get().budgetAlertDao().all().map { BudgetAlertMark(it.budgetId, LocalDate.ofEpochDay(it.periodStartEpochDay), it.level) }
    }

    override suspend fun save(mark: BudgetAlertMark) = io {
        db.get().budgetAlertDao().upsert(BudgetAlertEntity(mark.budgetId, mark.periodStart.toEpochDay(), mark.level))
    }
}
