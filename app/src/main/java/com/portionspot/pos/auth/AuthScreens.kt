package com.portionspot.pos.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.portionspot.pos.ui.LocalPosTokens
import com.portionspot.pos.ui.PosField
import kotlinx.coroutines.launch

/**
 * Wraps the whole app: routes between the account picker / login / PIN setup /
 * PIN unlock and the real POS content depending on [AuthManager.state]. Also shows
 * the "session expired" banner when a refresh is rejected server-side.
 */
@Composable
fun AuthGate(
    auth: AuthManager,
    onExitToLocal: (() -> Unit)? = null,
    content: @Composable (PosUser) -> Unit,
) {
    val state by auth.state.collectAsState()
    val reloginRequired by auth.reloginRequired.collectAsState()

    when (val s = state) {
        is AuthState.Loading -> Box(Modifier.fillMaxSize()) {}
        // With no account yet, [onExitToLocal] (when cloud mode was just opted into)
        // becomes the back arrow so the user can return to using the till locally.
        is AuthState.LoggedOut -> LoginScreen(auth, onBack = onExitToLocal)
        is AuthState.AddAccount -> LoginScreen(auth, onBack = { auth.backToPicker() })
        is AuthState.Picker -> AccountPickerScreen(auth, s.accounts)
        is AuthState.Locked -> PinUnlockScreen(auth, s.account)
        is AuthState.PinSetup -> PinSetupScreen(auth)
        is AuthState.Active -> Column(Modifier.fillMaxSize()) {
            if (reloginRequired) {
                val t = LocalPosTokens.current
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(t.danger.copy(alpha = 0.12f))
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Session expired — sales keep saving on this device",
                        fontSize = 12.sp,
                        color = t.danger,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { auth.promptRelogin() }) {
                        Text("Sign in", color = t.brand.s600, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Box(Modifier.weight(1f)) { content(s.user) }
        }
    }
}

@Composable
internal fun AuthScaffold(
    title: String,
    subtitle: String,
    onBack: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val t = LocalPosTokens.current
    Box(Modifier.fillMaxSize().background(t.canvas)) {
        Column(Modifier.fillMaxSize()) {
            if (onBack != null) {
                Row(Modifier.fillMaxWidth().safeDrawingPadding().padding(horizontal = 4.dp)) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back to accounts",
                            tint = t.inkSecondary,
                        )
                    }
                }
            }
            // Scroll container fills the space; the inner column wraps its own
            // height and is centered via Arrangement.Center. On a tall screen this
            // centers without a giant white band, and when the keyboard is up the
            // symmetric vertical padding keeps content off the keyboard edge while
            // the content overflows into a graceful scroll instead of an empty void.
            Column(
                Modifier
                    .fillMaxSize()
                    .then(if (onBack == null) Modifier.safeDrawingPadding() else Modifier)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Column(
                    Modifier.wrapContentHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(t.brand.s50)
                            .padding(14.dp),
                    ) {
                        Icon(
                            Icons.Rounded.Storefront,
                            contentDescription = null,
                            tint = t.brand.s600,
                            modifier = Modifier.width(36.dp).height(36.dp),
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        title,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = t.inkPrimary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(subtitle, fontSize = 14.sp, color = t.inkSecondary)
                    Spacer(Modifier.height(24.dp))
                    content()
                }
            }
        }
    }
}

@Composable
private fun ErrorText(message: String?) {
    if (message != null) {
        val t = LocalPosTokens.current
        Spacer(Modifier.height(8.dp))
        Text(message, color = t.danger, fontSize = 14.sp)
    }
}

/** The lock screen when the device already has accounts: pick who's using it. */
@Composable
private fun AccountPickerScreen(auth: AuthManager, accounts: List<AccountSummary>) {
    val t = LocalPosTokens.current
    AuthScaffold("Who's using this device?", "Tap your name, then enter your PIN") {
        accounts.forEach { acc ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(t.surface1)
                    .border(1.dp, t.surfaceBorder, RoundedCornerShape(12.dp))
                    .clickable { auth.chooseAccount(acc.userId) }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    Icons.Rounded.AccountCircle,
                    contentDescription = null,
                    tint = t.brand.s600,
                    modifier = Modifier.width(36.dp).height(36.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        acc.displayName.ifBlank { acc.email },
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = t.inkPrimary,
                    )
                    Text(
                        if (acc.isAdmin) "Admin" else "Cashier",
                        fontSize = 12.sp,
                        color = t.inkTertiary,
                    )
                }
                if (!acc.hasPin) {
                    Text(
                        "Set PIN",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = t.brand.s600,
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = { auth.addAccount() },
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            Icon(Icons.Rounded.PersonAdd, contentDescription = null, modifier = Modifier.width(18.dp).height(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Add another account")
        }
    }
}

@Composable
private fun LoginScreen(auth: AuthManager, onBack: (() -> Unit)?) {
    val t = LocalPosTokens.current
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
        PosField(
            value = email,
            onValueChange = { email = it },
            label = "Email",
            keyboardType = KeyboardType.Email,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        PosField(
            value = password,
            onValueChange = { password = it },
            label = "Password",
            keyboardType = KeyboardType.Password,
            visualTransformation =
                if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            trailing = {
                IconButton(onClick = { showPassword = !showPassword }, modifier = Modifier.width(24.dp).height(24.dp)) {
                    Icon(
                        if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = if (showPassword) "Hide password" else "Show password",
                        tint = t.inkTertiary,
                        modifier = Modifier.width(20.dp).height(20.dp),
                    )
                }
            },
        )
        ErrorText(error)
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { submit() },
            enabled = !busy && email.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = t.brand.s600, contentColor = t.inkOnBrand,
            ),
        ) {
            if (busy) CircularProgressIndicator(
                Modifier.width(22.dp).height(22.dp),
                color = t.inkOnBrand,
                strokeWidth = 2.dp,
            ) else Text("Sign in")
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Signing in needs internet once. After that this person can unlock and sell offline with a PIN.",
            fontSize = 12.sp,
            color = t.inkTertiary,
        )
    }
}

@Composable
internal fun PinField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    enabled: Boolean = true,
) {
    PosField(
        value = value,
        onValueChange = { new -> if (new.length <= 6 && new.all { it.isDigit() }) onChange(new) },
        label = label,
        keyboardType = KeyboardType.NumberPassword,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PinSetupScreen(auth: AuthManager) {
    val t = LocalPosTokens.current
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AuthScaffold("Set a PIN", "Unlock quickly on this device, even offline") {
        PinField(pin, { pin = it }, "PIN (4-6 digits)", enabled = !busy)
        Spacer(Modifier.height(12.dp))
        PinField(confirm, { confirm = it }, "Confirm PIN", enabled = !busy)
        ErrorText(error)
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                when {
                    pin.length < 4 -> error = "PIN must be at least 4 digits"
                    pin != confirm -> error = "PINs don't match"
                    else -> {
                        busy = true; error = null
                        // Hashing runs on Dispatchers.IO inside setPin, so the UI
                        // stays responsive while the PIN is derived.
                        scope.launch { auth.setPin(pin) }
                    }
                }
            },
            enabled = !busy && pin.isNotEmpty() && confirm.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = t.brand.s600, contentColor = t.inkOnBrand,
            ),
        ) { Text(if (busy) "Saving…" else "Save PIN") }
        TextButton(onClick = { auth.skipPin() }, enabled = !busy) {
            Text("Skip for now", color = t.inkSecondary)
        }
    }
}

@Composable
private fun PinUnlockScreen(auth: AuthManager, account: AccountSummary) {
    val t = LocalPosTokens.current
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
        onBack = { auth.backToPicker() },
    ) {
        Icon(
            Icons.Rounded.Lock,
            contentDescription = null,
            tint = t.inkTertiary,
        )
        Spacer(Modifier.height(12.dp))
        PinField(pin, { pin = it }, "PIN", enabled = !busy)
        ErrorText(error)
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { submit() },
            enabled = !busy && pin.length >= 4,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = t.brand.s600, contentColor = t.inkOnBrand,
            ),
        ) {
            if (busy) CircularProgressIndicator(
                Modifier.width(22.dp).height(22.dp),
                color = t.inkOnBrand,
                strokeWidth = 2.dp,
            ) else Text("Unlock")
        }
        TextButton(onClick = { auth.addAccount() }) {
            Text("Sign in with password instead", color = t.brand.s600)
        }
    }
}
