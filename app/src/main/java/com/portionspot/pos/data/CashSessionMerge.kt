package com.portionspot.pos.data

/**
 * The rule that decides which open cash session survives when a shop ends up with more
 * than one — and the reason it is a pure function in its own file.
 *
 * ── WHY THERE CAN BE TWO ──────────────────────────────────────────────────────
 *
 * The shared database enforces one open session per SHOP:
 *
 *     create unique index uq_cash_sessions_one_open
 *         on public.cash_sessions (business_id)
 *         where status = 'open' and deleted = false;
 *
 * That constraint is necessary and it is not sufficient. Two counters that are offline
 * from each other both ask "is a shift already open?", both correctly get "no", and both
 * open one. Postgres then rejects the second on push — and rejecting it only MOVES the
 * problem, because that till's sales already reference a session id the cloud has never
 * heard of, and a cash-up that cannot resolve a session silently leaves those sales out.
 * The shop counts its drawer and the number is wrong, with nothing anywhere saying why.
 *
 * ── WHY IT MUST BE THIS EXACT RULE ────────────────────────────────────────────
 *
 * Both clients settle it AFTER the pull that finally shows them each other, and they
 * must reach the same answer WITHOUT talking to each other:
 *
 *     winner = the open session with the oldest openedAt, ties broken by id
 *
 * If Android adopts a different rule from the web — newest wins, or ties broken the
 * other way — the two clients each pick a different survivor and spend every subsequent
 * pass repointing sales onto the other's loser. That failure never stops and never
 * raises an error. Matching the web here is not a preference, it is the whole mechanism.
 *
 * The loser is CLOSED, never deleted, and its rows are moved onto the winner: someone
 * really did open a drawer and take real money, and a cash-up that is missing a shift is
 * harder to trust than one that shows a merged one.
 */

/** The least a row needs for [pickSurvivingSession] to rank it. */
interface MergeableSession {
    val id: String
    val openedAt: Long
}

/**
 * The session that survives a merge, or null when there is nothing to merge.
 *
 * ★ TIE-BREAK PRECISION IS A REAL HAZARD, not a theoretical one. `openedAt` is epoch
 * MILLIS here and `cash_sessions.opened_at` is a Postgres `timestamptz`, which carries
 * MICROseconds. Two sessions whose open times differ only below a millisecond are a TIE
 * on this side and an ordering on the web's — so each would pick a different winner,
 * which is exactly the divergence this rule exists to prevent. Android only ever writes
 * millisecond-truncated values, so this can only bite for two sessions opened inside the
 * same millisecond by two different clients. Flagged in the handoff rather than papered
 * over: the fix belongs on the wire (agree to compare at millisecond precision), not in
 * a client guessing.
 */
fun <T : MergeableSession> pickSurvivingSession(open: List<T>): T? =
    open.minWithOrNull(compareBy({ it.openedAt }, { it.id }))

/**
 * Split the open sessions into the one that survives and the ones to be merged into it.
 *
 * Returns null when there is no conflict to resolve — zero sessions, or the single open
 * session that is the normal state of a trading shop. Callers should treat a null as
 * "nothing to do" rather than an error, because it is by far the common case and running
 * a merge that moves no rows is not free.
 */
fun <T : MergeableSession> planSessionMerge(open: List<T>): SessionMerge<T>? {
    val live = open.distinctBy { it.id }
    if (live.size < 2) return null
    val winner = pickSurvivingSession(live) ?: return null
    return SessionMerge(winner = winner, losers = live.filter { it.id != winner.id })
}

/**
 * The outcome of [planSessionMerge]: everything referencing a [losers] id — sales,
 * refunds and cash movements — is repointed onto [winner] and pushed in the SAME pass
 * that closes the losers, so no window exists where a sale points at a closed session.
 */
data class SessionMerge<T : MergeableSession>(
    val winner: T,
    val losers: List<T>,
) {
    /** The ids being merged away — the set to repoint `session_id` off. */
    val loserIds: List<String> get() = losers.map { it.id }

    /** What to write on a merged-away session so the shop can see what happened to it.
     *  A closed session with no explanation is indistinguishable from a lost shift. */
    fun closingNote(loser: T): String =
        "Merged into shift ${winner.id.take(8)} (two tills opened a shift while offline)"
}

/**
 * Which of the shared schema's movement types a cash movement is, given how this app
 * models the same money. The cloud CHECK-constrains the vocabulary:
 *
 *     pay_in · pay_out · drop · petty · float_topup · safe_in · bank_deposit
 *
 * and has NO `location` column — TILL / SAFE / OUTSIDE is DERIVED from the type. This
 * app stores the location explicitly on [CashTxn.location], so the mapping has to be
 * made rather than copied.
 *
 * A CHECK violation fails the whole batch, not the offending row, so an unmapped type
 * must resolve to a legal value rather than travel as-is. Unknown movements land on
 * `pay_in`/`pay_out` by sign, which is the honest fallback: the money definitely moved
 * in that direction, and the shop can see it, even if the finer category is lost.
 */
/**
 * Is this movement's cash ALREADY counted by the other client, from the sale or refund
 * it belongs to?
 *
 * The shared cash-up derives takings from the tenders (`sale_payments`), the change from
 * `sales.change_due` and payouts from `refund_payments`, and its own screen says so:
 * "Only cash that moves for a reason other than a sale. Sales, refunds and change are
 * already counted." This app ALSO writes a drawer movement for each of those, because it
 * models cash-on-hand as one ledger — so pushing them adds a second copy of money the
 * shop already counted once, and the expected drawer comes out double.
 *
 * Everything else — a pay-in, a petty spend, a bank drop, a float top-up — has no other
 * representation on that side and must go up, or a shop doing its cash-up on the web
 * cannot see cash a phone moved.
 */
fun cashMovementCountedElsewhere(refType: String?): Boolean =
    refType == "sale" || refType == "refund"

fun cashMovementTypeToWire(localType: String, location: String, amount: Double): String =
    when (localType.trim().lowercase()) {
        "drop" -> "drop"
        "petty", "petty_cash" -> "petty"
        "float_topup", "float", "topup" -> "float_topup"
        "safe_in" -> "safe_in"
        "bank_deposit", "deposit", "bank" -> "bank_deposit"
        else -> when {
            location.equals(CashLocation.SAFE, ignoreCase = true) && amount > 0 -> "safe_in"
            amount >= 0 -> "pay_in"
            else -> "pay_out"
        }
    }
