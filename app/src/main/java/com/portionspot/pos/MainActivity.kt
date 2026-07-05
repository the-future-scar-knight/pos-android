package com.portionspot.pos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.portionspot.pos.auth.AuthGate
import com.portionspot.pos.ui.AppRoot
import com.portionspot.pos.ui.CrashReportScreen
import com.portionspot.pos.ui.CrashReporter
import com.portionspot.pos.ui.PosTheme
import com.portionspot.pos.ui.PosViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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

        val container = (application as PosApp).container
        setContent {
            val vm: PosViewModel = viewModel(
                factory = PosViewModel.factory(container.repository, container.syncManager)
            )
            val theme by vm.themeChoice.collectAsState()
            PosTheme(theme = theme) {
                // Everything sits behind auth: login (online), PIN unlock (offline),
                // then the POS. Roles ride in on PosUser for later admin screens.
                AuthGate(container.authManager) { _ ->
                    AppRoot(vm)
                }
            }
        }
    }
}
