package com.portionspot.pos.data

/**
 * CASH-BASIS revenue and profit (§5) — the shop counts money when it ARRIVES.
 *
 * ══ What changed and why ══
 * The books used to recognise a sale in full the moment it was rung up, credit or not.
 * For a shop that sells on account that is a lie: the receipt says $100 but the drawer
 * saw nothing, and the dashboard reported profit the owner could not spend. Under the
 * cash basis a sale still records the receipt, still moves stock and still books the
 * debt — it simply does not count toward revenue or profit until the money is collected.
 *
 * ══ The rules ══
 *  1. A sale contributes what was COLLECTED ON THE DAY IT WAS SOLD:
 *     `collectedAtSale = clamp(amountPaid, 0, total)`.
 *     Clamping at [CashBasisSaleRow.total] is what keeps CHANGE OWED out of revenue: a
 *     customer who hands over $100 for a $90 sale has bought $90 of goods, and the $10
 *     the shop could not give back is a liability, not takings.
 *  2. A later repayment (a `credit_paid` ledger row) contributes ON ITS OWN DAY, matched
 *     FIFO back to the credit lots it settles.
 *  3. COST OF GOODS IS PRO-RATED to the collected share, so a part-paid sale can never
 *     show a fake loss. A $100 sale costing $60 with $40 collected contributes revenue
 *     40, cost 24, profit 16 — the ratio 40/100 applied to both sides.
 *
 * ══ ★ Why this cannot double-count ══
 * Every dollar of a sale is recognised through exactly ONE of two doors, and the two
 * doors are disjoint by construction:
 *   • the collected-at-sale door is `min(amountPaid, total)`;
 *   • the repayment door only ever consumes CREDIT LOTS, and a credit lot is by
 *     definition the part of the sale that was NOT paid at the till (`total − paid`).
 * The FIFO matcher then hands each repaid dollar to at most one lot, and each lot to at
 * most one sale, so the lifetime contribution of a sale is
 *     `collectedAtSale + Σ(repayments matched to its lots)  ≤  total`
 * and equals exactly what was collected on it. [CashBasisTest] asserts this invariant
 * directly, including for split, over- and part-payments.
 *
 * ══ Deliberate exclusions ══
 *  - A DEBT WRITE-OFF is a `credit_paid` row too, but no money arrived. It still CONSUMES
 *    its lot (the debt really is gone) and contributes ZERO revenue. Recognised by its
 *    note, [WRITE_OFF_NOTE] — the same string [PosRepository.writeOffDebt] writes.
 *  - A credit lot with NO linked sale (the "over-given change" correction booked as
 *    `credit_owed`) contributes nothing when repaid. It is the recovery of a till
 *    shortage, not a sale, and pretending otherwise would invent revenue with no goods
 *    behind it.
 *
 * Pure and side-effect free on purpose: no Room, no Android, no clock. That is what
 * makes the no-double-count invariant testable rather than asserted.
 */
object CashBasis {

    /** The note [PosRepository.writeOffDebt] stamps. A write-off is not a collection. */
    const val WRITE_OFF_NOTE = "Debt write-off"

    /** Half-a-cent tolerance, matching the repository's money comparisons. */
    private const val CENT = 0.005

    /**
     * Cash-basis figures for one window.
     *
     *  - [revenue]     — money actually collected in the window, from same-day settlement
     *                    and from repayments of older credit.
     *  - [cogs]        — cost of the goods behind that collected revenue (pro-rated).
     *  - [grossProfit] — [revenue] less [cogs], over costed lines only.
     *  - [costedRevenue] — the slice of [revenue] that came from lines carrying a cost;
     *                    the honest denominator for a margin percentage.
     *  - [uncollected] — sale value BILLED in the window that has not been collected yet.
     *                    Shown as a figure/alert, never as sales.
     */
    data class Period(
        val revenue: Double = 0.0,
        val cogs: Double = 0.0,
        val grossProfit: Double = 0.0,
        val costedRevenue: Double = 0.0,
        val uncollected: Double = 0.0
    )

    /** What one sale contributed, and when — the unit the invariant is asserted over. */
    data class Contribution(
        val saleId: String,
        val at: Long,
        val amount: Double,
        val costedShare: Double,
        val profitShare: Double,
        val cogsShare: Double
    )

    /**
     * Every recognition event over ALL time, oldest first. [compute] is a windowed sum of
     * these; the tests walk them to prove no sale is ever recognised beyond what it
     * collected. [sales] must be the shop's live COMPLETED sales; [ledger] its whole
     * credit ledger (both are what the repository's flows already provide).
     */
    fun contributions(
        sales: List<CashBasisSaleRow>,
        ledger: List<CreditTxn>
    ): List<Contribution> {
        val byId = sales.associateBy { it.id }
        val out = ArrayList<Contribution>(sales.size + ledger.size)

        // ── Door 1: what was settled on the day of the sale. ──
        for (s in sales) {
            val collected = s.amountPaid.coerceIn(0.0, s.total.coerceAtLeast(0.0))
            if (collected <= CENT) continue
            out += contributionOf(s, collected, s.soldAt)
        }

        // ── Door 2: repayments, FIFO-matched to the credit lots they settle. ──
        // Walk the ledger chronologically PER CUSTOMER, keeping a queue of open lots.
        // Only credit_owed / credit_paid participate: change_owed / refund_owed and
        // their payouts are the shop's "we owe you" balance, a different ledger entirely.
        val lots = HashMap<String, ArrayDeque<Lot>>()
        for (row in ledger.sortedBy { it.createdAt }) {
            if (row.deleted) continue
            when (row.type) {
                "credit_owed" -> {
                    if (row.amount <= CENT) continue
                    lots.getOrPut(row.customerId) { ArrayDeque() }
                        .addLast(Lot(row.saleId, row.amount))
                }
                "credit_paid" -> {
                    var left = row.amount
                    if (left <= CENT) continue
                    // A write-off clears the debt but is NOT money — it consumes lots and
                    // recognises nothing.
                    val isCollection = row.note?.trim() != WRITE_OFF_NOTE
                    val queue = lots[row.customerId] ?: continue
                    while (left > CENT && queue.isNotEmpty()) {
                        val lot = queue.first()
                        val take = minOf(left, lot.remaining)
                        lot.remaining -= take
                        left -= take
                        if (lot.remaining <= CENT) queue.removeFirst()
                        if (!isCollection || take <= CENT) continue
                        // A lot with no sale behind it (an over-given-change correction)
                        // recovers a till shortage — real money, but not a sale. It
                        // recognises nothing so revenue always has goods behind it.
                        val sale = lot.saleId?.let { byId[it] } ?: continue
                        out += contributionOf(sale, take, row.createdAt)
                    }
                }
            }
        }
        out.sortBy { it.at }
        return out
    }

    /**
     * Cash-basis revenue / cost / profit for `[from, to)`, plus the sale value billed in
     * the window that is still uncollected.
     */
    fun compute(
        sales: List<CashBasisSaleRow>,
        ledger: List<CreditTxn>,
        from: Long,
        to: Long
    ): Period {
        var revenue = 0.0
        var cogs = 0.0
        var profit = 0.0
        var costed = 0.0
        for (c in contributions(sales, ledger)) {
            if (c.at < from || c.at >= to) continue
            revenue += c.amount
            cogs += c.cogsShare
            profit += c.profitShare
            costed += c.costedShare
        }
        var uncollected = 0.0
        for (s in sales) {
            if (s.soldAt < from || s.soldAt >= to) continue
            uncollected += (s.total - s.amountPaid.coerceIn(0.0, s.total.coerceAtLeast(0.0)))
                .coerceAtLeast(0.0)
        }
        return Period(
            revenue = revenue,
            cogs = cogs,
            grossProfit = profit,
            costedRevenue = costed,
            uncollected = uncollected
        )
    }

    /**
     * Pro-rate one sale's costed economics to the [collected] slice of it. `ratio` is the
     * share of the sale's value this money represents; both the revenue side and the cost
     * side are scaled by it, which is precisely what stops a part-paid sale reading as a
     * loss. A zero-total sale (a full-discount giveaway) has no ratio to apply, so it
     * recognises the money with no goods economics attached.
     */
    private fun contributionOf(s: CashBasisSaleRow, collected: Double, at: Long): Contribution {
        val ratio = if (s.total > CENT) (collected / s.total).coerceIn(0.0, 1.0) else 0.0
        val saleCogs = (s.costedRevenue - s.lineProfit)
        return Contribution(
            saleId = s.id,
            at = at,
            amount = collected,
            costedShare = ratio * s.costedRevenue,
            profitShare = ratio * s.lineProfit,
            cogsShare = ratio * saleCogs
        )
    }

    /** One open credit lot: the unpaid remainder of a sale, awaiting FIFO settlement. */
    private class Lot(val saleId: String?, var remaining: Double)
}
