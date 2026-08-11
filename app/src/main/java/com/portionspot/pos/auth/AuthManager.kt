package com.portionspot.pos.auth

import android.content.Context
import com.portionspot.pos.sync.Connection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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
 * One name on the sign-in screen, taken from the roster this device holds.
 *
 * [hasPin] is shown rather than hidden: a cashier the owner created but never gave a PIN
 * to cannot sign in, and finding that out by typing four digits and being told "wrong PIN"
 * sends them to the wrong person for the fix.
 */
data class RosterMember(
    val id: String,
    val username: String,
    val displayName: String,
    val role: String,
    val hasPin: Boolean,
) {
    val isAdmin: Boolean get() = role == "admin"
}

sealed class AuthState {
    /** Reading the vault on process start. */
    object Loading : AuthState()

    /**
     * Nobody is signed in: the staff sign-in screen.
     *
     * ★ There is exactly ONE way out of this state and it is a PIN. It has no
     * "continue without signing in", and MainActivity only offers a back arrow on a
     * device that has never had a cloud sign-in — because local mode runs as a
     * hard-coded admin against the same database, so a back arrow here would let a
     * signed-out cashier promote themselves to owner. See `PosViewModel.cloudProvisioned`.
     */
    object SignIn : AuthState()

    /** Working. All POS screens live behind this state. */
    data class Active(val user: PosUser) : AuthState()
}

sealed class LoginResult {
    object Ok : LoginResult()
    data class Error(val message: String) : LoginResult()
}

/**
 * Owns who is using the till.
 *
 * ── WHAT REPLACED WHAT ───────────────────────────────────────────────────────
 *
 * This used to be a Supabase-Auth (GoTrue) client: email + password, access and refresh
 * tokens, a `pos_staff` profile read, and a per-device unlock PIN layered on top so the
 * shop could keep working offline. Three separate things were wrong with it and any one
 * was fatal: the GoTrue URL was hard-coded to a project the shop does not own, the client
 * was never built from the shop's saved connection, and `pos_staff` is not a table the
 * shared schema has (it is `staff`). Every call 404'd or answered for the wrong database.
 *
 * The web already models a till login as a `staff` row with a `pin_hash`, so this now
 * does the same thing: **a username and a PIN, checked against the roster on the device**.
 * That removes a whole service, removes tokens and refresh entirely, and makes offline the
 * default rather than a feature — the credential is a hash that is already on the phone
 * (see [com.portionspot.pos.data.StaffMember]), so a cashier whose row has synced once
 * signs in through a power cut and a dead cell.
 *
 * PostgREST is still reached with the ANON KEY exactly as before ([accessTokenOrNull]
 * returns null, which is that layer's word for "no session, use anon"). Nothing here
 * mints, holds or refreshes a JWT any more.
 *
 * ── WHAT SURVIVED, DELIBERATELY ──────────────────────────────────────────────
 *
 * The [SessionVault] still caches WHO signed in last, so a background pass with no UI —
 * the SMS receiver, the admin-notification worker — can still attribute a record and
 * still tell whether this phone is the owner's. And [PermissionRefresh] still governs
 * revocation, unchanged rule for rule; only the table it reads moved from `pos_staff`
 * to `staff`.
 */
class AuthManager(
    context: Context,
    /** The roster + shop id this device signs in against. */
    private val directory: StaffDirectory,
) {
    private val vault = SessionVault(context.applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow<AuthState>(AuthState.Loading)
    val state: StateFlow<AuthState> = _state

    /**
     * Resolves the saved cloud connection (Supabase url + anon key), wired by the app
     * container from [com.portionspot.pos.sync.SyncConfig]. Null before a connection is
     * configured, in which case the remote permission refresh simply does not run —
     * sign-in itself never needs it.
     */
    var connectionProvider: (suspend () -> Connection?)? = null

    /** The names on the sign-in screen, read from the local roster mirror. */
    private val _roster = MutableStateFlow<List<RosterMember>>(emptyList())
    val roster: StateFlow<List<RosterMember>> = _roster

    /** True while the roster is being read (drives the screen's spinner). */
    private val _rosterLoading = MutableStateFlow(false)
    val rosterLoading: StateFlow<Boolean> = _rosterLoading

    /** Who is signed in right now, for the permission refresh and the token-less
     *  identity reads below. Null between sign-outs. */
    @Volatile
    private var activeUserId: String? = null

    /**
     * Consecutive "server answered, no such staff row" sightings per account, for the
     * two-strike rule in [PermissionRefresh]. Deliberately IN MEMORY and not persisted:
     * the count exists to distinguish a genuinely deleted row (which is still missing on
     * the next pass, minutes later) from a one-off empty read after a schema rebuild or
     * an RLS change, and a process restart is a perfectly good moment to start that
     * judgement over. Persisting it would only make a stale strike outlive the condition
     * that caused it. Keyed by staff id so one cashier's strike can't revoke another.
     */
    private val missingStrikes = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** Memoised "has anyone ever signed in on this device?", so the hot identity path
     *  doesn't decrypt the vault every time. Null = unknown; invalidated on mutation. */
    @Volatile
    private var cachedHasCloudAccount: Boolean? = null

    init {
        scope.launch {
            withContext(Dispatchers.IO) {
                // Restore the LAST-USED identity so a background pass (the SMS receiver, the
                // notification worker) has someone to attribute to before any UI runs. It
                // does NOT sign anyone in: the screen still asks for a PIN on every cold
                // start, which is the point of one phone per cashier.
                val cached = runCatching { vault.activeOrAnySession() }.getOrNull()
                cachedHasCloudAccount = runCatching { vault.hasAnyAccount() }.getOrDefault(false)
                if (activeUserId == null) activeUserId = cached?.userId
                if (_state.value is AuthState.Loading) _state.value = AuthState.SignIn
            }
            loadRoster()
        }
    }

    /** Forget the memoised account-presence answer after any vault mutation. */
    private fun invalidateAccountCache() { cachedHasCloudAccount = null }

    // ── the roster ────────────────────────────────────────────────────────

    /**
     * Re-read the names the sign-in screen offers.
     *
     * LOCAL ONLY — no network, ever. The list is whatever the last roster pull left on the
     * device, and a till with no signal must still show its cashiers. Deactivated and
     * tombstoned rows are filtered out here (they are still fetched by [signIn], which
     * needs them to say "that account is switched off" rather than "no such user").
     */
    fun loadRoster() {
        scope.launch {
            _rosterLoading.value = true
            try {
                val rows = withContext(Dispatchers.IO) { directory.all() }
                _roster.value = rows
                    .filter { it.active && !it.deleted }
                    .map {
                        RosterMember(
                            id = it.id,
                            username = it.username,
                            displayName = it.name.ifBlank { it.username },
                            role = it.role,
                            hasPin = !it.pinHash.isNullOrBlank(),
                        )
                    }
                    .filter { it.username.isNotBlank() || it.displayName.isNotBlank() }
            } catch (_: Exception) {
                _roster.value = emptyList()
            } finally {
                _rosterLoading.value = false
            }
        }
    }

    // ── sign in ───────────────────────────────────────────────────────────

    /**
     * Sign in by typing a username. Suspends on IO: PBKDF2 at 210k iterations is ~100ms+
     * on a Sunmi and would visibly freeze the keypad on the main thread.
     */
    suspend fun signIn(username: String, pin: String): LoginResult = withContext(Dispatchers.IO) {
        val shop = directory.shopId()
        val result = StaffSignIn.attempt(directory.all(), username, pin, shop)
        finish(result)
    }

    /** Sign in as a name tapped on the roster — same rules, one less thing to type. */
    suspend fun signInAs(staffId: String, pin: String): LoginResult = withContext(Dispatchers.IO) {
        val shop = directory.shopId()
        val member = directory.byId(staffId)
            ?: return@withContext LoginResult.Error(
                StaffSignIn.message(StaffSignInResult.UnknownUser)
            )
        finish(StaffSignIn.verify(member, pin, shop))
    }

    /**
     * Turn a [StaffSignInResult] into a session.
     *
     * The role and the grants come straight off the staff row and go into the SAME cached
     * shape the rest of the app already reads ([CachedAuth] → [PosUser]), so every
     * capability gate downstream keeps working without knowing the credential changed.
     * Tokens are blank strings rather than a nullable field: there is no session to expire
     * any more, and the shape is shared with [PermissionRefresh.applyTo].
     */
    private fun finish(result: StaffSignInResult): LoginResult {
        val member = (result as? StaffSignInResult.Ok)?.member
            ?: return LoginResult.Error(StaffSignIn.message(result))
        val cached = CachedAuth(
            userId = member.id,
            // `email` carries the USERNAME. There is no email column on `staff` and no
            // mailbox behind it — the field is a label on the lock screen and an
            // attribution string on a receipt, and the username is the honest thing there.
            email = member.username,
            role = clampRole(member.role),
            displayName = member.name.ifBlank { member.username },
            accessToken = "",
            refreshToken = "",
            expiresAt = 0L,
            permissions = Permissions.fromJsonString(member.permissions).asKeyMap(),
        )
        vault.upsertSession(cached, makeActive = true)
        activeUserId = cached.userId
        cachedHasCloudAccount = true
        missingStrikes.remove(cached.userId)
        _state.value = AuthState.Active(cached.toUser())
        return LoginResult.Ok
    }

    /**
     * Narrow a role to the two this app distinguishes.
     *
     * The cloud allows `manager`, which Android's capability model has no third tier for
     * ([PosUser.isAdmin] tests `role == "admin"`), so a manager lands on the cashier side
     * of that line and is shaped by their `permissions` grants instead. Anything
     * unrecognised narrows the same way. That is the safe direction: an unexpected role
     * can only ever mean FEWER powers, never more.
     */
    private fun clampRole(role: String?): String {
        val r = role?.trim()?.lowercase().orEmpty()
        return if (r == "admin") "admin" else r.ifBlank { PermissionRefresh.FALLBACK_ROLE }
    }

    // ── sign out / switch ─────────────────────────────────────────────────

    /**
     * Lock the till and return to the sign-in screen, KEEPING the cached identity so a
     * background pass still knows who last used the phone. The next person still has to
     * present a PIN; nothing about this state is a credential.
     */
    fun switchUser() {
        activeUserId = null
        _state.value = AuthState.SignIn
        loadRoster()
    }

    /**
     * Sign out for good: drop the cached session as well. Local Room data is untouched —
     * unsynced sales stay queued and flush under whoever signs in next.
     *
     * ★ This lands on [AuthState.SignIn] and nowhere else. The route it used to take —
     * empty account list ⇒ LoggedOut ⇒ a login screen with a back arrow into local mode,
     * which runs as a hard-coded admin against the same database — was a three-tap
     * escalation from revoked cashier to owner. There is no such fall-through now: the
     * state does not depend on how many accounts remain.
     */
    suspend fun signOut() = withContext(Dispatchers.IO) {
        val userId = (state.value as? AuthState.Active)?.user?.id ?: activeUserId
        if (userId != null) vault.removeAccount(userId)
        invalidateAccountCache()
        activeUserId = null
        _state.value = AuthState.SignIn
        loadRoster()
    }

    // ── identity for code with no UI ──────────────────────────────────────

    /**
     * The signed-in (or last-active) cashier, for stamping attribution on records created
     * OUTSIDE the UI — notably the passive SMS receiver, which fires while the app may be
     * backgrounded or sitting on the sign-in screen. Prefers the live session; falls back
     * to the cached one so a locked device still attributes to whoever last used it.
     */
    suspend fun cachedUserOrNull(): PosUser? = withContext(Dispatchers.IO) {
        (state.value as? AuthState.Active)?.user ?: vault.activeOrAnySession()?.toUser()
    }

    /**
     * Authorise a manager-gated action (an over-threshold discount, say) with an admin's
     * PIN, on whatever phone it is being asked for.
     *
     * Checked against the shop ROSTER rather than the accounts provisioned on this device,
     * which is the whole point on a one-phone-per-cashier deployment: the owner walks over
     * and types their PIN on the cashier's handset, where they have never signed in.
     * [StaffSignIn.byPinOnly] costs ONE derivation for the whole shop, not one per admin.
     */
    suspend fun verifyAdminPin(pin: String): Boolean = withContext(Dispatchers.IO) {
        val admins = directory.all().filter { it.role.trim().lowercase() == "admin" }
        StaffSignIn.byPinOnly(admins, pin, directory.shopId()) is StaffSignInResult.Ok
    }

    /** True if a staff member has ever signed in on this device. */
    fun hasCloudAccount(): Boolean =
        cachedHasCloudAccount ?: runCatching { vault.hasAnyAccount() }
            .getOrDefault(false)
            .also { cachedHasCloudAccount = it }

    // ── local device PIN (no-cloud mode) ──────────────────────────────────
    // The optional single PIN that locks a phone-only till. Separate from staff sign-in
    // entirely — a different mechanism with a different salt (see [SessionVault.pbkdf2]
    // vs [StaffPin]) — so a shop with no cloud at all can still lock its till. All
    // hashing runs on IO; PBKDF2 is heavy on the Sunmi.

    suspend fun hasLocalPin(): Boolean = withContext(Dispatchers.IO) { vault.hasLocalPin() }

    suspend fun setLocalPin(pin: String) = withContext(Dispatchers.IO) { vault.setLocalPin(pin) }

    suspend fun verifyLocalPin(pin: String): Boolean =
        withContext(Dispatchers.IO) { vault.verifyLocalPin(pin) }

    suspend fun clearLocalPin() = withContext(Dispatchers.IO) { vault.clearLocalPin() }

    // ── identity for the sync layer ───────────────────────────────────────

    /**
     * Always null, and that is the whole contract now.
     *
     * [com.portionspot.pos.sync.SupabaseRest] reads null as "this device has no JWT — sign
     * with the anon key", which is exactly right: staff sign-in is a PIN against a row, not
     * a GoTrue session, so there has never been a token to send. The third value that layer
     * understands ([com.portionspot.pos.sync.SupabaseRest.SESSION_UNAVAILABLE], meaning
     * "a session exists but produced no token, REFUSE the request") is now unreachable from
     * here, because the condition it described cannot occur.
     *
     * Kept as a function rather than deleted: it is the seam the sync engine is built
     * against, and a shop that later puts its database behind authenticated RLS wires the
     * token back in here and nowhere else.
     */
    fun accessTokenOrNull(): String? = null

    /** No tokens, nothing to refresh. Kept because the sync layer calls it before every
     *  pass and that call site is the right place for a future re-auth hook. */
    suspend fun refreshIfNeeded() = Unit

    /**
     * Is the CURRENT device operating as an admin? Drives notification-audience gating
     * (which alerts raise a heads-up on THIS phone, and where they deep-link). Reads the
     * cached session's role straight from the vault so it works from a background pass with
     * no live UI. Local (phone-only) mode has no cached session — the local owner IS the
     * admin (see MainActivity's LOCAL mode) — so a null session reads as admin.
     */
    fun isDeviceAdmin(): Boolean =
        runCatching { vault.activeOrAnySession() }.getOrNull()?.role?.let { it == "admin" } ?: true

    // ── remote revocation ─────────────────────────────────────────────────

    /**
     * Re-fetch the signed-in member's own `staff` row and reconcile the cached session with
     * it, so a change the owner makes — a capability revoked, a demotion out of admin, a
     * deactivation, a deletion — actually reaches the cashier's phone. A till sits in the
     * foreground all day and never restarts, so this must NOT hang off app start alone; it
     * is driven by every successful sync pass and by regaining connectivity (see
     * [com.portionspot.pos.sync.SyncManager] and [com.portionspot.pos.sync.SyncWorker]) as
     * well as foreground and the manual button.
     *
     * ★ THE RULES ARE [PermissionRefresh]'S AND THEY DID NOT MOVE. Unreachable ⇒ keep the
     * cached grants, because a shop on a dead cell must keep selling. `active = false` ⇒
     * revoke now, on the first sighting, because that is the owner's own deliberate act.
     * A 200 with no rows ⇒ revoke only on the SECOND consecutive sighting, because an
     * absence has innocent causes (a rebuilt table, a tightened RLS policy) that answer
     * "no rows" for every cashier in the shop at once. Only the TABLE changed: `pos_staff`
     * did not exist, `staff` does.
     *
     * Nothing here is on a write path and nothing touches Room; the worst case is one small
     * GET that fails and changes nothing.
     */
    suspend fun refreshCurrentPermissions() = withContext(Dispatchers.IO) {
        val userId = activeUserId ?: return@withContext
        val cached = vault.sessionFor(userId) ?: return@withContext
        val conn = connectionProvider?.invoke() ?: return@withContext
        val shop = directory.shopId() ?: return@withContext
        val fetched = StaffAdminClient(conn).fetchProfile(shop, userId)
        when (val verdict = PermissionRefresh.verdict(fetched, cached.displayName, missingStrikes[userId] ?: 0)) {
            // Not evidence either way, so the strike count deliberately STANDS rather than
            // resetting — an unreachable pass must not let a deleted row start over.
            PermissionVerdict.KeepCached -> Unit
            // First sighting of a missing row: bank the strike and keep selling. Only a
            // second consecutive sighting revokes (see PermissionRefresh).
            PermissionVerdict.AwaitConfirmation ->
                missingStrikes[userId] = (missingStrikes[userId] ?: 0) + 1
            PermissionVerdict.RevokeAccount -> {
                missingStrikes.remove(userId)
                revokeAccount(userId)
            }
            is PermissionVerdict.Adopt -> {
                // The server showed us a live row: whatever we thought was missing is not.
                missingStrikes.remove(userId)
                val updated = PermissionRefresh.applyTo(verdict, cached)
                // Unchanged on the overwhelming majority of passes: skip the vault
                // re-encrypt (and the state churn) rather than rewriting it every ~45s.
                if (updated != cached) {
                    vault.updateSession(userId, updated)
                    val s = _state.value
                    if (s is AuthState.Active && s.user.id == userId) {
                        // Re-emit so AuthGate recomposes and pushes the new grants into the
                        // ViewModel — a revocation must land without the cashier signing out.
                        _state.value = AuthState.Active(updated.toUser())
                    }
                }
            }
        }
    }

    /**
     * Drop a staff member the server no longer recognises off this device, mid-shift if
     * need be.
     *
     * Deliberately the full session removal rather than a quiet downgrade. The revoked
     * person is put back on the sign-in screen, and the credential that screen checks is
     * the roster row the owner just switched off — so the next pull refuses them offline
     * too, and until it lands they have nothing cached to re-enter with. A deactivation
     * made in error self-heals the moment the owner reverses it.
     *
     * Local Room data is untouched (same contract as [signOut]); unsynced sales stay queued.
     */
    private fun revokeAccount(userId: String) {
        vault.removeAccount(userId)
        invalidateAccountCache()
        if (userId == activeUserId) activeUserId = null
        val onScreen = (_state.value as? AuthState.Active)?.user?.id == userId
        if (onScreen) {
            _state.value = AuthState.SignIn
            loadRoster()
        }
    }

    private fun CachedAuth.toUser() =
        PosUser(userId, email, role, displayName, Permissions.fromKeyMap(permissions))
}
