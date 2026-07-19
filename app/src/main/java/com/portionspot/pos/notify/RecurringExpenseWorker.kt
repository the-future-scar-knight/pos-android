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
 * Auto-posts due recurring expenses (prompt §9.3). A recurring expense is approved by
 * the admin ONCE; thereafter this worker posts each period's charge automatically and
 * merely NOTIFIES the admin — no re-approval. When cash-on-hand is short at post time
 * the repository applies the "take what's available" rule (cash first, remainder →
 * accounts payable) so the books stay balanced with no UI. All local — no network
 * constraint — so it runs fully offline.
 */
class RecurringExpenseWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? PosApp ?: return Result.success()
        return try {
            val toPush = app.container.repository.runRecurringExpenseSweep()
            toPush.forEach { Notifier.notifyAdmin(applicationContext, it) }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val PERIODIC = "pos_recurring_expenses"
        private const val ONESHOT = "pos_recurring_expenses_now"

        fun schedule(context: Context) {
            // Check a few times a day; posting is idempotent per-period (nextRunAt gate).
            val req = PeriodicWorkRequestBuilder<RecurringExpenseWorker>(6, TimeUnit.HOURS).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        /** Run a sweep immediately (e.g. on app launch, or right after approving a schedule). */
        fun runNow(context: Context) {
            val req = OneTimeWorkRequestBuilder<RecurringExpenseWorker>().build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(ONESHOT, ExistingWorkPolicy.REPLACE, req)
        }
    }
}
