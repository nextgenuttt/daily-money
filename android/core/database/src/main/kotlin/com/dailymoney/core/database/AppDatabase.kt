package com.dailymoney.core.database

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import com.dailymoney.core.database.dao.AccountDao
import com.dailymoney.core.database.dao.AttachmentDao
import com.dailymoney.core.database.dao.BudgetAlertDao
import com.dailymoney.core.database.dao.BudgetDao
import com.dailymoney.core.database.dao.ReminderDao
import com.dailymoney.core.database.dao.CategoryDao
import com.dailymoney.core.database.dao.DebtDao
import com.dailymoney.core.database.dao.GoalDao
import com.dailymoney.core.database.dao.PendingOpDao
import com.dailymoney.core.database.dao.RecurringDao
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
import com.dailymoney.core.database.entity.DebtEntity
import com.dailymoney.core.database.entity.DebtRepaymentEntity
import com.dailymoney.core.database.entity.GoalContributionEntity
import com.dailymoney.core.database.entity.PendingOpEntity
import com.dailymoney.core.database.entity.RecurringRuleEntity
import com.dailymoney.core.database.entity.SavingsGoalEntity
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
    ],
    version = 5,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5)],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun pendingOpDao(): PendingOpDao
    abstract fun accountDao(): AccountDao
    abstract fun categoryDao(): CategoryDao
    abstract fun transactionDao(): TransactionDao
    abstract fun budgetDao(): BudgetDao
    abstract fun recurringDao(): RecurringDao
    abstract fun goalDao(): GoalDao
    abstract fun debtDao(): DebtDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun reminderDao(): ReminderDao
    abstract fun budgetAlertDao(): BudgetAlertDao
    abstract fun todoDao(): TodoDao
}
