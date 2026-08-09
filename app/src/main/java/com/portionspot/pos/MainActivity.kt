package com.portionspot.pos

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.portionspot.pos.auth.AuthGate
import com.portionspot.pos.notify.Notifier
import com.portionspot.pos.ui.AdminRoot
import com.portionspot.pos.ui.AppMode
import com.portionspot.pos.ui.AppRoot
import com.portionspot.pos.ui.CrashReportScreen
import com.portionspot.pos.ui.CrashReporter
import com.portionspot.pos.ui.LocalGate
import com.portionspot.pos.ui.PosDeepLink
import com.portionspot.pos.ui.PosTheme
import com.portionspot.pos.ui.PosViewModel

/** Attribution id for sales made in local (no-cloud) mode — a single device owner. */
private const val LOCAL_OWNER_ID = "local-owner"

class MainActivity : ComponentActivity() {

    // Which screen a notification asked us to open (null = normal launch). Read in
    // onCreate and updated by onNewIntent so a tap on the mobile-money notification
    // deep-links to the Mobile Money screen whether the app was cold or already open.
    private val openTarget = mutableStateOf<String?>(null)

    // WHICH RECORD that notification was about ("Low stock: brake pads" → that item).
    // Every alert already carries refType/refId (NotificationEngine stamps them); Notifier
    // now passes them through, and this is where a tap turns back into a screen + a row.
    // Also settable from inside the app: tapping a card in the admin Alerts feed routes
    // through here so in-app and system taps land in exactly the same place.
    private val openRecord = mutableStateOf<PosDeepLink?>(null)

    /**
     * Pin the app's font scale to 1.0 regardless of the device's system Font-size
     * setting. A POS must look identical on every till; on the Sunmi (Android 6 / API
     * 23, where only Font size — not Display size — can be enlarged) an operator's
     * "Large" font blew the search bar, chips and cards up so only a couple of products
     * were visible (prompt §2). Ignoring the device font setting keeps the layout tight
     * and consistent with the phone it was designed on.
     */
    override fun attachBaseContext(newBase: Context) {
        val config = Configuration(newBase.resources.configuration)
        config.fontScale = 1.0f
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readOpenExtras(intent)
    }

    /** Pull the deep-link extras off a launch/tap intent. Split out so the cold-start
     *  path (onCreate) and the already-running path (onNewIntent) can never drift. */
    private fun readOpenExtras(intent: Intent?) {
        openTarget.value = intent?.getStringExtra(Notifier.EXTRA_OPEN)
        val refType = intent?.getStringExtra(Notifier.EXTRA_REF_TYPE)
        openRecord.value =
            if (refType.isNullOrBlank()) null
            else PosDeepLink(refType, intent?.getStringExtra(Notifier.EXTRA_REF_ID))
    }

    /** A deep link is spent once the screen has it — clearing both keys stops the
     *  LaunchedEffects re-firing (and re-opening a sheet the owner just closed). */
    private fun consumeOpen() {
        openTarget.value = null
        openRecord.value = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        readOpenExtras(intent)

        // If the previous run crashed, show the captured report instead of the app
        // so the tester can read/share it (no USB/logcat required). "Continue"
        // clears it and retries.
        val crash = CrashReporter.read(this)
        if (crash != null) {
            setContent {
                MaterialTheme {
                    CrashReportScreen(
                        text = crash,
                        onShare = { CrashReporter.share(this, crash) },
                        onContinue = { CrashReporter.clear(this); recreate() }
                    )
                }
            }
            return
        }

        // Ask once to be exempt from battery optimization so payment SMS + admin alerts
        // keep arriving while the app is backgrounded/closed (no-ops if already exempt).
        com.portionspot.pos.device.BackgroundReliability.requestExemptionOnce(this)

        val container = (application as PosApp).container
        setContent {
            val vm: PosViewModel = viewModel(
                factory = PosViewModel.factory(
                    container.repository, container.syncManager, container.authManager
                )
            )
            val theme by vm.themeChoice.collectAsState()
            PosTheme(theme = theme) {
                val bootLoaded by vm.bootLoaded.collectAsState()
                val appMode by vm.appMode.collectAsState()

                when {
                    // Wait for the persisted mode/onboarding flags so a returning user
                    // never sees the wrong screen flash before their choice loads.
                    !bootLoaded -> Box(Modifier.fillMaxSize()) {}

                    // ── Local (phone-only) mode: the default. Full app + admin panel,
                    // no cloud account, an optional device PIN. POS is home; the admin
                    // panel is one tap away. Sales attribute to a single local owner.
                    appMode == AppMode.Local -> LocalGate(vm) {
                        LaunchedEffect(Unit) { vm.setCurrentCashier(LOCAL_OWNER_ID, "Owner", isAdmin = true) }
                        val open by openTarget
                        val record by openRecord
                        val wantMobileMoney =
                            open == Notifier.OPEN_MOBILE_MONEY || record?.refType == "mm_receipt"
                        // A record beats the feed: if the alert names a row, open the row.
                        val wantAdminAlerts = open == Notifier.OPEN_ADMIN_ALERTS && record == null
                        // Start in the POS; the top-bar admin button flips to the panel.
                        var adminMode by rememberSaveable { mutableStateOf(false) }
                        LaunchedEffect(wantAdminAlerts) { if (wantAdminAlerts) adminMode = true }
                        // Records live on the POS-shell screens (Inventory, Receipts,
                        // Customers, …), so a record link always drops out of the panel.
                        LaunchedEffect(record, wantMobileMoney) {
                            if (record != null || wantMobileMoney) adminMode = false
                        }
                        if (adminMode) {
                            AdminRoot(
                                vm,
                                onExitToCashier = { adminMode = false },
                                openAlerts = wantAdminAlerts,
                                onOpenRecord = { openRecord.value = it },
                                onOpenConsumed = { consumeOpen() }
                            )
                        } else {
                            AppRoot(
                                vm,
                                onExitToAdmin = { adminMode = true },
                                openMobileMoney = wantMobileMoney,
                                deepLink = record,
                                onOpenConsumed = { consumeOpen() }
                            )
                        }
                    }

                    // ── Cloud (team) mode: the original behaviour, now opt-in. Login,
                    // per-cashier PIN, staff management, attribution, sync. Backing out
                    // of login (no account yet) returns to local mode.
                    else -> AuthGate(container.authManager, onExitToLocal = { vm.useLocalMode() }) { user ->
                        // ★ user.role is a key, not just a passenger. A role-only demotion
                        // (admin -> cashier with an identical permissions map) re-emits
                        // AuthState and recomposes this branch into the cashier shell, but
                        // without the role in the key list the effect never restarts — so
                        // _currentIsAdmin stayed true and vm.can(...) kept waving the
                        // demoted admin through every capability check in the handlers.
                        LaunchedEffect(user.id, user.role, user.permissions) {
                            vm.setCurrentCashier(user.id, user.displayName, user.isAdmin, user.permissions)
                        }
                        val open by openTarget
                        val record by openRecord
                        val wantMobileMoney =
                            open == Notifier.OPEN_MOBILE_MONEY || record?.refType == "mm_receipt"
                        val wantAdminAlerts = open == Notifier.OPEN_ADMIN_ALERTS && record == null
                        // Admins land in the admin shell but can drop into the cashier
                        // POS to make a sale, then jump back. Cashiers only ever see the POS.
                        if (user.isAdmin) {
                            var cashierMode by remember(user.id) { mutableStateOf(false) }
                            // Same rule as local mode: the records an alert points at are
                            // POS-shell screens, so a record link switches into the POS.
                            LaunchedEffect(record, wantMobileMoney) {
                                if (record != null || wantMobileMoney) cashierMode = true
                            }
                            if (cashierMode) {
                                AppRoot(
                                    vm,
                                    onExitToAdmin = { cashierMode = false },
                                    openMobileMoney = wantMobileMoney,
                                    deepLink = record,
                                    onOpenConsumed = { consumeOpen() }
                                )
                            } else {
                                AdminRoot(
                                    vm,
                                    onExitToCashier = { cashierMode = true },
                                    openAlerts = wantAdminAlerts,
                                    onOpenRecord = { openRecord.value = it },
                                    onOpenConsumed = { consumeOpen() }
                                )
                            }
                        } else {
                            // A cashier never reaches the admin shell, so an alert aimed at
                            // them ("all"/"cashier" audience) resolves to the POS screen for
                            // its record — or, if it names none, to a plain launch.
                            AppRoot(
                                vm,
                                openMobileMoney = wantMobileMoney,
                                deepLink = record,
                                onOpenConsumed = { consumeOpen() }
                            )
                        }
                    }
                }
            }
        }
    }
}
