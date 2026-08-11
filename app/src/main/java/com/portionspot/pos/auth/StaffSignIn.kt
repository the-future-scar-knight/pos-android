package com.portionspot.pos.auth

import com.portionspot.pos.data.StaffMember

/**
 * The outcome of one sign-in attempt. Five values, not a boolean, because the shop
 * needs to be told which of five very different things went wrong — and telling a
 * cashier "wrong PIN" when the real answer is "the owner deactivated you" costs a phone
 * call and a wasted twenty minutes at the counter.
 */
sealed interface StaffSignInResult {

    /** In. [member] carries the role and grants the session should adopt. */
    data class Ok(val member: StaffMember) : StaffSignInResult

    /** No staff row on this device with that username. */
    object UnknownUser : StaffSignInResult

    /** The row exists but the owner has switched it off (or deleted it). */
    object Deactivated : StaffSignInResult

    /** The row exists and is live, but nobody ever set a PIN on it. */
    object NoPinSet : StaffSignInResult

    /** Right person, wrong PIN. */
    object WrongPin : StaffSignInResult

    /**
     * This device holds NO staff rows at all for the shop, so there is nothing to check
     * against — it has never completed a sync. Distinct from [UnknownUser] on purpose:
     * the fix is "connect this till once", not "check your spelling".
     */
    object NoRoster : StaffSignInResult
}

/**
 * Staff sign-in: a username and a PIN, checked against the roster this device holds.
 *
 * Pure — no network, no Room, no Android — so the rules below can be tested directly and
 * so the whole thing works with the aeroplane on. That is not an incidental property: the
 * shop trades through power cuts and dead cells, and the credential it signs in with is a
 * hash that is already on the device. A sign-in that needed the network would fail on
 * exactly the days it is needed most.
 *
 * ── WHY THIS REPLACED SUPABASE AUTH ──────────────────────────────────────────
 *
 * The previous path signed in against GoTrue and then read a `pos_staff` table. Three
 * things were wrong with it at once, and any one of them was fatal: the GoTrue URL was
 * hard-coded to a project the shop does not own, the client was never built from the
 * shop's saved connection, and `pos_staff` does not exist in the shared schema (the table
 * is `staff`). Meanwhile the web already models staff as `staff` rows with a PIN. Matching
 * it removes a whole service, removes tokens and refresh entirely, and makes offline the
 * default rather than a feature.
 *
 * ── THE ORDER OF THE CHECKS IS THE DESIGN ────────────────────────────────────
 *
 * Identity first, then state, then credential. [Deactivated] and [NoPinSet] are reported
 * WITHOUT the PIN being correct, and that is deliberate: this is a shop counter, not a
 * public login form. Everyone at the till already knows who works there, so withholding
 * "that account is switched off" protects nothing and costs the cashier a phone call to
 * the owner to find out what a clear message would have said.
 */
object StaffSignIn {

    /**
     * Sign in by typing a username.
     *
     * [roster] should be every row for the shop INCLUDING deactivated and tombstoned ones
     * — that is what lets [StaffSignInResult.Deactivated] be distinguished from
     * [StaffSignInResult.UnknownUser]. Matching is case- and whitespace-insensitive: a
     * keyboard that auto-capitalises "Ryan" has not produced a different person.
     */
    fun attempt(
        roster: List<StaffMember>,
        username: String,
        pin: String,
        businessId: String?,
    ): StaffSignInResult {
        if (roster.isEmpty()) return StaffSignInResult.NoRoster
        val typed = username.trim().lowercase()
        if (typed.isEmpty()) return StaffSignInResult.UnknownUser
        // Live rows first, so a shop that somehow holds two rows with one username
        // resolves to the usable one rather than to a tombstone.
        val match = roster
            .sortedWith(compareBy({ it.deleted }, { !it.active }))
            .firstOrNull { it.username.trim().lowercase() == typed }
            ?: return StaffSignInResult.UnknownUser
        return verify(match, pin, businessId)
    }

    /**
     * Sign in as an already-identified person — the roster picker's path, where the
     * cashier tapped their own name and only has a PIN left to type.
     */
    fun verify(member: StaffMember, pin: String, businessId: String?): StaffSignInResult {
        if (member.deleted || !member.active) return StaffSignInResult.Deactivated
        val stored = member.pinHash?.takeIf { it.isNotBlank() } ?: return StaffSignInResult.NoPinSet
        // ★ The member's OWN businessId is preferred over the caller's. The salt is derived
        // from the business id, so checking a row against the wrong shop's id derives a
        // digest that cannot match anything — a correct PIN would read as wrong, with no
        // way for anyone at the counter to tell why.
        val shop = member.businessId.ifBlank { businessId.orEmpty() }
        return if (StaffPin.matches(pin, stored, shop)) StaffSignInResult.Ok(member)
        else StaffSignInResult.WrongPin
    }

    /**
     * Try one PIN against the WHOLE roster and return whoever it belongs to — the
     * "just type your PIN" pad, with no username at all.
     *
     * One derivation for the entire shop, not one per person: every row shares the salt
     * (it comes from the business id), so [StaffPin.checker] hashes the candidate once and
     * compares the digest against each stored hash. Without that, a twenty-cashier shop
     * would spend two seconds of PBKDF2 per keypress-complete on a Sunmi.
     *
     * ★ It only ever considers ACTIVE rows. A PIN pad with no username is the one place
     * where a deactivated person's hash still sitting on the device could let them back
     * in, so deactivated rows are not even candidates — the caller gets
     * [StaffSignInResult.UnknownUser], which is the truth for this entry mode.
     */
    fun byPinOnly(roster: List<StaffMember>, pin: String, businessId: String?): StaffSignInResult {
        if (roster.isEmpty()) return StaffSignInResult.NoRoster
        val live = roster.filter { it.active && !it.deleted && !it.pinHash.isNullOrBlank() }
        if (live.isEmpty()) return StaffSignInResult.NoPinSet
        val shop = live.first().businessId.ifBlank { businessId.orEmpty() }
        val check = StaffPin.checker(pin, shop)
        val hit = live.firstOrNull { check(it.pinHash) } ?: return StaffSignInResult.WrongPin
        return StaffSignInResult.Ok(hit)
    }

    /** A human message for a failed attempt, so every caller phrases it the same way. */
    fun message(result: StaffSignInResult): String = when (result) {
        is StaffSignInResult.Ok -> ""
        StaffSignInResult.UnknownUser -> "No staff member with that username on this till"
        StaffSignInResult.Deactivated -> "That account has been switched off — ask the owner"
        StaffSignInResult.NoPinSet -> "No PIN has been set for that account yet"
        StaffSignInResult.WrongPin -> "Wrong PIN"
        StaffSignInResult.NoRoster ->
            "This till hasn't loaded the staff list yet — connect it and sync once"
    }
}

/**
 * The signed-in staff row as the rest of the app already expects to see a user.
 *
 * `email` carries the USERNAME. There is no email column on `staff` and there is no
 * mailbox behind it either — the field is a label on the lock screen and an attribution
 * string on a receipt, and the username is the honest thing to put there. Renaming the
 * field is a separate, mechanical change across [PosUser]'s call sites; see
 * `docs/STAFF-PIN-AUTH-PLAN.md`.
 */
fun StaffMember.toPosUser(): PosUser = PosUser(
    id = id,
    email = username,
    role = role,
    displayName = name.ifBlank { username },
    permissions = Permissions.fromJsonString(permissions),
)
