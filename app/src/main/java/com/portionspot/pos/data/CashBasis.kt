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
 *  4. VAT IS NEVER REVENUE. It is pro-rated on the same ratio as everything else (a
 *     customer who has paid 40% of the bill has paid 40% of its VAT) and broken out on
 *     its own line, because it is ZIMRA's money passing through the drawer.
 *  5. A REFUND REVERSES the sale, on the refund's OWN day (see below).
 *
 * ══ ★ Refunds: a reversal, dated the day the goods came back ══
 * A refund used to be invisible here. [CashBasis] read only sales and the credit ledger,
 * and [PosRepository.createRefund] deliberately never touches the sale, its lines, its
 * `amountPaid` or its `status` — so a $100 sale costing $60, fully refunded, still
 * reported $100 collected and $40 profit with the goods back on the shelf.
 *
 * The fix is derived recognition, not a mutation:
 *
 *  - The SALE STAYS IMMUTABLE. The wire contract derives the cloud's `profit_total` and
 *    `line_profit` from the sale's own lines, and half this app relies on a completed
 *    sale never changing. The reversal lives here, in the read model.
 *  - The reversal is booked ON THE REFUND'S DAY and NEVER restates the day the sale was
 *    rung up. Those days may already have a [DayClose] whose counted short/over was
 *    computed against the old figure; rewriting them would invalidate a physical count
 *    that a human actually performed.
 *  - A refund reverses the FRACTION `refundTotal / sale.total` of the sale.
 *    [computeRefundTotal] builds `refundTotal` as `returnedGoods / saleGoodsValue ×
 *    saleTotal`, so that fraction is exactly the share of the GOODS that came back, on
 *    the same VAT-and-discount-inclusive basis as `sale.total`. Revenue, VAT, cost and
 *    profit are all scaled by it together.
 *  - Cash that physically arrived is still cash that arrived. [Period.collected] is
 *    GROSS and a refund never rewrites it: the payout already left the drawer through
 *    its own `CashTxn` row, and the till reconciliation reads that. The reversal is its
 *    own visible figure, [Period.refunded], so a card can read "collected X, refunds Y,
 *    kept Z" with every line naming something real.
 *
 * ══ ★ Why this cannot double-count, with refunds in the mix ══
 * Every dollar of a sale is recognised through exactly ONE of two doors, and the two
 * doors are disjoint by construction:
 *   • the collected-at-sale door is `min(amountPaid, total)`;
 *   • the repayment door only ever consumes CREDIT LOTS, and a credit lot is by
 *     definition the part of the sale that was NOT paid at the till (`total − paid`).
 * The FIFO matcher then hands each repaid dollar to at most one lot, and each lot to at
 * most one sale, so the RAW money collected on a sale never exceeds its total.
 *
 * Refunds are then applied as a per-sale CEILING rather than as a second stream of
 * events, which is what keeps a reversal from being counted twice. Walking one sale's
 * events in time order and keeping `refundedRatio` (Σ of its refund fractions, clamped
 * at 1.0), the sale's cumulative net recognition at any instant is
 *
 *     recognised = min( rawCollectedSoFar , (1 − refundedRatio) × total )
 *
 * and each event emits only the DELTA in that quantity — positive when money arrives,
 * negative when a refund shrinks the ceiling below what was already recognised. Three
 * properties follow, and [CashBasisTest] asserts all three:
 *
 *  - A reversal can never exceed what was actually recognised. A fully-unpaid credit
 *    sale recognised nothing, so refunding it reverses NOTHING — there is no revenue to
 *    take back; what gets cancelled is the debt, in the credit ledger.
 *  - Recognition arriving AFTER a refund is limited to the part of the sale still live.
 *    Refund half a part-paid sale and the later repayment tops recognition up to that
 *    half and stops, instead of recognising money for goods the shop has back.
 *  - Multiple partial refunds accumulate, and the ratio clamps at 1.0, so a third refund
 *    that would take the sale past 100% reverses only the remainder.
 *
 * ══ Deliberate exclusions ══
 *  - A DEBT WRITE-OFF is a `credit_paid` row too, but no money arrived. It still CONSUMES
 *    its lot (the debt really is gone) and contributes ZERO revenue. Recognised by its
 *    note, [WRITE_OFF_NOTE] — the same string [PosRepository.writeOffDebt] writes.
 *  - A credit lot with NO linked sale (the "over-given change" correction booked as
 *    `credit_owed`) contributes nothing when repaid. It is the recovery of a till
 *    shortage, not a sale, and pretending otherwise would invent revenue with no goods
 *    behind it.
 *  - A VOIDED refund is tombstoned by [PosRepository.voidRefund] (`deleted = 1`) and is
 *    filtered out upstream by [RefundDao.observeCashBasisRefunds], so its reversal simply
 *    stops existing. That does restate the refund's own day — the one place this object
 *    knowingly breaks the never-restate rule, because a void has no timestamp of its own
 *    to book against (`Refund.updatedAt` also moves on sync). It is the same convergence
 *    the cash mirror relies on, and a void is rare and admin-only.
 *  - A refund whose original sale is not in [sales] (deleted, or not `completed`) is
 *    skipped: there is no basis to pro-rate it against.
 *
 * Pure and side-effect free on purpose: no Room, no Android, no clock. That is what
 * makes the no-double-count invariant testable rather than asserted.
 */
object CashBasis {

    /** The note [PosRepository.writeOffDebt] stamps. A write-off is not a collection. */
    const val WRITE_OFF_NOTE = "Debt write-off"

    /**
     * The note [PosRepository.createRefund] stamps on the `credit_paid` row that CANCELS
     * the unpaid part of a refunded sale. Prefix, not exact match: the receipt number is
     * appended so the customer's ledger reads like a statement rather than a code.
     *
     * ★ WHY THE CANCELLATION IS A `credit_paid` ROW AT ALL. The debt genuinely goes away
     * — the goods came back — so the balance the shop chases must drop, and it must drop
     * on BOTH clients. `credit_owed − credit_paid` is the only arithmetic the web knows,
     * and `credit_txns.type` carries no CHECK constraint the web would reject a new word
     * on; it simply would not understand one, and the customer would keep showing a debt
     * on the browser that the phone says is settled.
     *
     * ★ WHY IT MUST BE EXCLUDED HERE. A plain `credit_paid` is money arriving, and door 2
     * would recognise the whole cancelled balance as revenue ON THE REFUND'S DAY — the
     * shop would book income for goods sitting back on its own shelf. It is the write-off
     * case exactly: the lot is CONSUMED (the debt really is gone, and a later payment must
     * not be matched to it) and NOTHING is recognised.
     */
    const val DEBT_CANCELLED_NOTE = "Debt cancelled by refund"

    /** Does this `credit_paid` row represent money that actually arrived? */
    private fun isCollection(note: String?): Boolean {
        val n = note?.trim() ?: return true
        return n != WRITE_OFF_NOTE && !n.startsWith(DEBT_CANCELLED_NOTE)
    }

    /** Half-a-cent tolerance, matching the repository's money comparisons. */
    private const val CENT = 0.005

    /**
     * Cash-basis figures for one window. EVERY FIELD NAMES ITS OWN BASIS, because the
     * bug this shape replaced was a card that subtracted a VAT-INCLUSIVE revenue figure
     * from a VAT-EXCLUSIVE cost and printed the difference as profit. The arithmetic on
     * screen has to hold, or the owner is being asked to trust a sum that does not add up.
     *
     *  - [collected]     GROSS money recognised in the window, VAT-INCLUSIVE — what
     *                    physically arrived, from same-day settlement and from repayments
     *                    of older credit. NOT reduced by refunds: the money did arrive,
     *                    and the refund payout leaves through its own cash row. This is
     *                    the figure a drawer/till reconciliation wants.
     *  - [refunded]      GROSS recognition TAKEN BACK in the window, VAT-INCLUSIVE and
     *                    POSITIVE, from refunds created in the window. Show it as its own
     *                    subtracted line, never folded into [collected].
     *  - [vat]           VAT inside `collected − refunded` — ZIMRA's money, never revenue.
     *                    Signed: a window containing only a refund reports negative VAT,
     *                    which is the truth (VAT was handed back).
     *  - [netRevenue]    `collected − refunded − vat`. The VAT-exclusive money the shop
     *                    actually kept for goods it actually sold. THE revenue figure.
     *  - [costedRevenue] the slice of [netRevenue] that came from lines carrying a cost —
     *                    VAT-exclusive, net of refunds. Two jobs: the honest denominator
     *                    for a margin percentage, and the LEFT-HAND SIDE of the profit
     *                    subtraction (see [grossProfit]).
     *  - [cogs]          cost of the goods behind [costedRevenue] (pro-rated, net of
     *                    refunds). VAT-exclusive.
     *  - [grossProfit]   `costedRevenue − cogs`, exactly, to the cent.
     *  - [uncollected]   sale value that LEFT THE TILL UNPAID in the window. Shown as a
     *                    figure/alert, never as sales. Windowed on the SALE's day and
     *                    read off [CashBasisSaleRow.amountPaid] alone, so it is reduced
     *                    by neither a later refund NOR a later repayment — the same
     *                    never-restate rule that dates reversals on the refund's day.
     *
     *                    ★ It is therefore NOT "still owed", and no screen may word it
     *                    that way: yesterday's figure stays whole after the debt is
     *                    settled today. What is still outstanding is the customer
     *                    ledger's answer, not this one. The web client words it
     *                    "left unpaid at the till" for the same reason.
     *
     * ★ THE TWO IDENTITIES, and why there are two rather than one:
     *
     *     netRevenue + vat + refunded == collected        (always)
     *     costedRevenue − cogs        == grossProfit      (always)
     *
     * `netRevenue − cogs` is NOT gross profit, and no screen should print it as one.
     * Profit is known only over lines that HAVE a cost price; a line with no cost is
     * unknown-cost, not zero-cost (see [computeSaleMargin]). The gap
     * `netRevenue − costedRevenue` is revenue whose profit the shop cannot compute, and
     * it is honest to show that gap rather than to bury it in the margin.
     */
    data class Period(
        val collected: Double = 0.0,
        val refunded: Double = 0.0,
        val vat: Double = 0.0,
        val netRevenue: Double = 0.0,
        val costedRevenue: Double = 0.0,
        val cogs: Double = 0.0,
        val grossProfit: Double = 0.0,
        val uncollected: Double = 0.0
    ) {
        /** Revenue in [netRevenue] with no cost price behind it, so no known profit. */
        val uncostedRevenue: Double get() = (netRevenue - costedRevenue).coerceAtLeast(0.0)
    }

    /**
     * One recognition event — the unit the invariant is asserted over. Exactly one of
     * [collected] / [refunded] is non-zero; the shares are SIGNED so a reversal is the
     * same shape with the sign flipped, which is what makes the windowed sums in
     * [compute] a plain addition.
     *
     *  - [collected]  gross money recognised here, VAT-inclusive (0 on a reversal).
     *  - [refunded]   gross recognition reversed here, VAT-inclusive, positive (0 on a
     *                 collection).
     *  - [vatShare]   VAT inside this event; negative on a reversal.
     *  - [costedShare]/[cogsShare]/[profitShare] the costed economics of this event,
     *    pro-rated on the same ratio, negative on a reversal.
     */
    data class Contribution(
        val saleId: String,
        val at: Long,
        val collected: Double,
        val refunded: Double,
        val vatShare: Double,
        val costedShare: Double,
        val profitShare: Double,
        val cogsShare: Double
    ) {
        /** Signed gross recognition: what this event added to the books. */
        val net: Double get() = collected - refunded
    }

    /**
     * Every recognition event over ALL time, oldest first. [compute] is a windowed sum of
     * these; the tests walk them to prove no sale is ever recognised beyond what it
     * collected, less what has been refunded.
     *
     * [sales] must be the shop's live COMPLETED sales; [ledger] its whole credit ledger;
     * [refunds] its whole live refund history (all three are what the repository's flows
     * already provide). All three are UNWINDOWED on purpose — a repayment today can
     * settle a sale from last year, and a refund today reverses a sale from last month.
     */
    fun contributions(
        sales: List<CashBasisSaleRow>,
        ledger: List<CreditTxn>,
        refunds: List<CashBasisRefundRow>
    ): List<Contribution> {
        val byId = sales.associateBy { it.id }
        // Raw events per sale, collected first and RESOLVED into net recognition second.
        // Two passes rather than one because the repayment door needs a per-CUSTOMER FIFO
        // walk to learn which sale a payment belongs to, while the refund ceiling needs a
        // per-SALE walk in time order. Neither can be expressed as the other.
        val raw = moneyEvents(sales, ledger, byId)
        fun record(saleId: String, at: Long, amount: Double, isRefund: Boolean) {
            raw.getOrPut(saleId) { ArrayList(2) }.add(RawEvent(at, amount, isRefund))
        }

        // ── The reversal side: refunds, on their own day. ──
        for (r in refunds) {
            if (r.refundTotal <= CENT) continue
            val sale = byId[r.saleId] ?: continue   // no sale ⇒ no basis to pro-rate
            record(sale.id, r.at, r.refundTotal, isRefund = true)
        }

        // ── Resolve each sale's events into net recognition deltas. ──
        val out = ArrayList<Contribution>(raw.values.sumOf { it.size })
        for ((saleId, events) in raw) {
            val s = byId[saleId] ?: continue
            val total = s.total.coerceAtLeast(0.0)
            var rawCollected = 0.0     // money in, before any refund ceiling
            var refundedRatio = 0.0    // Σ refund fractions of this sale, clamped at 1.0
            var recognised = 0.0       // what the books currently show for this sale
            // A refund can only reverse what has already happened, so money sorts BEFORE
            // a refund landing in the same millisecond (two tills, one clock tick).
            val ordered = events.sortedWith(
                compareBy<RawEvent> { it.at }.thenBy { if (it.isRefund) 1 else 0 }
            )
            for (e in ordered) {
                if (e.isRefund) {
                    val fraction = if (total > CENT) e.amount / total else 1.0
                    refundedRatio = (refundedRatio + fraction).coerceIn(0.0, 1.0)
                } else {
                    rawCollected += e.amount
                }
                // The ceiling: the sale is now a sale of `(1 − refundedRatio) × total`,
                // and it can never have recognised more money than actually arrived.
                val target = minOf(rawCollected, (1.0 - refundedRatio) * total)
                val delta = target - recognised
                if (delta > -CENT && delta < CENT) continue
                recognised = target
                out += contributionOf(s, delta, e.at)
            }
        }
        out.sortBy { it.at }
        return out
    }

    /**
     * Cash-basis figures for `[from, to)`, plus the sale value billed in the window that
     * is still uncollected. See [Period] for what each field is on the basis of — the
     * fields are not interchangeable and choosing the wrong one is the bug this shape
     * exists to make impossible.
     */
    fun compute(
        sales: List<CashBasisSaleRow>,
        ledger: List<CreditTxn>,
        refunds: List<CashBasisRefundRow>,
        from: Long,
        to: Long
    ): Period {
        var collected = 0.0
        var refunded = 0.0
        var vat = 0.0
        var cogs = 0.0
        var profit = 0.0
        var costed = 0.0
        for (c in contributions(sales, ledger, refunds)) {
            if (c.at < from || c.at >= to) continue
            collected += c.collected
            refunded += c.refunded
            vat += c.vatShare
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
            collected = collected,
            refunded = refunded,
            vat = vat,
            netRevenue = collected - refunded - vat,
            costedRevenue = costed,
            cogs = cogs,
            grossProfit = profit,
            uncollected = uncollected
        )
    }

    /**
     * Pro-rate one sale's economics to the [delta] slice of it. `ratio` is the share of
     * the sale's value this event represents; VAT, the costed take and the cost side are
     * ALL scaled by it, which is precisely what stops a part-paid sale reading as a loss
     * and what keeps VAT on the same basis as the money it sits inside.
     *
     * [delta] is SIGNED: negative for a refund reversal, which flips every share with it
     * so a reversal is arithmetically the exact opposite of the recognition it undoes.
     * A zero-total sale (a full-discount giveaway) has no ratio to apply, so it recognises
     * the money with no goods economics attached.
     */
    private fun contributionOf(s: CashBasisSaleRow, delta: Double, at: Long): Contribution {
        val ratio = if (s.total > CENT) (delta / s.total).coerceIn(-1.0, 1.0) else 0.0
        val saleCogs = (s.costedRevenue - s.lineProfit)
        return Contribution(
            saleId = s.id,
            at = at,
            collected = if (delta > 0.0) delta else 0.0,
            refunded = if (delta < 0.0) -delta else 0.0,
            vatShare = ratio * s.taxTotal,
            costedShare = ratio * s.costedRevenue,
            profitShare = ratio * s.lineProfit,
            cogsShare = ratio * saleCogs
        )
    }

    /**
     * The two MONEY doors — everything that put cash against a sale, before any refund
     * ceiling is applied. Extracted so [contributions] and [rawCollectedBySale] cannot
     * drift apart: a second copy of the FIFO walk is the one way this object could start
     * answering "how much did this sale collect" differently depending on who asked.
     *
     * Sales absent from [byId] still CONSUME their lots — consumption happens before the
     * lookup — so a caller may pass a single sale row and get that sale's collections
     * correctly, without loading every other sale the customer ever made.
     */
    private fun moneyEvents(
        sales: List<CashBasisSaleRow>,
        ledger: List<CreditTxn>,
        byId: Map<String, CashBasisSaleRow>,
    ): HashMap<String, MutableList<RawEvent>> {
        val raw = HashMap<String, MutableList<RawEvent>>(sales.size)
        fun record(saleId: String, at: Long, amount: Double) {
            raw.getOrPut(saleId) { ArrayList(2) }.add(RawEvent(at, amount, isRefund = false))
        }

        // ── Door 1: what was settled on the day of the sale. ──
        for (s in sales) {
            val collected = s.amountPaid.coerceIn(0.0, s.total.coerceAtLeast(0.0))
            if (collected <= CENT) continue
            record(s.id, s.soldAt, collected)
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
                    // A write-off, or a debt cancelled because the goods came back, clears
                    // the debt but is NOT money — it consumes lots and recognises nothing.
                    val collects = isCollection(row.note)
                    val queue = lots[row.customerId] ?: continue
                    while (left > CENT && queue.isNotEmpty()) {
                        val lot = queue.first()
                        val take = minOf(left, lot.remaining)
                        lot.remaining -= take
                        left -= take
                        if (lot.remaining <= CENT) queue.removeFirst()
                        if (!collects || take <= CENT) continue
                        // A lot with no sale behind it (an over-given-change correction)
                        // recovers a till shortage — real money, but not a sale. It
                        // recognises nothing so revenue always has goods behind it.
                        val sale = lot.saleId?.let { byId[it] } ?: continue
                        record(sale.id, row.createdAt, take)
                    }
                }
            }
        }
        return raw
    }

    /**
     * RAW money collected against each sale — both doors, NO refund ceiling.
     *
     * This is the figure a refund needs and [Period.collected] is not: it answers "how
     * much of this sale's money has actually reached the shop", which is the ONLY honest
     * cap on how much can be handed back across the counter. Refunding a part-paid
     * account sale used to hand back the whole sale total, which drove a real till to
     * −$60 on a $100 sale with $40 paid — the shop paying out money it had never been
     * given. See [PosRepository.createRefund], which settles the unpaid part against the
     * customer's DEBT and only lets [payable] leave the drawer.
     *
     * Deliberately NOT net of refunds: what a sale has collected does not change because
     * goods came back. The caller subtracts what it has already refunded, because only
     * the caller knows whether the refund it is about to write is included yet.
     */
    fun rawCollectedBySale(
        sales: List<CashBasisSaleRow>,
        ledger: List<CreditTxn>,
    ): Map<String, Double> =
        moneyEvents(sales, ledger, sales.associateBy { it.id })
            .mapValues { (_, events) -> events.sumOf { it.amount } }

    /** One open credit lot: the unpaid remainder of a sale, awaiting FIFO settlement. */
    private class Lot(val saleId: String?, var remaining: Double)

    /** One thing that happened to a sale: money in, or goods back. */
    private class RawEvent(val at: Long, val amount: Double, val isRefund: Boolean)
}
