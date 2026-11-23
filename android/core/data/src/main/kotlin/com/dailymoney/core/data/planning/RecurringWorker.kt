package com.dailymoney.core.data.planning

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.dailymoney.core.domain.money.BackgroundJobs
import com.dailymoney.core.domain.money.ObserveBudgetProgressUseCase
import com.dailymoney.core.domain.money.PostDueRecurringUseCase
import com.dailymoney.core.domain.reminders.CheckBudgetAlertsUseCase
import com.dailymoney.core.domain.reminders.ReminderScheduler
import kotlinx.coroutines.flow.first
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Daily job: posts due recurring transactions, checks budget alerts and re-plans reminder alarms. */
class RecurringWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun postDue(): PostDueRecurringUseCase
        fun auth(): FirebaseAuth
        fun scheduler(): ReminderScheduler
        fun budgetProgress(): ObserveBudgetProgressUseCase
        fun budgetAlerts(): CheckBudgetAlertsUseCase
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java)
        if (deps.auth().currentUser == null) return Result.success()
        return runCatching {
            deps.postDue()()
            deps.budgetAlerts()(deps.budgetProgress()().first())
            deps.scheduler().reschedule()
        }.fold({ Result.success() }, { Result.retry() })
    }
}

@Singleton
class WorkManagerJobs @Inject constructor(@ApplicationContext private val context: Context) : BackgroundJobs {

    override fun scheduleRecurring() {
        val request = PeriodicWorkRequestBuilder<RecurringWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        // KEEP: re-scheduling on every app start must not reset the daily timer.
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(RECURRING, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private companion object {
        const val RECURRING = "recurring-transactions"
    }
}
