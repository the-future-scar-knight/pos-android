package com.portionspot.pos

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.portionspot.pos.auth.AuthGate
import com.portionspot.pos.notify.Notifier
import com.portionspot.pos.ui.AdminRoot
import com.portionspot.pos.ui.AppRoot
import com.portionspot.pos.ui.CrashReportScreen
import com.portionspot.pos.ui.CrashReporter
import com.portionspot.pos.ui.PosTheme
import com.portionspot.pos.ui.PosViewModel

class MainActivity : ComponentActivity() {

    // Which screen a notification asked us to open (null = normal launch). Read in
    // onCreate and updated by onNewIntent so a tap on the mobile-money notification
    // deep-links to the Mobile Money screen whether the app was cold or already open.
    private val openTarget = mutableStateOf<String?>(null)

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
        openTarget.value = intent.getStringExtra(Notifier.EXTRA_OPEN)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openTarget.value = intent?.getStringExtra(Notifier.EXTRA_OPEN)

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
                // Everything sits behind auth: login (online), PIN unlock (offline),
                // then the POS. Roles ride in on PosUser for later admin screens.
                AuthGate(container.authManager) { user ->
                    // Push the signed-in cashier into the ViewModel so financial
                    // writes (refunds now; the rest as Phase 2 continues) are attributed.
                    LaunchedEffect(user.id) { vm.setCurrentCashier(user.id, user.displayName, user.isAdmin) }
                    val open by openTarget
                    val wantMobileMoney = open == Notifier.OPEN_MOBILE_MONEY
                    val wantAdminAlerts = open == Notifier.OPEN_ADMIN_ALERTS
                    // Admins land in the admin shell (§9) but can drop into the cashier
                    // POS to make a sale, then jump back. Cashiers only ever see the POS.
                    if (user.isAdmin) {
                        // A mobile-money notification opens the cashier POS (where the
                        // Mobile Money screen lives), then falls back to the admin shell.
                        var cashierMode by remember(user.id) { mutableStateOf(false) }
                        LaunchedEffect(wantMobileMoney) { if (wantMobileMoney) cashierMode = true }
                        if (cashierMode) {
                            AppRoot(
                                vm,
                                onExitToAdmin = { cashierMode = false },
                                openMobileMoney = wantMobileMoney,
                                onOpenConsumed = { openTarget.value = null }
                            )
                        } else {
                            AdminRoot(
                                vm,
                                onExitToCashier = { cashierMode = true },
                                openAlerts = wantAdminAlerts,
                                onOpenConsumed = { openTarget.value = null }
                            )
                        }
                    } else {
                        AppRoot(
                            vm,
                            openMobileMoney = wantMobileMoney,
                            onOpenConsumed = { openTarget.value = null }
                        )
                    }
                }
            }
        }
    }
}
