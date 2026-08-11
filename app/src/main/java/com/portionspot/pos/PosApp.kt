package com.portionspot.pos

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.portionspot.pos.auth.AuthManager
import com.portionspot.pos.auth.StaffDirectory
import com.portionspot.pos.data.PosDatabase
import com.portionspot.pos.data.PosRepository
import com.portionspot.pos.notify.AdminNotificationWorker
import com.portionspot.pos.notify.Notifier
import com.portionspot.pos.notify.RecurringExpenseWorker
import com.portionspot.pos.sync.PosSyncEngine
import com.portionspot.pos.ui.CrashReporter
import com.portionspot.pos.sync.SyncConfig
import com.portionspot.pos.sync.SyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Manual dependency container. We deliberately skip Hilt for now to keep the
 * build simple and fast to sync; this can be swapped for Hilt later without
 * touching call sites (everything goes through [AppContainer]).
 */
class AppContainer(app: Application) {
    private val database: PosDatabase = PosDatabase.get(app)
    val repository: PosRepository = PosRepository(database)

    // ---- Cloud sync (bring-your-own Supabase) ----
    // Declared BEFORE the auth manager on purpose: staff sign-in reads the shop's CLOUD
    // business id from here (the PIN salt is derived from it), so the directory below
    // cannot be built until this exists.
    val syncConfig: SyncConfig = SyncConfig(database.settingDao())

    // ---- Auth (staff username + PIN against the shared `staff` table) ----
    // No GoTrue, no tokens: the credential is `staff.pin_hash`, mirrored onto this device
    // by the roster pull, so a cashier whose row has synced once signs in with no network.
    val authManager: AuthManager = AuthManager(
        app,
        StaffDirectory(database.staffDao()) { syncConfig.cloudBusinessId() },
    )

    val syncEngine: PosSyncEngine = PosSyncEngine(
        database.businessDao(), database.itemDao(), database.itemAttributeDao(),
        database.saleDao(),
        database.salePaymentDao(), database.customerDao(), database.creditDao(),
        database.refundDao(), database.mobileMoneyDao(),
        database.expenseDao(), database.cashTxnDao(),
        database.supplierDao(), database.purchaseOrderDao(),
        database.notificationDao(), database.auditDao(),
        database.staffRequestDao(), database.cashSessionDao(),
        database.stockMovementDao(), database.staffDao(), syncConfig,
        accessToken = authManager::accessTokenOrNull,
        ensureFreshToken = { authManager.refreshIfNeeded() }
    )
    val syncManager: SyncManager =
        SyncManager(app.applicationContext, syncConfig, syncEngine, database.syncArmDao())

    init {
        // A new/updated admin alert (or a read-state change) nudges a debounced sync so
        // it lands on the OTHER phones in seconds, not on the ~15-minute worker cycle.
        repository.onSyncWorthyChange = { reason -> syncManager.requestSync(reason) }
        // The saved cloud connection, for the ONE thing auth still uses the network for:
        // re-reading the signed-in member's own `staff` row so a revocation reaches their
        // phone (see AuthManager.refreshCurrentPermissions). Sign-in itself never needs it.
        // Null until the owner has connected a database.
        authManager.connectionProvider = { syncConfig.connection() }
    }
}

class PosApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Record any fatal crash so the next launch can show it (no adb needed).
        CrashReporter.install(this)
        // Mobile-money notification channel (Phase 5) — create early so the SMS
        // receiver can post the moment a payment lands, even before the UI opens.
        Notifier.ensureChannel(this)
        container = AppContainer(this)
        // If the user already connected a database, make sure periodic sync is armed.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            container.syncManager.ensureScheduled()
        }
        // Arm the admin notifications backend (§8): periodic recompute + one sweep now
        // so escalations (unverified payments, owed refunds, aging debts, low stock,
        // unsynced device) fire even while the admin isn't looking at the app.
        AdminNotificationWorker.schedule(this)
        AdminNotificationWorker.runNow(this)
        // Auto-post due recurring expenses (§9.3): periodic + one sweep now so a charge
        // that came due while the app was closed lands on next launch.
        RecurringExpenseWorker.schedule(this)
        RecurringExpenseWorker.runNow(this)

        // ---- Adaptive pull -------------------------------------------------
        // While the app is on screen, poll for cloud changes on a short cadence so a
        // second phone (the owner's admin handset) sees a cashier's sale/alert within
        // about a minute. The moment the app goes to background the loop stops and the
        // existing ~15-minute periodic SyncWorker is the only cadence again — no
        // websockets, no idle battery/data drain.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                container.syncManager.setForeground(true)
                // Refresh the signed-in user's own permission grants each time the app
                // comes to the foreground, so an admin's change reaches the device.
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    container.authManager.refreshCurrentPermissions()
                }
            }

            override fun onStop(owner: LifecycleOwner) {
                container.syncManager.setForeground(false)
            }
        })
    }
}
