package com.portionspot.pos.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.portionspot.pos.ui.LocalPosTokens
import com.portionspot.pos.ui.PosField
import kotlinx.coroutines.launch

/**
 * Wraps the whole app in cloud (team) mode: either the staff sign-in screen or the POS.
 *
 * There is no third branch and that is deliberate. The old gate had six states — logged
 * out, add-account, account picker, PIN unlock, PIN setup, active — because a GoTrue login
 * and an offline unlock PIN were two different credentials that had to be reconciled. A
 * staff PIN is one credential checked against one table, so there is one screen in front
 * of the till and one way through it.
 *
 * [onExitToLocal] is the back arrow, and it must be NULL on any device where a cloud
 * account has ever signed in. Local mode runs as a hard-coded admin against the SAME
 * database, so an arrow here is a route from "signed-out cashier" to "owner" in three
 * taps. MainActivity decides; see `PosViewModel.cloudProvisioned`.
 */
@Composable
fun AuthGate(
    auth: AuthManager,
    onExitToLocal: (() -> Unit)? = null,
    content: @Composable (PosUser) -> Unit,
) {
    val state by auth.state.collectAsState()

    when (val s = state) {
        is AuthState.Loading -> Box(Modifier.fillMaxSize()) {}
        is AuthState.SignIn -> StaffSignInScreen(auth, onBack = onExitToLocal)
        is AuthState.Active -> Box(Modifier.fillMaxSize()) { content(s.user) }
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
                            contentDescription = "Back",
                            tint = t.inkSecondary,
                        )
                    }
                }
            }
            // Scroll container fills the space. The scrolling column is forced to be at
            // LEAST the viewport tall (heightIn min = maxHeight) and centers its content:
            // when the form is short it sits centered (the original look); when the
            // keyboard is up and the content is taller than the shrunken viewport, the
            // column grows past the viewport so Center becomes a top-anchored layout and
            // the whole thing scrolls FROM THE TOP — the title/logo stay reachable instead
            // of being pushed off-screen.
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .then(if (onBack == null) Modifier.safeDrawingPadding() else Modifier)
                    .imePadding()
            ) {
                val viewportHeight = maxHeight
                Box(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = viewportHeight),
                        contentAlignment = Alignment.Center,
                    ) {
                        // Padding lives on the INNER column, never on the min-height Box.
                        // Applied outside, it added 48dp on top of the viewport height, so
                        // the form was permanently 48dp scrollable even when it all fitted —
                        // and scrolling up slid the title under the top edge.
                        Column(
                            Modifier
                                .wrapContentHeight()
                                .padding(horizontal = 24.dp, vertical = 24.dp),
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

/**
 * The one screen in front of a cloud-mode till: tap your name, type your PIN.
 *
 * ── WHY THE ROSTER IS A LIST AND NOT A LOGIN FORM ────────────────────────────
 *
 * Everyone at the counter already knows who works there, so there is nothing to protect by
 * making a cashier type their own name — only four digits to get wrong on a phone keyboard
 * while a customer waits. The typed path stays available for a member whose row hasn't
 * reached this device yet under a name they can see, and for a shop with a long roster.
 *
 * ── IT NEVER TOUCHES THE NETWORK ─────────────────────────────────────────────
 *
 * Both the list and the check read the roster mirrored on this device, so a till signs its
 * cashier in through a power cut and a dead cell — which is when the shop most needs to
 * open. The one thing a phone must do online is sync ONCE, which is exactly what the empty
 * state says.
 */
@Composable
private fun StaffSignInScreen(auth: AuthManager, onBack: (() -> Unit)?) {
    val t = LocalPosTokens.current
    val scope = rememberCoroutineScope()
    val roster by auth.roster.collectAsState()
    val loading by auth.rosterLoading.collectAsState()

    // Who is being signed in. Null = the name list; a member = their PIN pad.
    var picked by remember { mutableStateOf<RosterMember?>(null) }
    // The "I'll type my username" path, for a name that isn't on this device's list.
    var typing by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { auth.loadRoster() }

    fun submit() {
        if (busy || pin.length < 4) return
        busy = true; error = null
        scope.launch {
            val member = picked
            val result =
                if (member != null) auth.signInAs(member.id, pin)
                else auth.signIn(username, pin)
            when (result) {
                is LoginResult.Ok -> Unit // the state flow moves us on
                is LoginResult.Error -> { error = result.message; pin = "" }
            }
            busy = false
        }
    }

    // ── the PIN pad, for a name already chosen (or a username already typed) ──
    if (picked != null || typing) {
        val who = picked?.displayName
        AuthScaffold(
            title = who?.let { "Hello, $it" } ?: "Sign in",
            subtitle = if (who != null) "Enter your PIN" else "Your username and PIN",
            // Back always returns to the NAME LIST, never out of the gate. The only route
            // out of cloud mode is [onBack] on the list itself, and that is null on a
            // device that has ever had a sign-in.
            onBack = {
                picked = null; typing = false; pin = ""; username = ""; error = null
            },
        ) {
            if (who == null) {
                PosField(
                    value = username,
                    onValueChange = { username = it; error = null },
                    label = "Username",
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
            }
            PinField(pin, { pin = it; error = null }, "PIN", enabled = !busy)
            ErrorText(error)
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { submit() },
                enabled = !busy && pin.length >= 4 && (who != null || username.isNotBlank()),
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
            if (picked?.hasPin == false) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "No PIN has been set for this account yet — the owner sets one from the staff list.",
                    fontSize = 12.sp,
                    color = t.inkTertiary,
                )
            }
        }
        return
    }

    // ── the name list ────────────────────────────────────────────────────────
    AuthScaffold(
        title = "Who's using this till?",
        subtitle = "Tap your name, then enter your PIN",
        onBack = onBack,
    ) {
        if (roster.isEmpty()) {
            Text(
                when {
                    loading -> "Loading staff…"
                    // The back arrow only exists on a till that has never had a sign-in —
                    // which is exactly the till that still has to be pointed at a database.
                    // Naming the route matters: the connection screen is behind this gate.
                    onBack != null ->
                        "This till hasn't loaded the staff list yet. Go back, open " +
                            "Settings → Cloud sync, connect the shop's database and sync once. " +
                            "After that everyone on the list signs in with no internet."
                    else ->
                        "This till hasn't loaded the staff list yet — it needs to sync once " +
                            "while online. After that everyone on the list signs in offline."
                },
                fontSize = 13.sp,
                color = t.inkTertiary,
            )
            Spacer(Modifier.height(16.dp))
        } else {
            roster.forEach { member ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(t.surface1)
                        .border(1.dp, t.surfaceBorder, RoundedCornerShape(12.dp))
                        .clickable { picked = member; pin = ""; error = null }
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
                            member.displayName,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = t.inkPrimary,
                        )
                        Text(
                            if (member.isAdmin) "Admin" else "Cashier",
                            fontSize = 12.sp,
                            color = t.inkTertiary,
                        )
                    }
                    // Said on the LIST, not after four digits have been typed and refused:
                    // "no PIN" is the owner's job to fix, and the cashier needs to know
                    // that before they start doubting their own memory.
                    if (!member.hasPin) {
                        Text(
                            "No PIN set",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = t.danger,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        OutlinedButton(
            onClick = { typing = true; pin = ""; error = null },
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) { Text("Sign in with a username") }

        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { auth.loadRoster() }, enabled = !loading) {
            Icon(
                Icons.Rounded.Refresh,
                contentDescription = null,
                modifier = Modifier.width(16.dp).height(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text("Refresh staff list", color = t.inkSecondary)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Your PIN is the same one you use on the web POS. This till works offline once " +
                "it has synced the staff list.",
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
