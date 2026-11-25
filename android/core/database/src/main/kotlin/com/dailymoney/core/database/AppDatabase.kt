package com.dailymoney.core.database

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase
import com.dailymoney.core.database.dao.AccountDao
import com.dailymoney.core.database.dao.AttachmentDao
import com.dailymoney.core.database.dao.BudgetAlertDao
import com.dailymoney.core.database.dao.BudgetDao
import com.dailymoney.core.database.dao.ReminderDao
import com.dailymoney.core.database.dao.CategoryDao
import com.dailymoney.core.database.dao.CategoryRuleDao
import com.dailymoney.core.database.dao.DebtDao
import com.dailymoney.core.database.dao.GoalDao
import com.dailymoney.core.database.dao.PendingOpDao
import com.dailymoney.core.database.dao.RecurringDao
import com.dailymoney.core.database.dao.SyncDao
import com.dailymoney.core.database.dao.TodoDao
import com.dailymoney.core.database.dao.TransactionDao
import com.dailymoney.core.database.entity.AccountEntity
import com.dailymoney.core.database.entity.AttachmentEntity
import com.dailymoney.core.database.entity.BudgetAlertEntity
import com.dailymoney.core.database.entity.BudgetEntity
import com.dailymoney.core.database.entity.OccurrenceRecordEntity
import com.dailymoney.core.database.entity.ReminderEntity
import com.dailymoney.core.database.entity.ReminderStepEntity
import com.dailymoney.core.database.entity.CategoryEntity
import com.dailymoney.core.database.entity.CategoryRuleEntity
import com.dailymoney.core.database.entity.DebtEntity
import com.dailymoney.core.database.entity.DebtRepaymentEntity
import com.dailymoney.core.database.entity.GoalContributionEntity
import com.dailymoney.core.database.entity.NotificationEntity
import com.dailymoney.core.database.entity.PendingOpEntity
import com.dailymoney.core.database.entity.RecurringRuleEntity
import com.dailymoney.core.database.entity.SavingsGoalEntity
import com.dailymoney.core.database.entity.SettlementEntity
import com.dailymoney.core.database.entity.SharedDecisionEntity
import com.dailymoney.core.database.entity.SharedExpenseEntity
import com.dailymoney.core.database.entity.SharedSplitEntity
import com.dailymoney.core.database.entity.SyncMemberEntity
import com.dailymoney.core.database.entity.SyncGroupEntity
import com.dailymoney.core.database.entity.SyncStateEntity
import com.dailymoney.core.database.entity.TodoActivityEntity
import com.dailymoney.core.database.entity.TodoChecklistItemEntity
import com.dailymoney.core.database.entity.TodoEntity
import com.dailymoney.core.database.entity.TransactionEntity

@Database(
    entities = [
        PendingOpEntity::class,
        AccountEntity::class,
        CategoryEntity::class,
        TransactionEntity::class,
        BudgetEntity::class,
        RecurringRuleEntity::class,
        SavingsGoalEntity::class,
        GoalContributionEntity::class,
        DebtEntity::class,
        DebtRepaymentEntity::class,
        AttachmentEntity::class,
        ReminderEntity::class,
        ReminderStepEntity::class,
        OccurrenceRecordEntity::class,
        BudgetAlertEntity::class,
        TodoEntity::class,
        TodoChecklistItemEntity::class,
        TodoActivityEntity::class,
        SharedExpenseEntity::class,
        SyncGroupEntity::class,
        SyncStateEntity::class,
        SharedSplitEntity::class,
        SyncMemberEntity::class,
        SettlementEntity::class,
        SharedDecisionEntity::class,
        NotificationEntity::class,
        CategoryRuleEntity::class,
    ],
    version = 11,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5), AutoMigration(from = 5, to = 6), AutoMigration(from = 6, to = 7, spec = AppDatabase.ResyncGroups::class),
        AutoMigration(from = 7, to = 8, spec = AppDatabase.ResyncGroups::class),
        AutoMigration(from = 8, to = 9, spec = AppDatabase.ResyncGroups::class),
        AutoMigration(from = 9, to = 10),
        AutoMigration(from = 10, to = 11),
    ],
)
abstract class AppDatabase : RoomDatabase() {

    /** Group rows synced by an older version lack newer fields (creators, splits, approvals): pull everything again. */
    class ResyncGroups : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            db.execSQL("DELETE FROM sync_state WHERE `key` = 'cursor'")
        }
    }

    abstract fun pendingOpDao(): PendingOpDao
    abstract fun accountDao(): AccountDao
    abstract fun categoryDao(): CategoryDao
    abstract fun categoryRuleDao(): CategoryRuleDao
    abstract fun transactionDao(): TransactionDao
    abstract fun budgetDao(): BudgetDao
    abstract fun recurringDao(): RecurringDao
    abstract fun goalDao(): GoalDao
    abstract fun debtDao(): DebtDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun reminderDao(): ReminderDao
    abstract fun budgetAlertDao(): BudgetAlertDao
    abstract fun todoDao(): TodoDao
    abstract fun syncDao(): SyncDao
}
