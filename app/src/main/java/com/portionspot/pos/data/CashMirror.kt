package com.portionspot.pos.data

/**
 * MAKING ANOTHER TILL'S CASH SHOW UP IN THIS TILL'S DRAWER.
 *
 * ── THE BUG THIS EXISTS FOR ───────────────────────────────────────────────────
 *
 * A pull writes another phone's sale, its lines and its tenders. It does NOT write a
 * [CashTxn] — every cash-ledger insert in the app is a local user action. So with one
 * shared drawer and two phones, phone A's cash-on-hand shows the day's takings and
 * phone B's shows almost nothing, while the physical drawer holds both.
 *
 * That is not a cosmetic gap. Whoever closes the day counts the REAL drawer against an
 * expected figure covering only their own phone's sales, so the count comes out "over" by
 * exactly the other phone's takings — and [PosRepository.closeDay] writes that difference
 * permanently as a `variance` [CashTxn], which by the owner's rule is a hit to profit.
 * A perfectly balanced day is recorded as a windfall, or a shortage is hidden underneath
 * one, and nothing anywhere says why.
 *
 * ── WHY IT RECONCILES SUMS RATHER THAN INSERTING ON PULL ──────────────────────
 *
 * The obvious fix — "write a cash row when you pull a sale" — is wrong twice over. It
 * double-writes on the phone that rang the sale up (which already wrote its row at
 * checkout), and it re-writes on every later pull of the same row. The obvious patch for
 * THAT — "insert only if no row exists for this sale" — is still wrong, because a sale is
 * not the only thing that moves cash against a sale id: a refund accumulates a payout row
 * per instalment, and voiding one writes a REVERSING row against the same refund id.
 * Existence tells you nothing about how much.
 *
 * So the rule is stated as a balance, not as an event:
 *
 *     for each (refType, refId): expected net cash − what the ledger already holds
 *
 * and the difference, if any, is written as one row. That is idempotent by construction —
 * the second run sums the row it just wrote and finds a difference of zero — and it is
 * self-healing rather than merely safe: a device that pulled a refund and later pulls the
 * void of it converges to the same ledger as the device that did the voiding, without
 * either of them knowing what the other did. Nothing is ever deleted or rewritten, so no
 * row's `pendingSync` churns and nothing gets re-uploaded.
 *
 * ── WHY THESE ROWS NEVER GO BACK UP ───────────────────────────────────────────
 *
 * They carry `refType` of `sale` or `refund`, which [cashMovementCountedElsewhere] already
 * excludes from the `cash_movements` push — the shared cash-up derives a sale's cash from
 * the tenders and a refund's from the payouts, so uploading these would double the drawer
 * on the other side. That exclusion is a precondition of this file, not a coincidence: any
 * new `refType` introduced here would have to be added there in the same change.
 */

/** A running total of cash already booked against one source row. */
data class CashRefSum(
    val refId: String,
    val total: Double,
)

/**
 * What the cash ledger OUGHT to hold against one sale or refund, with the few facts a
 * mirrored row needs in order to read like the local one it stands in for.
 *
 * [at] is the source row's own timestamp — the sale's `soldAt`, the refund's `createdAt` —
 * and never the moment of the sync. A movement stamped with the pull time lands in the
 * wrong trading day whenever a phone comes back online after midnight, which puts the
 * discrepancy in the wrong day's cash-up: precisely the class of error this file exists to
 * remove.
 */
data class ExpectedCashRow(
    val refId: String,
    val total: Double,
    val at: Long,
    val label: String?,
    val createdBy: String?,
    val createdByName: String?,
)

/** One cash-ledger row the reconciliation wants written. */
data class CashMirrorRow(
    val refType: String,
    val refId: String,
    /** The DIFFERENCE, not the gross figure — this is a correction to a running total. */
    val amount: Double,
    val at: Long,
    val label: String?,
    val createdBy: String?,
    val createdByName: String?,
)

/** Half a cent — the same tolerance the repository compares money with. */
private const val MIRROR_CENT = 0.005

/**
 * The rows needed to bring the cash ledger into line with what actually happened.
 *
 * [expected] is what the source rows say should be in the drawer against each id;
 * [existing] is what the ledger already holds against them. Only the difference is
 * emitted, and only when it is worth more than half a cent — otherwise two clients whose
 * doubles differ in the last bit would write each other correction rows forever.
 *
 * ★ [reverseOrphans] is the void case and it is deliberately not symmetric.
 *
 * When true, an id the ledger holds cash against but that no longer appears in [expected]
 * is zeroed out. That is exactly right for REFUNDS: voiding one soft-deletes it and the
 * voiding phone writes a reversing row itself, so a phone that had already mirrored the
 * payout must write the same reversal to converge. Without it, a voided refund's cash
 * stays out of one drawer forever.
 *
 * When false, an unrecognised id is LEFT ALONE. That is right for SALES, because "not in
 * the expected set" covers more than deletion — a parked sale, a quote, a sale mid-edit —
 * and reversing real takings on the strength of a row not appearing in a query is a way to
 * lose money that no one would ever trace back to here. A sale that genuinely needs
 * reversing is reversed by the code that reverses it.
 *
 * Output is sorted by id so two devices planning from the same data plan the same thing in
 * the same order, which is what makes the behaviour reproducible in a test.
 */
fun planCashMirror(
    refType: String,
    expected: List<ExpectedCashRow>,
    existing: List<CashRefSum>,
    reverseOrphans: Boolean,
): List<CashMirrorRow> {
    val held = existing.associate { it.refId to it.total }
    val out = mutableListOf<CashMirrorRow>()
    for (row in expected) {
        val delta = row.total - (held[row.refId] ?: 0.0)
        if (kotlin.math.abs(delta) <= MIRROR_CENT) continue
        out += CashMirrorRow(
            refType = refType,
            refId = row.refId,
            amount = delta,
            at = row.at,
            label = row.label,
            createdBy = row.createdBy,
            createdByName = row.createdByName,
        )
    }
    if (reverseOrphans) {
        val known = expected.mapTo(HashSet<String>()) { it.refId }
        for ((refId, total) in held) {
            if (refId in known) continue
            if (kotlin.math.abs(total) <= MIRROR_CENT) continue
            // No source row left to read a timestamp or an author off, so the reversal
            // carries none. `at` is filled by the caller from the clock — a correction for
            // something that no longer exists genuinely happened now.
            out += CashMirrorRow(
                refType = refType,
                refId = refId,
                amount = -total,
                at = 0L,
                label = null,
                createdBy = null,
                createdByName = null,
            )
        }
    }
    return out.sortedBy { it.refId }
}

/**
 * Net cash a sale put in the drawer: cash tenders received, less the change actually
 * handed back.
 *
 * The same arithmetic [PosRepository.checkout] does at the till (`cashTendered −
 * changeGivenActual`), restated here so the reconciliation and the original write cannot
 * drift apart — if they did, every sync would find a difference and write a correction row
 * for it, forever. Card and mobile-money tenders never open the drawer and are excluded;
 * `changeDue` on the sale IS the amount actually handed over, not the amount owed.
 */
fun netCashForSale(cashTendered: Double, changeGiven: Double): Double =
    cashTendered - changeGiven
