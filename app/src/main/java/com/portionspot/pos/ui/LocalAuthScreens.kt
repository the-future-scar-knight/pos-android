package com.portionspot.pos.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.portionspot.pos.auth.AuthScaffold
import com.portionspot.pos.auth.PinField
import kotlinx.coroutines.launch

/**
 * The gate for LOCAL (phone-only, no-cloud) mode. Unlike [com.portionspot.pos.auth.AuthGate]
 * — which requires a Supabase login — this lets a fresh install straight into a fully
 * usable till. It only ever interrupts for:
 *   1. the one-time first-run choice (set a PIN, or keep the till open), and
 *   2. the local PIN on every cold start, IF one was set.
 *
 * "Every cold start" is honoured by holding [unlocked] in [rememberSaveable]: a rotation
 * keeps it (no re-prompt), but process death (a genuine cold start) resets it to false.
 */
@Composable
fun LocalGate(vm: PosViewModel, content: @Composable () -> Unit) {
    val onboarded by vm.onboarded.collectAsState()
    val hasPin by vm.hasLocalPin.collectAsState()
    var unlocked by rememberSaveable { mutableStateOf(false) }

    when {
        !onboarded -> WelcomeScreen(vm, onProceed = { unlocked = true })
        hasPin && !unlocked -> LocalLockScreen(vm, onUnlock = { unlocked = true })
        else -> content()
    }
}

/** First-run: choose whether to protect the till with a PIN or leave it open. */
@Composable
private fun WelcomeScreen(vm: PosViewModel, onProceed: () -> Unit) {
    // false = the choice screen; true = the "set a PIN" form.
    var settingPin by remember { mutableStateOf(false) }

    if (!settingPin) {
        AuthScaffold("Welcome", "How do you want to secure this till?") {
            Button(
                onClick = { settingPin = true },
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) { Text("Set up a PIN") }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { vm.completeOnboarding(); onProceed() },
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) { Text("Keep it open") }
            Spacer(Modifier.height(12.dp))
            Text(
                "A PIN is asked each time you open the app. You can add, change or remove it " +
                    "later under Settings. This device works fully offline — no account needed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        PinSetupForm(
            title = "Set a PIN",
            subtitle = "You'll enter this each time the app opens",
            saveLabel = "Save PIN",
            onBack = { settingPin = false },
            onSave = { pin -> vm.setLocalPin(pin) { vm.completeOnboarding(); onProceed() } }
        )
    }
}

/** The lock screen shown on every cold start when a local PIN is set. */
@Composable
private fun LocalLockScreen(vm: PosViewModel, onUnlock: () -> Unit) {
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit() {
        if (busy || pin.length < 4) return
        busy = true; error = null
        scope.launch {
            if (vm.verifyLocalPin(pin)) {
                onUnlock()
            } else {
                error = "Wrong PIN — try again"
                pin = ""
            }
            busy = false
        }
    }

    AuthScaffold("Enter your PIN", "Unlock this till") {
        PinField(pin, { pin = it }, "PIN", enabled = !busy)
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { submit() },
            enabled = !busy && pin.length >= 4,
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            if (busy) CircularProgressIndicator(
                Modifier.size(22.dp),
                color = MaterialTheme.colorScheme.onPrimary,
                strokeWidth = 2.dp
            ) else Text("Unlock")
        }
    }
}

/**
 * Reusable "enter + confirm a PIN" form, shown full-screen during onboarding. The
 * Settings screen sets/changes the PIN through its own dialog; this covers first-run.
 */
@Composable
private fun PinSetupForm(
    title: String,
    subtitle: String,
    saveLabel: String,
    onBack: () -> Unit,
    onSave: (String) -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AuthScaffold(title, subtitle, onBack = onBack) {
        PinField(pin, { pin = it }, "PIN (4-6 digits)", enabled = !busy)
        Spacer(Modifier.height(12.dp))
        PinField(confirm, { confirm = it }, "Confirm PIN", enabled = !busy)
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                when {
                    pin.length < 4 -> error = "PIN must be at least 4 digits"
                    pin != confirm -> error = "PINs don't match"
                    else -> { busy = true; error = null; onSave(pin) }
                }
            },
            enabled = !busy && pin.isNotEmpty() && confirm.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) { Text(if (busy) "Saving…" else saveLabel) }
        TextButton(onClick = onBack, enabled = !busy) { Text("Cancel") }
    }
}
