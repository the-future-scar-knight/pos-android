package com.portionspot.pos.sync

import android.content.Context
import com.portionspot.pos.device.ConnectivityObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** UI-facing sync state for the Settings screen. */
sealed class SyncStatus {
    object Idle : SyncStatus()
    object Syncing : SyncStatus()
    /** [warnings] carries per-table push failures from a partial pass (empty = clean). */
    data class Done(
        val pushed: Int,
        val pulled: Int,
        val at: Long,
        val warnings: List<String> = emptyList()
    ) : SyncStatus()
    data class Error(val message: String) : SyncStatus()
}

/**
 * Single entry point the UI talks to for connecting a database and syncing.
 * Holds the application context so it can (un)schedule the [SyncWorker].
 */
class SyncManager(
    private val appContext: Context,
    val config: SyncConfig,
    private val engine: PosSyncEngine
) {
    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    /** Long-lived scope for debounced/background flushes (survives individual screens). */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The single in-flight debounce timer; a new [requestSync] cancels and replaces it,
     *  so a burst of edits collapses into ONE sync. */
    private var debounceJob: Job? = null

    init {
        // Flush the moment the device comes back online (offline→online) if anything is
        // queued. Seeded emit is the current state, so we only act on a real transition.
        scope.launch {
            var wasOnline = ConnectivityObserver.currentlyOnline(appContext)
            ConnectivityObserver.onlineFlow(appContext).collect { online ->
                if (online && !wasOnline) requestSync("reconnect")
                wasOnline = online
            }
        }
    }

    suspend fun connection(): Connection? = config.connection()
    suspend fun isConfigured(): Boolean = config.isConfigured()
    suspend fun lastSyncAt(): Long? = config.lastSyncAt()
    suspend fun lastUploadAt(): Long? = config.lastUploadAt()
    suspend fun lastDownloadAt(): Long? = config.lastDownloadAt()

    /** Rows waiting to upload — for the "N to upload" badge. */
    suspend fun pendingUploadCount(): Int = engine.pendingUploadCount()

    /**
     * Ask for a sync SOON, coalescing a burst of local writes into one pass. Called
     * fire-and-forget right after any money-moving mutation (sale, customer, credit,
     * refund, mobile-money verify, product edit) so cloud data appears within seconds
     * instead of on the ~15-minute worker cycle. Never blocks the caller or the local save.
     *
     * Design: a ~2.5s debounce, then — if a connection is configured and there's actually
     * pending data — either run in-process ([runNow], for the fast status/timestamp update)
     * when online, or enqueue the CONNECTED-constrained one-shot [SyncWorker] when offline
     * so the edit still flushes on reconnect. Offline-first stays intact either way.
     */
    fun requestSync(reason: String? = null) {
        synchronized(this) {
            debounceJob?.cancel()
            debounceJob = scope.launch {
                delay(DEBOUNCE_MS)
                flushIfNeeded()
            }
        }
    }

    private suspend fun flushIfNeeded() {
        if (!config.isConfigured()) return
        // Nothing queued → don't hit the network on every keystroke-sized change.
        if (engine.pendingUploadCount() <= 0) return
        if (ConnectivityObserver.currentlyOnline(appContext)) {
            val outcome = runNow()
            // If the live pass failed mid-flight (network dropped), fall back to the
            // durable worker so the queued rows still flush once connectivity is stable.
            if (outcome is SyncOutcome.Failed) SyncWorker.syncNow(appContext)
        } else {
            // Offline: hand it to WorkManager, which holds the job until CONNECTED.
            SyncWorker.syncNow(appContext)
        }
    }

    suspend fun test(url: String, key: String): ConnectionTest = withContext(Dispatchers.IO) {
        SupabaseRest(url.trim().trimEnd('/'), key.trim()).test()
    }

    /** Save the connection, arm periodic background sync, and sync once right now.
     *  Connecting a cloud DB enables PUSH by default so locally-created rows actually
     *  upload — pull-only was leaving customers/sales stuck at pendingSync=1 forever.
     *  The user-facing toggle can still turn it back off. */
    suspend fun connect(url: String, key: String): SyncOutcome {
        config.saveConnection(url, key)
        config.setPushEnabled(true)
        SyncWorker.schedulePeriodic(appContext)
        return runNow()
    }

    suspend fun disconnect() {
        SyncWorker.cancelPeriodic(appContext)
        config.clearConnection()
        _status.value = SyncStatus.Idle
    }

    /** Foreground sync (the "Sync now" button and on-connect), with live status. */
    suspend fun runNow(): SyncOutcome {
        _status.value = SyncStatus.Syncing
        val outcome = engine.sync()
        _status.value = when (outcome) {
            is SyncOutcome.Success ->
                SyncStatus.Done(outcome.pushed, outcome.pulled, System.currentTimeMillis(), outcome.pushErrors)
            is SyncOutcome.Failed -> SyncStatus.Error(outcome.message)
            SyncOutcome.NotConfigured -> SyncStatus.Idle
        }
        return outcome
    }

    /** Re-arm periodic sync on app start if a connection is already saved. */
    suspend fun ensureScheduled() {
        if (config.isConfigured()) SyncWorker.schedulePeriodic(appContext)
    }

    private companion object {
        /** Coalescing window: 5 rapid edits within this land as one sync. */
        const val DEBOUNCE_MS = 2_500L
    }
}
