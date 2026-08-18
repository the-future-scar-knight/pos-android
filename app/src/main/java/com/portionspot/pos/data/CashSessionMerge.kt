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
fun cashMovementTypeToWire(localType: String, location: String, amount: Double): String =
    when (localType.trim().lowercase()) {
        "drop" -> "drop"
        "petty", "petty_cash" -> "petty"
        "float_topup", "float", "topup" -> "float_topup"
        "safe_in" -> "safe_in"
        "bank_deposit", "deposit", "bank" -> "bank_deposit"
        // A customer settling a debt in cash. `pay_in` is not a fallback here, it is the
        // right word: money arriving at the till for a reason other than a sale is exactly
        // what the shared cash-up means by a pay-in, and a repayment has no other
        // representation on that side at all (see [cashMovementCountedElsewhere]).
        "credit_payment" -> "pay_in"
        else -> when {
            location.equals(CashLocation.SAFE, ignoreCase = true) && amount > 0 -> "safe_in"
            // ★ THE OTHER HALF OF THE SAFE, and the reason `safe_out` had to exist. Money
            // leaving the safe used to go up as a bare `pay_out` — indistinguishable on the
            // wire from money leaving the till — so it came back down against the TILL on
            // every other device. A float top-up made on one phone left the second phone's
            // till short and its safe over by the same amount, and only a physical count
            // ever put it right. `safe_in` had a word and its mirror image did not.
            location.equals(CashLocation.SAFE, ignoreCase = true) -> "safe_out"
            amount >= 0 -> "pay_in"
            else -> "pay_out"
        }
    }

/**
 * What one `cash_movements` row from the shared schema means to THIS app's ledger: the
 * local [CashTxn.type], WHICH pocket the money moved in or out of, and the SIGN.
 */
data class CashMovementFromWire(
    val type: String,
    /** A [CashLocation] constant — never the raw wire word. */
    val location: String,
    /** SIGNED in this app's convention: + INTO [location], − OUT of it. */
    val amount: Double,
    /**
     * The OTHER half, when one wire row means money left one pocket and arrived in
     * another. The web writes a till→safe drop as a single row; this app keeps one row per
     * location, so the caller writes both or the money half-vanishes. Null for the
     * movements that genuinely touch one pocket.
     */
    val counterpart: CashMovementFromWire? = null,
)

/**
 * The inverse of [cashMovementTypeToWire] — the function that lets a pulled movement land
 * in the right pocket, in the right direction, instead of quietly inverting it.
 *
 * Two facts have to be REBUILT here, because the wire carries neither:
 *
 *  - **The sign.** `amount` goes up as a MAGNITUDE with the direction encoded in `type`
 *    (see [com.portionspot.pos.sync.wire.CashMovementPushDto], which spells out why).
 *    Take the wire figure at face value and a $5 payout made on the other phone ADDS $5
 *    to this one's drawer — the same double-inversion the push guards against, arriving
 *    from the other end.
 *  - **The location.** There is no `location` column at all; TILL / SAFE is derived from
 *    the type, so it has to be derived back.
 *
 * The five types [cashMovementTypeToWire] passes through verbatim come back verbatim, so a
 * movement that leaves one phone and lands on another is the SAME row on both, and a row
 * that goes up a second time goes up under the word it went up under the first time.
 * `pay_in` / `pay_out` are the two the push COLLAPSES onto and they cannot be un-collapsed:
 * they mean no more than "money in" / "money out", so that is exactly what they become,
 * and the movement's `reason` — kept as the local `note` — is what still says why.
 *
 * `drop` and `bank_deposit` reduce the TILL and credit nothing, which is what they mean on
 * the side that writes them: the web has no safe, so its drop is simply cash out of the
 * drawer. A till→safe move made on THIS app never arrives as a `drop` — it goes up as the
 * transfer PAIR it is (`pay_out` + `safe_in`), so both halves travel and both balances move.
 *
 * ★ `safe_out` IS A WORD THE SHARED VOCABULARY DID NOT HAVE, and adding it is what closed
 * the last hole in this mapping. Money leaving the SAFE used to go up as a bare `pay_out`,
 * indistinguishable from money leaving the till, and came back down against the TILL: a
 * float top-up made on one phone left the second phone's till short and its safe over by
 * the same amount, for ever, because nothing but a physical count ever corrected it. Cash
 * on hand agreed to the cent the whole time, which is precisely what made it hard to see.
 *
 * The alternative considered and rejected was to rebuild the transfer PAIR from a single
 * `float_topup` — reconstructing the safe half on the way in. It fixes only the paired
 * case, leaves a safe-FUNDED expense (which has no till half at all) still landing on the
 * drawer, and needs a derived row id that the originating phone must then be careful not
 * to double-count. One symmetrical word costs a line of SQL and fixes both.
 *
 * ★★ SEQUENCING. The CHECK constraint fails the WHOLE BATCH, not the offending row, so no
 * client may emit `safe_out` until the constraint allows it. The `alter table … add
 * constraint` lives in [com.portionspot.pos.sync.SupabaseSetupSql]; run it against a
 * project BEFORE a build that speaks this word points at it, or every cash push stops.
 */
fun cashMovementTypeFromWire(wireType: String, amount: Double): CashMovementFromWire {
    val magnitude = kotlin.math.abs(amount)
    return when (wireType.trim().lowercase()) {
        "safe_in" -> CashMovementFromWire("safe_in", CashLocation.SAFE, magnitude)
        "safe_out" -> CashMovementFromWire("safe_out", CashLocation.SAFE, -magnitude)
        "pay_in" -> CashMovementFromWire("pay_in", CashLocation.TILL, magnitude)
        "petty" -> CashMovementFromWire("petty", CashLocation.TILL, -magnitude)
        "pay_out" -> CashMovementFromWire("pay_out", CashLocation.TILL, -magnitude)
        // ── The three that move money between TWO pockets in a single row ──
        //
        // ★ THESE ARE THE WEB'S OWN WORDS AND THEY ALL USED TO LAND ON THE TILL. The web
        // keeps a full safe and writes each of these as ONE row meaning both halves:
        // `drop` is till→safe, `float_topup` is safe→till, `bank_deposit` is safe→out.
        // Read as till-only, a browser bank deposit made the phone's expected drawer read
        // short by the whole deposit, and a drop or a top-up broke the phone's TOTAL cash
        // on hand — not merely the split — because one half of the move simply vanished.
        //
        // Adopting the web's meaning is safe precisely because THIS APP NEVER EMITS THESE
        // WORDS: its own transfers go up as the pair `pay_out` + `safe_in` (or `safe_out` +
        // `pay_in`), and a grep of every `CashTxn(type = …)` in the repository finds no
        // drop, float_topup, bank_deposit or petty. So a row bearing one of them was
        // written by the other client, and there is no risk of a device re-importing and
        // doubling its own movement. [counterpart] is the second local row the caller must
        // write; null when the movement really does touch one pocket only.
        "drop" -> CashMovementFromWire(
            "drop", CashLocation.TILL, -magnitude,
            counterpart = CashMovementFromWire("drop", CashLocation.SAFE, magnitude),
        )
        "float_topup" -> CashMovementFromWire(
            "float_topup", CashLocation.TILL, magnitude,
            counterpart = CashMovementFromWire("float_topup", CashLocation.SAFE, -magnitude),
        )
        // Out of the SAFE and out of the business — the till is not involved at all, which
        // is the one this got most wrong.
        "bank_deposit" -> CashMovementFromWire("bank_deposit", CashLocation.SAFE, -magnitude)
        // The CHECK constraint says this cannot arrive, and the day it does is the day the
        // constraint was relaxed on the other side without anyone telling this one. The
        // money still moved, so it is booked as an `adjust` at the till in whatever
        // direction the row was actually written in — the raw signed figure, since with no
        // recognisable type there is no direction to restore. Dropping the row instead
        // would take real cash out of one phone's drawer and leave it in another's, which
        // is the failure this whole pull exists to end.
        else -> CashMovementFromWire("adjust", CashLocation.TILL, amount)
    }
}
