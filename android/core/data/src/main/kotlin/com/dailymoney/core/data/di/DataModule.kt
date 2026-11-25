package com.dailymoney.core.data.di

import com.dailymoney.core.data.auth.FirebaseAuthRepository
import com.dailymoney.core.data.auth.FirebaseTokenProvider
import com.dailymoney.core.data.money.RoomAccountRepository
import com.dailymoney.core.data.money.RoomBudgetRepository
import com.dailymoney.core.data.money.RoomCategoryRepository
import com.dailymoney.core.data.money.RoomTransactionRepository
import com.dailymoney.core.domain.money.AccountRepository
import com.dailymoney.core.domain.money.BudgetRepository
import com.dailymoney.core.domain.money.CategoryRepository
import com.dailymoney.core.domain.money.TransactionRepository
import java.time.Clock
import com.dailymoney.core.data.backup.BackupManager
import com.dailymoney.core.domain.backup.BackupService
import com.dailymoney.core.data.groups.RemoteGroupRepository
import com.dailymoney.core.domain.groups.GroupRepository
import com.dailymoney.core.data.todo.RoomTodoRepository
import com.dailymoney.core.domain.todo.TodoRepository
import com.dailymoney.core.data.reminders.AndroidNotifier
import com.dailymoney.core.data.reminders.AndroidReminderScheduler
import com.dailymoney.core.data.reminders.RoomBudgetAlertStore
import com.dailymoney.core.data.reminders.RoomReminderRepository
import com.dailymoney.core.domain.reminders.BudgetAlertStore
import com.dailymoney.core.domain.reminders.Notifier
import com.dailymoney.core.domain.reminders.ReminderRepository
import com.dailymoney.core.domain.reminders.ReminderScheduler
import com.dailymoney.core.data.planning.AttachmentStore
import com.dailymoney.core.data.planning.ExportWriter
import com.dailymoney.core.data.planning.RoomDebtRepository
import com.dailymoney.core.data.planning.RoomGoalRepository
import com.dailymoney.core.data.planning.RoomRecurringRepository
import com.dailymoney.core.data.planning.WorkManagerJobs
import com.dailymoney.core.domain.money.AttachmentRepository
import com.dailymoney.core.domain.money.BackgroundJobs
import com.dailymoney.core.domain.money.DebtRepository
import com.dailymoney.core.domain.money.ExportService
import com.dailymoney.core.domain.money.GoalRepository
import com.dailymoney.core.domain.money.RecurringRepository
import com.dailymoney.core.data.session.BackendSessionImpl
import com.dailymoney.core.data.session.LocalDataWiperImpl
import com.dailymoney.core.data.session.PendingChangesRepositoryImpl
import com.dailymoney.core.domain.auth.AuthRepository
import com.dailymoney.core.domain.auth.BackendSession
import com.dailymoney.core.domain.auth.LocalDataWiper
import com.dailymoney.core.domain.auth.PendingChangesRepository
import com.dailymoney.core.network.AuthTokenProvider
import com.dailymoney.core.data.notify.RemoteMessageRepository
import com.dailymoney.core.data.notify.RoomNotificationRepository
import com.dailymoney.core.data.sync.RemoteSettlementRepository
import com.dailymoney.core.data.sync.RoomSharedExpenseRepository
import com.dailymoney.core.data.sync.WorkManagerSync
import com.dailymoney.core.domain.sharing.MessageRepository
import com.dailymoney.core.domain.sharing.NotificationRepository
import com.dailymoney.core.domain.sharing.SettlementRepository
import com.dailymoney.core.domain.sharing.SharedExpenseRepository
import com.dailymoney.core.domain.sharing.SyncScheduler
import com.google.firebase.auth.FirebaseAuth
import com.dailymoney.core.data.smart.ContentStatementReader
import com.dailymoney.core.data.smart.MlKitReceiptReader
import com.dailymoney.core.data.smart.RoomCategoryRuleRepository
import com.dailymoney.core.domain.smart.CategoryRuleRepository
import com.dailymoney.core.domain.smart.ReceiptTextReader
import com.dailymoney.core.domain.smart.StatementFileReader
import com.dailymoney.core.data.session.AccountDeletionImpl
import com.dailymoney.core.domain.auth.AccountDeletion
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {

    @Binds
    abstract fun authRepository(impl: FirebaseAuthRepository): AuthRepository

    @Binds
    abstract fun backendSession(impl: BackendSessionImpl): BackendSession

    @Binds
    abstract fun localDataWiper(impl: LocalDataWiperImpl): LocalDataWiper

    @Binds
    abstract fun pendingChanges(impl: PendingChangesRepositoryImpl): PendingChangesRepository

    @Binds
    abstract fun authTokenProvider(impl: FirebaseTokenProvider): AuthTokenProvider

    @Binds
    abstract fun accountRepository(impl: RoomAccountRepository): AccountRepository

    @Binds
    abstract fun categoryRepository(impl: RoomCategoryRepository): CategoryRepository

    @Binds
    abstract fun transactionRepository(impl: RoomTransactionRepository): TransactionRepository

    @Binds
    abstract fun budgetRepository(impl: RoomBudgetRepository): BudgetRepository

    @Binds
    abstract fun recurringRepository(impl: RoomRecurringRepository): RecurringRepository

    @Binds
    abstract fun goalRepository(impl: RoomGoalRepository): GoalRepository

    @Binds
    abstract fun debtRepository(impl: RoomDebtRepository): DebtRepository

    @Binds
    abstract fun attachmentRepository(impl: AttachmentStore): AttachmentRepository

    @Binds
    abstract fun exportService(impl: ExportWriter): ExportService

    @Binds
    abstract fun backgroundJobs(impl: WorkManagerJobs): BackgroundJobs

    @Binds
    abstract fun reminderRepository(impl: RoomReminderRepository): ReminderRepository

    @Binds
    abstract fun reminderScheduler(impl: AndroidReminderScheduler): ReminderScheduler

    @Binds
    abstract fun budgetAlertStore(impl: RoomBudgetAlertStore): BudgetAlertStore

    @Binds
    abstract fun notifier(impl: AndroidNotifier): Notifier

    @Binds
    abstract fun todoRepository(impl: RoomTodoRepository): TodoRepository

    @Binds
    abstract fun groupRepository(impl: RemoteGroupRepository): GroupRepository

    @Binds
    abstract fun backupService(impl: BackupManager): BackupService

    @Binds
    abstract fun sharedExpenseRepository(impl: RoomSharedExpenseRepository): SharedExpenseRepository

    @Binds
    abstract fun syncScheduler(impl: WorkManagerSync): SyncScheduler

    @Binds
    abstract fun settlementRepository(impl: RemoteSettlementRepository): SettlementRepository

    @Binds
    abstract fun notificationRepository(impl: RoomNotificationRepository): NotificationRepository

    @Binds
    abstract fun messageRepository(impl: RemoteMessageRepository): MessageRepository

    @Binds
    abstract fun accountDeletion(impl: AccountDeletionImpl): AccountDeletion

    @Binds
    abstract fun receiptTextReader(impl: MlKitReceiptReader): ReceiptTextReader

    @Binds
    abstract fun statementFileReader(impl: ContentStatementReader): StatementFileReader

    @Binds
    abstract fun categoryRuleRepository(impl: RoomCategoryRuleRepository): CategoryRuleRepository

    companion object {
        @Provides
        @Singleton
        fun firebaseAuth(): FirebaseAuth = FirebaseAuth.getInstance()

        /** Uses the phone's current time zone, so "this month" follows the user when they travel. */
        @Provides
        fun clock(): Clock = Clock.systemDefaultZone()
    }
}
