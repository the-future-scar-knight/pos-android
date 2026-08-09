package com.portionspot.pos.auth

/**
 * The outcome of ONE attempt to re-read the signed-in staff member's own `pos_staff`
 * row. Three-valued on purpose: the whole point of the remote-revocation path is that
 * "the server says this person is gone" and "I couldn't ask the server" must never be
 * the same value. Collapsing them into a nullable row is what let a deleted staff
 * member keep their grants forever, indistinguishable from a passing network blip.
 */
sealed interface StaffProfileFetch {
    /** The server answered and the row exists (it may still say `active = false`). */
    data class Found(val profile: StaffProfileDto) : StaffProfileFetch

    /** The server answered 200 with ZERO rows. A verdict: see [PermissionRefresh]. */
    object Missing : StaffProfileFetch

    /** No answer at all — offline, 401, 5xx, unparseable body. NOT a verdict. */
    object Unreachable : StaffProfileFetch
}

/** What the device must do with the cached session after one refresh attempt. */
sealed interface PermissionVerdict {
    /**
     * Adopt the server's view of this staff member. Carries the three fields the shop
     * owner can change remotely: capability grants, the cashier/admin role, and the
     * display name that gets stamped on receipts and attribution.
     */
    data class Adopt(
        val role: String,
        val displayName: String,
        val permissions: Map<String, Boolean>,
    ) : PermissionVerdict

    /** Couldn't reach the truth: keep whatever is cached and try again next pass. */
    object KeepCached : PermissionVerdict

    /**
     * FIRST sighting of a missing row. Keep the cached session for now, but remember the
     * sighting so the NEXT pass can act on it. See the two-strike rule in
     * [PermissionRefresh] — this is the strike, not the verdict.
     */
    object AwaitConfirmation : PermissionVerdict

    /** This person is no longer POS staff: the account must leave this device. */
    object RevokeAccount : PermissionVerdict
}

/**
 * The fail-safe rule for a remote permission change, kept as pure logic so it can be
 * tested without a network, a vault, or Android.
 *
 * The dangerous direction here is failing OPEN — a till that keeps honouring grants the
 * owner has already taken away. Every branch below is chosen against that:
 *
 *  - [StaffProfileFetch.Unreachable] ⇒ [PermissionVerdict.KeepCached]. Offline-first is
 *    the defining property of this app: a shop on a dead 4G cell must keep selling with
 *    the grants it last knew about. A network failure is not evidence of anything, and
 *    treating it as a revocation would brick a working till every time the signal drops.
 *
 *  - `active = false` ⇒ [PermissionVerdict.RevokeAccount], immediately. A deactivated
 *    cashier who kept working was the second half of the fail-open bug: the old refresh
 *    copied role/name/permissions and ignored `active` entirely. This one acts on the
 *    FIRST sighting because the row is right there in front of us and it is the shop
 *    owner's own deliberate act — there is nothing ambiguous left to confirm.
 *
 *  - [StaffProfileFetch.Missing] ⇒ revoke, but only on the SECOND consecutive sighting
 *    ([PermissionVerdict.AwaitConfirmation] first). A 200 with no row IS evidence the
 *    server was reached and does not know this person — but unlike `active = false` it is
 *    evidence of an ABSENCE, and an absence has innocent causes that a present row does
 *    not: `pos_staff` recreated by a re-run of the setup SQL, a tightened RLS policy, the
 *    app pointed at a fresh project. Any of those answers 200-with-no-rows for EVERY
 *    cashier at once, and acting on the first sighting would wipe every account off every
 *    till in the shop simultaneously — with no way back until someone is online with a
 *    password. In Harare that is a dead shop, and it is a far worse failure than one
 *    sacked cashier keeping their grants for another sync cycle.
 *
 *    A genuinely deleted row stays deleted, so the second strike lands on the next pass
 *    (~15 minutes on the periodic sync, sooner on a foreground or manual sync). A blip
 *    does not survive to a second consecutive pass. Only a reachable-but-still-absent row
 *    can cross the line, and any successful read of a live row clears the count.
 *
 *  - A blank role narrows to "cashier" rather than inheriting the cached one. Nonsense
 *    data must never WIDEN a person's powers; the worst it can do is make them ask.
 *
 * Revocation is deliberately account-removal rather than a quieter downgrade: the vault
 * holds an offline-unlock PIN, so anything short of removing the account would let the
 * revoked person keep unlocking the till offline forever. Re-adding the account needs a
 * fresh online password login, and that path already refuses an inactive profile
 * (see [AuthManager.login]), so a genuine mistake self-heals the moment the owner
 * reactivates them. Local Room data is never touched by any of this.
 */
object PermissionRefresh {

    /** The role assumed when the server sends one we can't read. Narrowest possible. */
    const val FALLBACK_ROLE = "cashier"

    /** Consecutive missing-row sightings required before an account is removed. Set to 1
     *  to revoke on the first sighting (faster, but see the shop-wide wipe risk above). */
    const val STRIKES_BEFORE_REVOKE = 2

    /**
     * [missingStrikes] is how many CONSECUTIVE times this device has already been told
     * the row is missing, before this attempt. Zero on a fresh session and reset by any
     * successful read of a live row. An [StaffProfileFetch.Unreachable] pass must neither
     * increment nor clear it: it is not evidence in either direction, so the count simply
     * stands until the server can be reached again.
     */
    fun verdict(
        fetch: StaffProfileFetch,
        cachedDisplayName: String,
        missingStrikes: Int = 0,
    ): PermissionVerdict =
        when (fetch) {
            StaffProfileFetch.Unreachable -> PermissionVerdict.KeepCached
            StaffProfileFetch.Missing ->
                if (missingStrikes >= STRIKES_BEFORE_REVOKE - 1) PermissionVerdict.RevokeAccount
                else PermissionVerdict.AwaitConfirmation
            is StaffProfileFetch.Found -> {
                val p = fetch.profile
                if (!p.active) {
                    PermissionVerdict.RevokeAccount
                } else {
                    PermissionVerdict.Adopt(
                        role = p.role.trim().ifBlank { FALLBACK_ROLE },
                        // A blank display name is a legacy row, not a rename to nothing:
                        // keep the name already on this device so receipts stay attributed.
                        displayName = p.displayName.ifBlank { cachedDisplayName },
                        permissions = Permissions.jsonToKeyMap(p.permissions),
                    )
                }
            }
        }

    /**
     * The write-through: fold an [PermissionVerdict.Adopt] into the cached session,
     * leaving tokens, expiry, email and id alone. Returning a value equal to [cached]
     * when nothing moved is what lets the caller skip a vault re-encrypt (and a state
     * re-emission) on every one of the ~45-second foreground passes.
     */
    fun applyTo(adopt: PermissionVerdict.Adopt, cached: CachedAuth): CachedAuth =
        cached.copy(
            role = adopt.role,
            displayName = adopt.displayName,
            permissions = adopt.permissions,
        )
}
