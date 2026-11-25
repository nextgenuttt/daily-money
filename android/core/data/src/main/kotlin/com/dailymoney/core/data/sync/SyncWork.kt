package com.dailymoney.core.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.dailymoney.core.domain.config.AppConfig
import com.dailymoney.core.domain.sharing.SyncScheduler
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun syncEngine(): SyncEngine
        fun auth(): FirebaseAuth
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java)
        if (deps.auth().currentUser == null) return Result.success()
        return if (deps.syncEngine().sync()) Result.success() else Result.retry()
    }
}

/** LLD §6.6: sync after local writes (debounced), on app open, on a push poke, and every 6 hours. */
@Singleton
class WorkManagerSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val config: AppConfig,
) : SyncScheduler {

    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    override fun requestSync() {
        if (!config.backendEnabled) return
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(online)
            .setInitialDelay(DEBOUNCE_SECONDS, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // REPLACE restarts the 2 s wait on every request (debounce). Cancelling a sync mid-way is safe:
        // queued ops keep their ids and the server answers a replay with the stored result.
        WorkManager.getInstance(context).enqueueUniqueWork(SYNC, ExistingWorkPolicy.REPLACE, request)
    }

    override fun schedulePeriodic() {
        if (!config.backendEnabled) return
        val request = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS).setConstraints(online).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private companion object {
        const val SYNC = "sync"
        const val PERIODIC = "sync-periodic"
        const val DEBOUNCE_SECONDS = 2L
    }
}
