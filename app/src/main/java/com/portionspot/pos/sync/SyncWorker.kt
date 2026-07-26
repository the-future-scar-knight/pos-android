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
import com.portionspot.pos.notify.AdminNotificationWorker
import com.portionspot.pos.notify.Notifier
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
        val app = applicationContext as PosApp
        val container = app.container
        return when (val outcome = container.syncEngine.sync()) {
            is SyncOutcome.Success -> {
                // BUG A, background path: a pull may have brought down alert rows this
                // phone must buzz for (a cashier's expense submission never gets re-derived
                // by the sweep). This is a background pass with no live UI, so the role
                // comes from the last-active/stored account in the vault; local mode has no
                // cloud session and the owner is always the admin, so a null session reads
                // as admin (see AuthManager.isDeviceAdmin). If genuinely indeterminate we
                // therefore default to firing admin-audience alerts — the historical target.
                if (outcome.pulled > 0) {
                    val isAdmin = container.authManager.isDeviceAdmin()
                    runCatching {
                        container.repository.fireUnpushedHeadsUps(isAdmin)
                            .forEach { Notifier.notifyAlert(applicationContext, it) }
                    }
                    // Recompute engine conditions off the freshly-synced data. Coalesced by
                    // unique REPLACE work, so kicking it every background pull is safe.
                    AdminNotificationWorker.runNow(applicationContext)
                }
                Result.success()
            }
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
