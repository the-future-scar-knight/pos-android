package com.portionspot.pos.sync

import android.content.Context
import com.portionspot.pos.PosApp
import com.portionspot.pos.device.ConnectivityObserver
import com.portionspot.pos.notify.AdminNotificationWorker
import com.portionspot.pos.notify.Notifier
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

    /** The foreground adaptive-poll loop (null = not polling, i.e. app backgrounded). */
    private var pollJob: Job? = null

    /** When a pass last STARTED (any trigger: debounce, poll, pull-on-open, manual).
     *  Every automatic trigger coalesces against this so push-on-change and the poll
     *  loop can never double-fire within [MIN_GAP_MS]. */
    @Volatile
    private var lastAttemptAt: Long = 0L

    /** Epoch-ms deadline until which the foreground loop polls at [HOT_POLL_MS] instead of
     *  [POLL_MS]. Set by [goHot] while a staff-request round-trip is in flight so the
     *  admin's decision reaches the cashier (and vice-versa) in seconds, not a poll cycle.
     *  0 (or past) = normal cadence. */
    @Volatile
    private var hotUntil: Long = 0L

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
        lastAttemptAt = System.currentTimeMillis()
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

    // ── adaptive pull (foreground polling) ──────────────────────────────────
    /**
     * Foreground/background switch, driven by `ProcessLifecycleOwner` in [com.portionspot.pos.PosApp].
     *
     * FOREGROUND: poll roughly every [POLL_MS] while online, so a second phone (the
     * admin's) sees a cashier's activity within about a minute instead of waiting for
     * the ~15-minute WorkManager cycle. A pull with no changes is a tiny conditional
     * request — every table carries an `updated_at=gt.<cursor>` — so this stays cheap
     * on mobile data. No websockets/Realtime: no persistent connection, no idle drain.
     *
     * BACKGROUND: the loop stops entirely and the existing periodic [SyncWorker]
     * (unchanged, ~15 min, CONNECTED-constrained) is the only cadence.
     */
    fun setForeground(foreground: Boolean) {
        synchronized(this) {
            if (!foreground) {
                pollJob?.cancel()
                pollJob = null
                return
            }
            if (pollJob?.isActive == true) return
            startPollLoop()
        }
    }

    /** (Re)start the foreground poll loop. The per-iteration delay honours [hotUntil], so
     *  entering hot mode ([goHot]) speeds the loop up mid-flight and it decays back to the
     *  steady cadence once the window lapses. Always called under `synchronized(this)`. */
    private fun startPollLoop() {
        pollJob?.cancel()
        pollJob = scope.launch {
            // Come back to the app (or just went hot) → catch up immediately (coalesced),
            // then settle into whichever cadence applies.
            pollOnce("foreground")
            while (true) {
                val hot = System.currentTimeMillis() < hotUntil
                delay(if (hot) HOT_POLL_MS else POLL_MS)
                pollOnce(if (System.currentTimeMillis() < hotUntil) "hot-poll" else "poll")
            }
        }
    }

    /**
     * Enter "hot-poll" mode for [windowMs]: while a staff-request I just submitted (cashier)
     * or decided (admin) is in flight, poll every [HOT_POLL_MS] instead of [POLL_MS] so the
     * round-trip feels live. Cancels the current long sleep and restarts the loop so the
     * faster cadence takes effect at once — but ONLY when foregrounded (a null [pollJob]
     * means backgrounded; the window is still remembered and honoured when the loop resumes).
     * Coalesced against [HOT_MIN_GAP_MS] in [pollOnce] so it can't double-fire.
     */
    fun goHot(windowMs: Long = HOT_WINDOW_MS) {
        synchronized(this) {
            hotUntil = System.currentTimeMillis() + windowMs
            if (pollJob?.isActive == true) startPollLoop()
        }
    }

    /**
     * Pull NOW for an admin-facing screen that just opened (admin dashboard, alerts):
     * opening it should show current data rather than whatever the last cycle left.
     * Coalesced by [MIN_GAP_MS], so flipping between admin tabs costs nothing.
     */
    fun requestPullNow(reason: String = "screen-open") {
        scope.launch { pollOnce(reason) }
    }

    /**
     * One adaptive pass: skipped when unconfigured, offline, or when a pass already ran
     * inside [MIN_GAP_MS] (the coalescing rule that keeps push-on-change and the poll
     * loop from doubling up). Runs quietly — a transient poll failure must not paint the
     * Settings screen red, so only successes update the visible status.
     */
    private suspend fun pollOnce(reason: String) {
        if (!config.isConfigured()) return
        val now = System.currentTimeMillis()
        // In a hot window the gate loosens to [HOT_MIN_GAP_MS] so the ~6s cadence isn't
        // swallowed by the normal 20s coalescer, while still blocking a true double-fire.
        val minGap = if (now < hotUntil) HOT_MIN_GAP_MS else MIN_GAP_MS
        if (now - lastAttemptAt < minGap) return
        if (!ConnectivityObserver.currentlyOnline(appContext)) return
        lastAttemptAt = now
        val outcome = engine.sync()
        if (outcome is SyncOutcome.Success) {
            _status.value = SyncStatus.Done(
                outcome.pushed, outcome.pulled, System.currentTimeMillis(), outcome.pushErrors
            )
            afterPull(outcome.pulled)
        }
    }

    /**
     * After a foreground pass that PULLED rows, deliver cross-device heads-ups (BUG A) and
     * recompute engine conditions off the freshly-synced data. A notification row that
     * arrived here — most importantly an event alert like a cashier's expense submission,
     * which the sweep never re-derives — otherwise sits silently in the feed and never
     * buzzes. We fire directly here (this path owns [appContext] for [Notifier]) for
     * whichever rows match THIS device's role, then kick [AdminNotificationWorker.runNow]
     * so engine conditions recompute within the poll cadence instead of the 30-min sweep.
     * runNow coalesces via unique REPLACE work, so the extra kick can't pile up.
     */
    private suspend fun afterPull(pulled: Int) {
        if (pulled <= 0) return
        val app = appContext.applicationContext as? PosApp ?: return
        val isAdmin = app.container.authManager.isDeviceAdmin()
        runCatching {
            app.container.repository.fireUnpushedHeadsUps(isAdmin)
                .forEach { Notifier.notifyAlert(appContext, it) }
        }
        AdminNotificationWorker.runNow(appContext)
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
        lastAttemptAt = System.currentTimeMillis()
        _status.value = SyncStatus.Syncing
        val outcome = engine.sync()
        _status.value = when (outcome) {
            is SyncOutcome.Success ->
                SyncStatus.Done(outcome.pushed, outcome.pulled, System.currentTimeMillis(), outcome.pushErrors)
            is SyncOutcome.Failed -> SyncStatus.Error(outcome.message)
            SyncOutcome.NotConfigured -> SyncStatus.Idle
        }
        if (outcome is SyncOutcome.Success) afterPull(outcome.pulled)
        return outcome
    }

    /** Re-arm periodic sync on app start if a connection is already saved. */
    suspend fun ensureScheduled() {
        if (config.isConfigured()) SyncWorker.schedulePeriodic(appContext)
    }

    private companion object {
        /** Coalescing window: 5 rapid edits within this land as one sync. */
        const val DEBOUNCE_MS = 2_500L

        /** Foreground poll cadence (~45s): fast enough that an admin phone feels live,
         *  slow enough to stay cheap on 4G. Background stays on the ~15-min worker. */
        const val POLL_MS = 45_000L

        /** Minimum gap between any two automatic passes. Anything triggered inside this
         *  window is dropped, so push-on-change + poll + pull-on-open never stack. */
        const val MIN_GAP_MS = 20_000L

        /** Hot-poll cadence (~6s) while a staff-request round-trip is in flight. */
        const val HOT_POLL_MS = 6_000L

        /** How long a single [goHot] call stays hot (~2.5 min) before decaying to [POLL_MS]. */
        const val HOT_WINDOW_MS = 150_000L

        /** Loosened coalescing gap during a hot window — small enough to let the ~6s cadence
         *  through, large enough that two triggers in quick succession still collapse. */
        const val HOT_MIN_GAP_MS = 4_000L
    }
}
