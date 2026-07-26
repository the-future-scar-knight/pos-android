package com.portionspot.pos.auth

import android.content.Context
import com.portionspot.pos.sync.Connection
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
    /** Per-person capability grants (see [can]). Admins are never gated regardless. */
    val permissions: Permissions = Permissions.EMPTY,
) {
    val isAdmin: Boolean get() = role == "admin"
}

/**
 * A shop-roster staff member the lock-screen picker can offer even though they have
 * never signed in on THIS device. Populated from the cloud `pos_staff` roster while
 * online. Tapping one starts a password sign-in pre-filled with [email].
 */
data class RosterMember(
    val userId: String,
    val email: String,
    val displayName: String,
    val role: String,
) {
    val isAdmin: Boolean get() = role == "admin"
}

sealed class AuthState {
    /** Reading the vault on process start. */
    object Loading : AuthState()

    /** No accounts on this device — online email+password login required. */
    object LoggedOut : AuthState()

    /** Adding another account from the picker (keeps existing accounts). When
     *  [prefillEmail] is set the login screen seeds that email — used by the
     *  lock-screen "switch to a named staff member" flow, where the person only has
     *  to type their own password. */
    data class AddAccount(val prefillEmail: String? = null) : AuthState()

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

    /**
     * Resolves the saved cloud connection (Supabase url + anon key), wired by the
     * app container from [com.portionspot.pos.sync.SyncConfig]. Null before a
     * connection is configured; when null the roster fetch is skipped entirely.
     */
    var connectionProvider: (suspend () -> Connection?)? = null

    /** Shop-roster members eligible for lock-screen "switch by name": active staff
     *  who are NOT already on this device and are NOT the current user. */
    private val _roster = MutableStateFlow<List<RosterMember>>(emptyList())
    val roster: StateFlow<List<RosterMember>> = _roster

    /** True while a roster fetch is in flight (drives the picker's spinner). */
    private val _rosterLoading = MutableStateFlow(false)
    val rosterLoading: StateFlow<Boolean> = _rosterLoading

    /** True when the server refused our refresh token: prompt re-login, keep data. */
    private val _reloginRequired = MutableStateFlow(false)
    val reloginRequired: StateFlow<Boolean> = _reloginRequired

    @Volatile
    private var currentAccessToken: String? = null

    /** Which account [refreshIfNeeded] refreshes: the last one unlocked / added. */
    @Volatile
    private var activeUserId: String? = null

    /** Memoised "does this device have a cloud account at all?", so the hot token path
     *  ([accessTokenOrNull], called per HTTP request) doesn't decrypt the vault every
     *  time. Null = unknown, recomputed on next read; invalidated on every mutation. */
    @Volatile
    private var cachedHasCloudAccount: Boolean? = null

    init {
        scope.launch {
            withContext(Dispatchers.IO) {
                // Restore the last-used session so a background pass (and the token layer)
                // has an identity before any UI runs. Never CLOBBER a session another path
                // already established: this restore is asynchronous and can land after a
                // login, an unlock, or the read-through in [accessTokenOrNull].
                val cached = runCatching { vault.activeOrAnySession() }.getOrNull()
                if (activeUserId == null) activeUserId = cached?.userId
                if (currentAccessToken == null) currentAccessToken = cached?.accessToken
                val any = runCatching { vault.hasAnyAccount() }.getOrDefault(false)
                cachedHasCloudAccount = any
                if (_state.value is AuthState.Loading) {
                    _state.value = if (any) AuthState.Picker(vault.accounts()) else AuthState.LoggedOut
                }
            }
        }
    }

    /** Forget the memoised account-presence answer after any vault mutation. */
    private fun invalidateAccountCache() { cachedHasCloudAccount = null }

    // ── account picker / switching ────────────────────────────────────────

    /** Go to the account picker without dropping any cached session. */
    fun switchUser() {
        _state.value = AuthState.Picker(vault.accounts())
    }

    /** From the picker: add another account via online login. */
    fun addAccount() {
        _state.value = AuthState.AddAccount()
    }

    /**
     * From the picker's "Other staff" section: sign in as a roster member who has
     * never been added on this device. Their own password is still required (no free
     * admin impersonation) — we just seed their email so they only type a password.
     * On success it flows through the normal path (first time here ⇒ PIN setup).
     */
    fun switchToStaff(email: String) {
        _state.value = AuthState.AddAccount(prefillEmail = email)
    }

    /**
     * Fetch the shop roster for the picker's "Other staff" section. Online-only and
     * best-effort: runs on IO with whatever token is available (falling back to the
     * connection's anon key inside [StaffAdminClient]); any failure — offline, no
     * connection configured, or RLS blocking an unauthenticated read — simply leaves
     * the roster empty so the picker shows only local accounts. Excludes active staff
     * already provisioned on this device (matched by id OR email) and the current user.
     */
    fun loadRoster() {
        val provider = connectionProvider
        if (provider == null) { _roster.value = emptyList(); return }
        scope.launch {
            _rosterLoading.value = true
            try {
                val conn = withContext(Dispatchers.IO) { provider() }
                if (conn == null) { _roster.value = emptyList(); return@launch }
                val token = currentAccessToken
                val rows = withContext(Dispatchers.IO) {
                    StaffAdminClient(conn) { token }.listRoster()
                }
                val localIds = vault.accounts().map { it.userId }.toSet()
                val localEmails = vault.accounts().map { it.email.trim().lowercase() }.toSet()
                _roster.value = rows.asSequence()
                    .filter { it.active }
                    .map { RosterMember(it.id, it.email.orEmpty().trim(), it.displayName, it.role) }
                    .filter { it.userId.isNotBlank() && it.email.isNotBlank() }
                    .filter { it.userId !in localIds && it.email.lowercase() !in localEmails }
                    .filter { it.userId != activeUserId }
                    .toList()
            } catch (_: Exception) {
                _roster.value = emptyList()
            } finally {
                _rosterLoading.value = false
            }
        }
    }

    /** Drop any roster (e.g. when the device goes offline). */
    fun clearRoster() {
        _roster.value = emptyList()
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
        cachedHasCloudAccount = true
        // A different account's session may well be healthy; clear the prompt and let the
        // next [refreshIfNeeded] re-raise it if this one is dead too.
        _reloginRequired.value = false
        _state.value = AuthState.Active(session.toUser())
    }

    // ── online login (first account or "add account") ─────────────────────

    suspend fun login(email: String, password: String): LoginResult =
        withContext(Dispatchers.IO) {
            when (val result = api.signIn(email.trim(), password)) {
                // Covers both "no network" and a transient server-side failure (5xx / 429),
                // which SupabaseAuth deliberately does NOT report as a rejected credential.
                is AuthResult.Offline ->
                    LoginResult.Error("Couldn't reach the sign-in server — check your connection and try again")
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
                        permissions = Permissions.jsonToKeyMap(profile.permissions),
                    )
                    vault.upsertSession(cached, makeActive = true)
                    vault.resetPinFailures(userId)
                    activeUserId = userId
                    currentAccessToken = cached.accessToken
                    cachedHasCloudAccount = true
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
                invalidateAccountCache()
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
    fun hasCloudAccount(): Boolean = hasCloudSession()

    suspend fun hasLocalPin(): Boolean = withContext(Dispatchers.IO) { vault.hasLocalPin() }

    suspend fun setLocalPin(pin: String) = withContext(Dispatchers.IO) { vault.setLocalPin(pin) }

    suspend fun verifyLocalPin(pin: String): Boolean =
        withContext(Dispatchers.IO) { vault.verifyLocalPin(pin) }

    suspend fun clearLocalPin() = withContext(Dispatchers.IO) { vault.clearLocalPin() }

    // ── tokens for the sync layer ─────────────────────────────────────────

    /**
     * A REAL access token for the account this device syncs as, or null if there simply
     * isn't one. Reads through to the vault when the in-memory copy is missing, so a pass
     * that starts before the asynchronous restore in [init] has finished — the ~45s
     * foreground poll, the push-on-change debounce, the hot-poll, and most sharply the
     * [com.portionspot.pos.sync.SyncWorker] waking a FRESH process — still gets the right
     * identity. The token may be stale; [refreshIfNeeded] handles expiry.
     */
    private fun liveTokenOrNull(): String? {
        // A server-refused refresh means the cached token is dead; don't resurrect it
        // from the vault, and don't let anything fall back to anon (see accessTokenOrNull).
        if (_reloginRequired.value) return null
        currentAccessToken?.takeIf { it.isNotBlank() }?.let { return it }
        val cached = runCatching {
            activeUserId?.let { vault.sessionFor(it) } ?: vault.activeOrAnySession()
        }.getOrNull() ?: return null
        if (cached.accessToken.isBlank()) return null
        activeUserId = cached.userId
        currentAccessToken = cached.accessToken
        return cached.accessToken
    }

    /** Has a cloud account ever been provisioned here? Memoised; see [cachedHasCloudAccount]. */
    private fun hasCloudSession(): Boolean =
        cachedHasCloudAccount ?: runCatching { vault.hasAnyAccount() }
            .getOrDefault(false)
            .also { cachedHasCloudAccount = it }

    /**
     * Identity for a sync request, in the three-valued contract
     * [com.portionspot.pos.sync.SupabaseRest] expects:
     *
     *  - a JWT → sign with it (possibly stale; PostgREST answers 401 and the pass retries).
     *  - `null` → this device has NO cloud account (local/phone-only mode). The anon key
     *    is the correct, intended identity.
     *  - [com.portionspot.pos.sync.SupabaseRest.SESSION_UNAVAILABLE] → an account EXISTS
     *    but no token can be produced for it. The request must be REFUSED, never signed
     *    with the anon key: an anon write is rejected by RLS on every table (Postgres
     *    42501), which looks like a data problem, hides the auth problem, and leaves the
     *    rows silently unsynced. Raising [reloginRequired] here is what puts the
     *    "Session expired" banner in front of the user.
     */
    fun accessTokenOrNull(): String? {
        liveTokenOrNull()?.let { return it }
        if (!hasCloudSession()) return null
        _reloginRequired.value = true
        return com.portionspot.pos.sync.SupabaseRest.SESSION_UNAVAILABLE
    }

    /**
     * Is the CURRENT device operating as an admin? Drives notification-audience gating
     * (which alerts raise a heads-up on THIS phone, and where they deep-link). Reads the
     * active cached session's role directly from the vault so it works from a background
     * pass with no live UI. Local (phone-only) mode has NO cloud session — the local
     * owner is always the admin (see MainActivity's LOCAL mode) — so a null session reads
     * as admin. Cheap enough to call per sync pass (a decrypt of the small vault blob).
     */
    fun isDeviceAdmin(): Boolean =
        runCatching { vault.activeOrAnySession() }.getOrNull()?.role?.let { it == "admin" } ?: true

    /**
     * Called before every sync pass: make sure a usable access token exists, refreshing
     * proactively once we're inside [REFRESH_SKEW_SECONDS] of expiry (comfortably wider
     * than the ~15-minute background cadence, so a token never dies between passes).
     *
     * It resolves the account from the VAULT rather than trusting the in-memory
     * [activeUserId]. The old `activeUserId ?: return` was the silent-data-loss bug: the
     * restore in [init] is asynchronous, so a pass arriving first — the SyncWorker waking
     * a fresh process is the clean example — early-returned, left [currentAccessToken]
     * null, and every write then went out signed with the ANON key. Postgres refused all
     * of them with 42501, no refresh was ever attempted, and the UI still said "signed in".
     *
     * Offline is NOT a session failure: the cached token stays in place, the pass fails on
     * the network, records stay queued, and we try again. Only a server verdict
     * ([AuthResult.Rejected], i.e. a 4xx on the refresh grant) drops the dead token and
     * raises [reloginRequired]. Local data is never touched either way.
     */
    suspend fun refreshIfNeeded() = withContext(Dispatchers.IO) {
        refreshMutex.withLock {
            val cached = (activeUserId?.let { vault.sessionFor(it) } ?: vault.activeOrAnySession())
                ?: run { currentAccessToken = null; return@withLock }
            val userId = cached.userId
            activeUserId = userId
            // Publish the cached token up front, so that even if the refresh below fails we
            // send a REAL (if stale) JWT — rejected with 401 by PostgREST before it ever
            // reaches Postgres — instead of degrading to the anon key.
            if (cached.accessToken.isNotBlank()) currentAccessToken = cached.accessToken

            val now = System.currentTimeMillis() / 1000
            val stillFresh = cached.accessToken.isNotBlank() &&
                now < cached.expiresAt - REFRESH_SKEW_SECONDS
            if (stillFresh && !_reloginRequired.value) return@withLock

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
                is AuthResult.Rejected -> {
                    // The server refused the refresh token: this session is dead. Drop the
                    // token so nothing can keep signing writes with it, and prompt.
                    currentAccessToken = null
                    _reloginRequired.value = true
                }
                is AuthResult.Offline -> Unit // keep the cached token; retry next pass
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
        cachedHasCloudAccount = remaining.isNotEmpty()
        _reloginRequired.value = false
        _state.value = if (remaining.isEmpty()) AuthState.LoggedOut else AuthState.Picker(remaining)
    }

    /** From the "session expired" banner: go to re-login (add-account), keeping the
     *  cached session until the new login overwrites it, so nothing is lost if the
     *  user backs out. */
    fun promptRelogin() {
        _state.value = if (vault.hasAnyAccount()) AuthState.AddAccount() else AuthState.LoggedOut
    }

    /**
     * Re-fetch the ACTIVE account's own `pos_staff` row and update its cached role +
     * permissions, so an admin's change to a cashier's grants reaches that cashier's
     * device. Offline (fetch throws) or a not-found row ⇒ keep whatever is cached; we
     * never sign the user out or clear grants from a transient failure. If the live
     * [AuthState.Active] is this user, re-emit it so the UI (and [PosUser.permissions])
     * refresh.
     */
    suspend fun refreshCurrentPermissions() = withContext(Dispatchers.IO) {
        // Same read-through as the sync path: don't give up just because the async restore
        // in [init] hasn't populated the in-memory session yet.
        val token = liveTokenOrNull() ?: return@withContext
        val userId = activeUserId ?: return@withContext
        val profile = try {
            api.fetchStaffProfile(token, userId)
        } catch (_: Exception) {
            return@withContext // offline / transient — keep cached grants
        } ?: return@withContext
        val cached = vault.sessionFor(userId) ?: return@withContext
        val updated = cached.copy(
            role = profile.role,
            displayName = profile.displayName.ifBlank { cached.displayName },
            permissions = Permissions.jsonToKeyMap(profile.permissions),
        )
        vault.updateSession(userId, updated)
        val s = _state.value
        if (s is AuthState.Active && s.user.id == userId) {
            _state.value = AuthState.Active(updated.toUser())
        }
    }

    private fun CachedAuth.toUser() =
        PosUser(userId, email, role, displayName, Permissions.fromKeyMap(permissions))

    private companion object {
        const val MAX_PIN_ATTEMPTS = 5

        /** Refresh this many seconds BEFORE the access token actually expires. Supabase
         *  tokens live ~1h; the background sync cadence is ~15 min, so a 5-minute cushion
         *  guarantees at least one pass gets to renew the token while it is still valid,
         *  instead of the shop discovering it died between passes. */
        const val REFRESH_SKEW_SECONDS = 300L
    }
}
