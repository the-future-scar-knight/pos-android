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

    /** No cached session — online email+password login required. */
    object LoggedOut : AuthState()

    /** Session cached and a PIN is set: offline unlock. */
    data class Locked(val displayName: String) : AuthState()

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
 * Owns the auth lifecycle: online login, encrypted session cache, offline PIN
 * unlock, token refresh, and role plumbing.
 *
 * Offline-first contract (prompt §2): first login needs internet; after that
 * the cashier unlocks with a PIN and works fully offline. If the refresh token
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

    init {
        scope.launch {
            val cached = withContext(Dispatchers.IO) { vault.loadSession() }
            if (cached == null) {
                _state.value = AuthState.LoggedOut
            } else {
                currentAccessToken = cached.accessToken
                _state.value = if (vault.hasPin()) {
                    AuthState.Locked(cached.displayName)
                } else {
                    AuthState.Active(cached.toUser())
                }
            }
        }
    }

    // ── online login ──────────────────────────────────────────────────────

    suspend fun login(email: String, password: String): LoginResult =
        withContext(Dispatchers.IO) {
            when (val result = api.signIn(email.trim(), password)) {
                is AuthResult.Offline ->
                    LoginResult.Error("No connection — first login needs internet")
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
                    vault.saveSession(cached)
                    vault.resetPinFailures()
                    currentAccessToken = cached.accessToken
                    _reloginRequired.value = false

                    _state.value = if (vault.hasPin()) {
                        AuthState.Active(cached.toUser())
                    } else {
                        AuthState.PinSetup(cached.toUser())
                    }
                    LoginResult.Ok
                }
            }
        }

    // ── PIN ───────────────────────────────────────────────────────────────

    fun setPin(pin: String) {
        val user = (state.value as? AuthState.PinSetup)?.user ?: return
        vault.setPin(pin)
        _state.value = AuthState.Active(user)
    }

    fun skipPin() {
        val user = (state.value as? AuthState.PinSetup)?.user ?: return
        _state.value = AuthState.Active(user)
    }

    /** Offline unlock. After [MAX_PIN_ATTEMPTS] failures the PIN is wiped and a
     *  full password login is required (which needs internet). */
    suspend fun unlockWithPin(pin: String): LoginResult = withContext(Dispatchers.IO) {
        val cached = vault.loadSession()
            ?: return@withContext LoginResult.Error("Session missing — sign in again").also {
                _state.value = AuthState.LoggedOut
            }
        if (vault.verifyPin(pin)) {
            vault.resetPinFailures()
            currentAccessToken = cached.accessToken
            _state.value = AuthState.Active(cached.toUser())
            LoginResult.Ok
        } else {
            val fails = vault.recordPinFailure()
            if (fails >= MAX_PIN_ATTEMPTS) {
                vault.clearPin()
                _state.value = AuthState.LoggedOut
                LoginResult.Error("Too many attempts — sign in with your password")
            } else {
                LoginResult.Error("Wrong PIN (${MAX_PIN_ATTEMPTS - fails} attempts left)")
            }
        }
    }

    /**
     * The signed-in (or last-unlocked) cashier, for stamping attribution on records
     * created OUTSIDE the UI — notably the passive SMS receiver (Phase 5), which fires
     * while the app may be backgrounded or PIN-locked. Prefers the live Active user;
     * falls back to the cached vault session so a locked device still attributes to
     * the cashier who last used it. Null before any first login.
     */
    suspend fun cachedUserOrNull(): PosUser? = withContext(Dispatchers.IO) {
        (state.value as? AuthState.Active)?.user
            ?: (state.value as? AuthState.PinSetup)?.user
            ?: vault.loadSession()?.toUser()
    }

    // ── tokens for the sync layer ─────────────────────────────────────────

    /** Snapshot for request headers; may be stale, see [refreshIfNeeded]. */
    fun accessTokenOrNull(): String? = currentAccessToken

    /**
     * Called before a sync pass. Refreshes the access token when it's within
     * a minute of expiry. Offline: keep what we have (sync only runs online
     * anyway, and a failed pass just retries later — records stay queued).
     * Server-rejected refresh: raise [reloginRequired]; never drop local data.
     */
    suspend fun refreshIfNeeded() = withContext(Dispatchers.IO) {
        refreshMutex.withLock {
            val cached = vault.loadSession() ?: return@withLock
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
                    vault.saveSession(updated)
                    currentAccessToken = updated.accessToken
                    _reloginRequired.value = false
                }
                is AuthResult.Rejected -> _reloginRequired.value = true
                is AuthResult.Offline -> Unit // keep the stale token; retry next pass
            }
        }
    }

    // ── sign out / re-login ───────────────────────────────────────────────

    /** Clears the vault (session + PIN). Local Room data is untouched. */
    suspend fun logout() = withContext(Dispatchers.IO) {
        currentAccessToken?.let { api.signOut(it) }
        vault.clearAll()
        currentAccessToken = null
        _reloginRequired.value = false
        _state.value = AuthState.LoggedOut
    }

    /** From the "session expired" banner: go to the login screen. The vault
     *  session is kept until the new login overwrites it, so nothing is lost
     *  if the user backs out. */
    fun promptRelogin() {
        _state.value = AuthState.LoggedOut
    }

    private fun CachedAuth.toUser() = PosUser(userId, email, role, displayName)

    private companion object {
        const val MAX_PIN_ATTEMPTS = 5
    }
}
