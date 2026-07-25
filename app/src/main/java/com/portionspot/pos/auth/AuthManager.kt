package com.portionspot.pos.auth

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A signed-in POS staff member, as the UI sees them. */
data class PosUser(
    val id: String,
    val email: String,
    val role: String,
    val displayName: String,
) {
    val isAdmin: Boolean get() = role == "admin"
}

sealed class AuthState {
    /** Reading the vault on process start. */
    object Loading : AuthState()

    /** No accounts on this device — online email+password login required. */
    object LoggedOut : AuthState()

    /** Adding another account from the picker (keeps existing accounts). */
    object AddAccount : AuthState()

    /** One or more accounts provisioned: pick who's using the device. */
    data class Picker(val accounts: List<AccountSummary>) : AuthState()

    /** A picked account with a PIN set: enter it to unlock offline. */
    data class Locked(val account: AccountSummary) : AuthState()

    /** Fresh online login succeeded; offer to set an offline-unlock PIN. */
    data class PinSetup(val user: PosUser) : AuthState()

    /** Working. All POS screens live behind this state. */
    data class Active(val user: PosUser) : AuthState()
}

sealed class LoginResult {
    object Ok : LoginResult()
    data class Error(val message: String) : LoginResult()
}

/**
 * Owns the auth lifecycle: online login, encrypted multi-account session cache,
 * offline PIN unlock, account switching, token refresh, and role plumbing.
 *
 * Multi-account (prompt: a device the admin set up must let cashiers sign in with
 * their own PIN without wiping the admin): the [SessionVault] holds every account
 * provisioned on the device. The lock screen is an account picker; each account
 * unlocks with its own PIN and lands in its own role's UI. "Switch user" returns
 * to the picker without touching any cached session.
 *
 * Offline-first contract: adding an account needs internet once; after that the
 * staff member unlocks with a PIN and works fully offline. If the refresh token
 * dies while we're back online, we NEVER touch local data — we just raise
 * [reloginRequired] so the UI prompts, and unsynced records stay queued.
 */
class AuthManager(
    context: Context,
    private val api: SupabaseAuth = SupabaseAuth(),
) {
    private val vault = SessionVault(context.applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val refreshMutex = Mutex()

    private val _state = MutableStateFlow<AuthState>(AuthState.Loading)
    val state: StateFlow<AuthState> = _state

    /** True when the server refused our refresh token: prompt re-login, keep data. */
    private val _reloginRequired = MutableStateFlow(false)
    val reloginRequired: StateFlow<Boolean> = _reloginRequired

    @Volatile
    private var currentAccessToken: String? = null

    /** Which account [refreshIfNeeded] refreshes: the last one unlocked / added. */
    @Volatile
    private var activeUserId: String? = null

    init {
        scope.launch {
            withContext(Dispatchers.IO) {
                activeUserId = vault.activeUserId()
                currentAccessToken = vault.activeSession()?.accessToken
                _state.value = if (vault.hasAnyAccount()) {
                    AuthState.Picker(vault.accounts())
                } else {
                    AuthState.LoggedOut
                }
            }
        }
    }

    // ── account picker / switching ────────────────────────────────────────

    /** Go to the account picker without dropping any cached session. */
    fun switchUser() {
        _state.value = AuthState.Picker(vault.accounts())
    }

    /** From the picker: add another account via online login. */
    fun addAccount() {
        _state.value = AuthState.AddAccount
    }

    /** Back out of add-account / PIN entry to the picker. */
    fun backToPicker() {
        _state.value = AuthState.Picker(vault.accounts())
    }

    /** User tapped an account in the picker. */
    fun chooseAccount(userId: String) {
        val summary = vault.accounts().firstOrNull { it.userId == userId } ?: run {
            switchUser(); return
        }
        if (summary.hasPin) {
            _state.value = AuthState.Locked(summary)
        } else {
            // No PIN was ever set for this account (they skipped it): activate directly.
            activate(userId)
        }
    }

    private fun activate(userId: String) {
        val session = vault.sessionFor(userId) ?: run { switchUser(); return }
        vault.setActive(userId)
        activeUserId = userId
        currentAccessToken = session.accessToken
        _state.value = AuthState.Active(session.toUser())
    }

    // ── online login (first account or "add account") ─────────────────────

    suspend fun login(email: String, password: String): LoginResult =
        withContext(Dispatchers.IO) {
            when (val result = api.signIn(email.trim(), password)) {
                is AuthResult.Offline ->
                    LoginResult.Error("No connection — signing in needs internet")
                is AuthResult.Rejected -> LoginResult.Error(result.message)
                is AuthResult.Success -> {
                    val session = result.session
                    val userId = session.user?.id
                        ?: return@withContext LoginResult.Error("Server returned no user")
                    val profile = try {
                        api.fetchStaffProfile(session.accessToken, userId)
                    } catch (e: Exception) {
                        return@withContext LoginResult.Error("Could not load your staff profile — try again")
                    }
                    if (profile == null || !profile.active) {
                        api.signOut(session.accessToken)
                        return@withContext LoginResult.Error("This account is not authorized for the POS")
                    }

                    val cached = CachedAuth(
                        userId = userId,
                        email = session.user.email ?: email.trim(),
                        role = profile.role,
                        displayName = profile.displayName,
                        accessToken = session.accessToken,
                        refreshToken = session.refreshToken,
                        expiresAt = session.expiryEpochSeconds(),
                    )
                    vault.upsertSession(cached, makeActive = true)
                    vault.resetPinFailures(userId)
                    activeUserId = userId
                    currentAccessToken = cached.accessToken
                    _reloginRequired.value = false

                    _state.value = if (vault.hasPin(userId)) {
                        AuthState.Active(cached.toUser())
                    } else {
                        AuthState.PinSetup(cached.toUser())
                    }
                    LoginResult.Ok
                }
            }
        }

    // ── PIN ───────────────────────────────────────────────────────────────

    /** Suspends on IO: PBKDF2 (120k iterations) is too heavy for the main thread on
     *  low-end hardware like the Sunmi, and hashing there would freeze PIN setup. */
    suspend fun setPin(pin: String) {
        val user = (state.value as? AuthState.PinSetup)?.user ?: return
        withContext(Dispatchers.IO) { vault.setPin(user.id, pin) }
        _state.value = AuthState.Active(user)
    }

    fun skipPin() {
        val user = (state.value as? AuthState.PinSetup)?.user ?: return
        _state.value = AuthState.Active(user)
    }

    /** Offline unlock of the account currently shown on the [AuthState.Locked] screen.
     *  After [MAX_PIN_ATTEMPTS] failures that account is removed from the device and a
     *  fresh online login is required to re-add it. */
    suspend fun unlockWithPin(pin: String): LoginResult = withContext(Dispatchers.IO) {
        val account = (state.value as? AuthState.Locked)?.account
            ?: return@withContext LoginResult.Error("Pick an account first").also { switchUser() }
        val userId = account.userId
        if (vault.verifyPin(userId, pin)) {
            vault.resetPinFailures(userId)
            activate(userId)
            LoginResult.Ok
        } else {
            val fails = vault.recordPinFailure(userId)
            if (fails >= MAX_PIN_ATTEMPTS) {
                val remaining = vault.removeAccount(userId)
                if (userId == activeUserId) { activeUserId = null; currentAccessToken = null }
                _state.value = if (remaining.isEmpty()) AuthState.LoggedOut else AuthState.Picker(remaining)
                LoginResult.Error("Too many attempts — sign in with your password")
            } else {
                LoginResult.Error("Wrong PIN (${MAX_PIN_ATTEMPTS - fails} attempts left)")
            }
        }
    }

    /**
     * The signed-in (or last-active) cashier, for stamping attribution on records
     * created OUTSIDE the UI — notably the passive SMS receiver (Phase 5), which fires
     * while the app may be backgrounded or PIN-locked. Prefers the live Active user;
     * falls back to the active vault session so a locked device still attributes to
     * the cashier who last used it. Null before any first login.
     */
    suspend fun cachedUserOrNull(): PosUser? = withContext(Dispatchers.IO) {
        (state.value as? AuthState.Active)?.user
            ?: (state.value as? AuthState.PinSetup)?.user
            ?: vault.activeSession()?.toUser()
    }

    /** Authorise a manager-gated action (e.g. an over-threshold discount) by checking
     *  the entered PIN against any admin account provisioned on this device. */
    suspend fun verifyAdminPin(pin: String): Boolean =
        withContext(Dispatchers.IO) { vault.verifyAnyAdminPin(pin) }

    // ── local device PIN (no-cloud mode) ──────────────────────────────────
    // The optional single PIN that locks a phone-only till. Separate from cloud
    // accounts entirely; all hashing runs on IO (PBKDF2 is heavy on the Sunmi).

    /** True if a cloud account has ever been provisioned on this device. */
    fun hasCloudAccount(): Boolean = vault.hasAnyAccount()

    suspend fun hasLocalPin(): Boolean = withContext(Dispatchers.IO) { vault.hasLocalPin() }

    suspend fun setLocalPin(pin: String) = withContext(Dispatchers.IO) { vault.setLocalPin(pin) }

    suspend fun verifyLocalPin(pin: String): Boolean =
        withContext(Dispatchers.IO) { vault.verifyLocalPin(pin) }

    suspend fun clearLocalPin() = withContext(Dispatchers.IO) { vault.clearLocalPin() }

    // ── tokens for the sync layer ─────────────────────────────────────────

    /** Snapshot for request headers; may be stale, see [refreshIfNeeded]. */
    fun accessTokenOrNull(): String? = currentAccessToken

    /**
     * Is the CURRENT device operating as an admin? Drives notification-audience gating
     * (which alerts raise a heads-up on THIS phone, and where they deep-link). Reads the
     * active cached session's role directly from the vault so it works from a background
     * pass with no live UI. Local (phone-only) mode has NO cloud session — the local
     * owner is always the admin (see MainActivity's LOCAL mode) — so a null session reads
     * as admin. Cheap enough to call per sync pass (a decrypt of the small vault blob).
     */
    fun isDeviceAdmin(): Boolean = vault.activeSession()?.role?.let { it == "admin" } ?: true

    /**
     * Called before a sync pass. Refreshes the active account's access token when
     * it's within a minute of expiry. Offline: keep what we have (sync only runs
     * online anyway, and a failed pass just retries later — records stay queued).
     * Server-rejected refresh: raise [reloginRequired]; never drop local data.
     */
    suspend fun refreshIfNeeded() = withContext(Dispatchers.IO) {
        refreshMutex.withLock {
            val userId = activeUserId ?: return@withLock
            val cached = vault.sessionFor(userId) ?: return@withLock
            val now = System.currentTimeMillis() / 1000
            if (now < cached.expiresAt - 60) {
                currentAccessToken = cached.accessToken
                return@withLock
            }
            when (val result = api.refresh(cached.refreshToken)) {
                is AuthResult.Success -> {
                    val s = result.session
                    val updated = cached.copy(
                        accessToken = s.accessToken,
                        refreshToken = s.refreshToken,
                        expiresAt = s.expiryEpochSeconds(),
                    )
                    vault.updateSession(userId, updated)
                    currentAccessToken = updated.accessToken
                    _reloginRequired.value = false
                }
                is AuthResult.Rejected -> _reloginRequired.value = true
                is AuthResult.Offline -> Unit // keep the stale token; retry next pass
            }
        }
    }

    // ── sign out / re-login ───────────────────────────────────────────────

    /** Remove the active account from this device (session + PIN). Local Room data is
     *  untouched. Drops to the picker if other accounts remain, else to login. */
    suspend fun signOut() = withContext(Dispatchers.IO) {
        val userId = (state.value as? AuthState.Active)?.user?.id ?: activeUserId
        currentAccessToken?.let { api.signOut(it) }
        val remaining = if (userId != null) vault.removeAccount(userId) else vault.accounts()
        activeUserId = null
        currentAccessToken = null
        _reloginRequired.value = false
        _state.value = if (remaining.isEmpty()) AuthState.LoggedOut else AuthState.Picker(remaining)
    }

    /** From the "session expired" banner: go to re-login (add-account), keeping the
     *  cached session until the new login overwrites it, so nothing is lost if the
     *  user backs out. */
    fun promptRelogin() {
        _state.value = if (vault.hasAnyAccount()) AuthState.AddAccount else AuthState.LoggedOut
    }

    private fun CachedAuth.toUser() = PosUser(userId, email, role, displayName)

    private companion object {
        const val MAX_PIN_ATTEMPTS = 5
    }
}
