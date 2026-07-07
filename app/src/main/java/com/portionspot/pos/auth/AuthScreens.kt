package com.portionspot.pos.auth

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Wraps the whole app: routes between the account picker / login / PIN setup /
 * PIN unlock and the real POS content depending on [AuthManager.state]. Also shows
 * the "session expired" banner when a refresh is rejected server-side.
 */
@Composable
fun AuthGate(auth: AuthManager, content: @Composable (PosUser) -> Unit) {
    val state by auth.state.collectAsState()
    val reloginRequired by auth.reloginRequired.collectAsState()

    when (val s = state) {
        is AuthState.Loading -> Box(Modifier.fillMaxSize()) {}
        is AuthState.LoggedOut -> LoginScreen(auth, onBack = null)
        is AuthState.AddAccount -> LoginScreen(auth, onBack = { auth.backToPicker() })
        is AuthState.Picker -> AccountPickerScreen(auth, s.accounts)
        is AuthState.Locked -> PinUnlockScreen(auth, s.account)
        is AuthState.PinSetup -> PinSetupScreen(auth)
        is AuthState.Active -> Column(Modifier.fillMaxSize()) {
            if (reloginRequired) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Session expired — sales keep saving on this device",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { auth.promptRelogin() }) { Text("Sign in") }
                    }
                }
            }
            Box(Modifier.weight(1f)) { content(s.user) }
        }
    }
}

@Composable
private fun AuthScaffold(
    title: String,
    subtitle: String,
    onBack: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            if (onBack != null) {
                Row(Modifier.fillMaxWidth().safeDrawingPadding().padding(horizontal = 4.dp)) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back to accounts")
                    }
                }
            }
            Column(
                Modifier
                    .fillMaxSize()
                    .then(if (onBack == null) Modifier.safeDrawingPadding() else Modifier)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Rounded.Storefront,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(48.dp).height(48.dp)
                )
                Spacer(Modifier.height(12.dp))
                Text(title, style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground)
                Spacer(Modifier.height(4.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(24.dp))
                content()
            }
        }
    }
}

@Composable
private fun ErrorText(message: String?) {
    if (message != null) {
        Spacer(Modifier.height(8.dp))
        Text(
            message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/** The lock screen when the device already has accounts: pick who's using it. */
@Composable
private fun AccountPickerScreen(auth: AuthManager, accounts: List<AccountSummary>) {
    AuthScaffold("Who's using this device?", "Tap your name, then enter your PIN") {
        accounts.forEach { acc ->
            Card(
                Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { auth.chooseAccount(acc.userId) },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Rounded.AccountCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(36.dp).height(36.dp)
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            acc.displayName.ifBlank { acc.email },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            if (acc.isAdmin) "Admin" else "Cashier",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (!acc.hasPin) {
                        Text(
                            "Set PIN",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = { auth.addAccount() },
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            Icon(Icons.Rounded.PersonAdd, contentDescription = null, modifier = Modifier.width(18.dp).height(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Add another account")
        }
    }
}

@Composable
private fun LoginScreen(auth: AuthManager, onBack: (() -> Unit)?) {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit() {
        if (busy || email.isBlank() || password.isBlank()) return
        busy = true; error = null
        scope.launch {
            when (val r = auth.login(email, password)) {
                is LoginResult.Ok -> Unit // state flow moves us on
                is LoginResult.Error -> error = r.message
            }
            busy = false
        }
    }

    AuthScaffold("PortionSpot POS", "Sign in to start selling", onBack = onBack) {
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            enabled = !busy,
            visualTransformation =
                if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { showPassword = !showPassword }) {
                    Icon(
                        if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = if (showPassword) "Hide password" else "Show password"
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        ErrorText(error)
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { submit() },
            enabled = !busy && email.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            if (busy) CircularProgressIndicator(
                Modifier.width(22.dp).height(22.dp),
                color = MaterialTheme.colorScheme.onPrimary,
                strokeWidth = 2.dp
            ) else Text("Sign in")
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Signing in needs internet once. After that this person can unlock and sell offline with a PIN.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PinField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { new -> if (new.length <= 6 && new.all { it.isDigit() }) onChange(new) },
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun PinSetupScreen(auth: AuthManager) {
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AuthScaffold("Set a PIN", "Unlock quickly on this device, even offline") {
        PinField(pin, { pin = it }, "PIN (4-6 digits)")
        Spacer(Modifier.height(12.dp))
        PinField(confirm, { confirm = it }, "Confirm PIN")
        ErrorText(error)
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                when {
                    pin.length < 4 -> error = "PIN must be at least 4 digits"
                    pin != confirm -> error = "PINs don't match"
                    else -> auth.setPin(pin)
                }
            },
            enabled = pin.isNotEmpty() && confirm.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) { Text("Save PIN") }
        TextButton(onClick = { auth.skipPin() }) { Text("Skip for now") }
    }
}

@Composable
private fun PinUnlockScreen(auth: AuthManager, account: AccountSummary) {
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit() {
        if (busy || pin.length < 4) return
        busy = true; error = null
        scope.launch {
            when (val r = auth.unlockWithPin(pin)) {
                is LoginResult.Ok -> Unit
                is LoginResult.Error -> { error = r.message; pin = "" }
            }
            busy = false
        }
    }

    AuthScaffold(
        "Welcome back, ${account.displayName.ifBlank { account.email }}",
        "Enter your PIN to unlock",
        onBack = { auth.backToPicker() }
    ) {
        Icon(
            Icons.Rounded.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        PinField(pin, { pin = it }, "PIN", enabled = !busy)
        ErrorText(error)
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { submit() },
            enabled = !busy && pin.length >= 4,
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            if (busy) CircularProgressIndicator(
                Modifier.width(22.dp).height(22.dp),
                color = MaterialTheme.colorScheme.onPrimary,
                strokeWidth = 2.dp
            ) else Text("Unlock")
        }
        TextButton(onClick = { auth.addAccount() }) { Text("Sign in with password instead") }
    }
}
