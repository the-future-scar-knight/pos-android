package com.portionspot.pos.notify

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.portionspot.pos.PosApp
import java.util.concurrent.TimeUnit

/**
 * The admin notifications backend loop (prompt §8, Phase 7). Recomputes the shop's
 * alert state on a schedule (and on demand) and pushes any newly-escalated conditions.
 * All local — no network constraint — so escalation works even fully offline (the one
 * §8 item that DOES depend on connectivity, "device hasn't synced", is derived from
 * the pending-count + last-sync time, not from a live call).
 */
class AdminNotificationWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? PosApp ?: return Result.success()
        return try {
            val repo = app.container.repository
            val thresholds = repo.loadNotifThresholds()
            val pending = repo.pendingSyncCount()
            val lastSync = app.container.syncManager.lastSyncAt()
            val toPush = repo.runNotificationSweep(thresholds, pending, lastSync)
            toPush.forEach { Notifier.notifyAdmin(applicationContext, it) }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val PERIODIC = "pos_admin_notifications"
        private const val ONESHOT = "pos_admin_notifications_now"

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<AdminNotificationWorker>(30, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        /** Run a sweep immediately (e.g. on app launch, or after a big write). */
        fun runNow(context: Context) {
            val req = OneTimeWorkRequestBuilder<AdminNotificationWorker>().build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(ONESHOT, ExistingWorkPolicy.REPLACE, req)
        }
    }
}
