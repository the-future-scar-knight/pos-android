package com.portionspot.pos.sync

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    suspend fun connection(): Connection? = config.connection()
    suspend fun isConfigured(): Boolean = config.isConfigured()
    suspend fun lastSyncAt(): Long? = config.lastSyncAt()

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
}
