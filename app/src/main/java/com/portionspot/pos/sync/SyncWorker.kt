package com.portionspot.pos.sync

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
import com.portionspot.pos.PosApp
import java.util.concurrent.TimeUnit

/**
 * Background sync. Runs every ~15 min (the WorkManager minimum) while online,
 * and on demand via [syncNow]. The NetworkType.CONNECTED constraint is what
 * makes offline edits "wait and flush" — WorkManager holds the job until the
 * device is back online.
 */
class SyncWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val engine = (applicationContext as PosApp).container.syncEngine
        return when (engine.sync()) {
            is SyncOutcome.Success -> Result.success()
            SyncOutcome.NotConfigured -> Result.success()   // nothing to do yet
            is SyncOutcome.Failed -> Result.retry()         // back off and try again
        }
    }

    companion object {
        private const val PERIODIC = "pos_periodic_sync"
        private const val ONESHOT = "pos_oneshot_sync"

        private fun onlineConstraints() = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun schedulePeriodic(context: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(onlineConstraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        fun cancelPeriodic(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
        }

        fun syncNow(context: Context) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(onlineConstraints())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(ONESHOT, ExistingWorkPolicy.REPLACE, req)
        }
    }
}
