package com.portionspot.pos.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ★ THE NO-DOUBLE-COUNT PROOF (§5).
 *
 * Cash-basis revenue is the one change in this phase that can silently invent money: a
 * sale is recognised partly at the till and partly on repayment, so an off-by-one in the
 * FIFO matcher would count the same dollar twice and quietly inflate the owner's profit.
 *
 * These tests assert the invariant DIRECTLY rather than checking a few example totals:
 *
 *     for every sale S:  Σ (net contributions of S over all time)
 *                        ==  min(money collected on S, the share of S not refunded)
 *
 * plus the pro-rating rule, the window placement (a repayment counts on ITS day, a refund
 * on the day the goods came back), the VAT split (VAT is never revenue), and the two
 * deliberate exclusions (write-offs and correction lots).
 */
class CashBasisTest {

    private val DAY = 86_400_000L
    private val d1 = 1_700_000_000_000L      // "day 1"
    private val d2 = d1 + DAY
    private val d3 = d1 + 2 * DAY
    private val d4 = d1 + 3 * DAY

    /** The money tolerance the repository and [CashBasis] both use: half a cent. */
    private val CENT = 0.005

    /**
     * A sale of [total] (VAT-INCLUSIVE) whose costed lines carry [cost] of goods. Every
     * line is costed here, so `costedRevenue` is the whole VAT-exclusive take — which is
     * what makes `netRevenue − cogs == grossProfit` hold for these fixtures. The
     * "uncosted lines break the netRevenue subtraction" test below builds a partly-costed
     * sale, where it deliberately does not.
     */
    private fun sale(
        id: String,
        soldAt: Long,
        total: Double,
        paid: Double,
        cost: Double,
        customerId: String? = "c1",
        tax: Double = 0.0
    ) = CashBasisSaleRow(
        id = id,
        soldAt = soldAt,
        total = total,
        taxTotal = tax,
        amountPaid = paid,
        customerId = customerId,
        costedRevenue = total - tax,
        lineProfit = total - tax - cost
    )

    private fun owed(saleId: String?, amount: Double, at: Long, customerId: String = "c1") =
        CreditTxn(
            businessId = "b", customerId = customerId, saleId = saleId,
            type = "credit_owed", amount = amount, createdAt = at, updatedAt = at
        )

    private fun paid(amount: Double, at: Long, customerId: String = "c1", note: String? = null) =
        CreditTxn(
            businessId = "b", customerId = customerId, saleId = null,
            type = "credit_paid", amount = amount, note = note, createdAt = at, updatedAt = at
        )

    /** A live refund of [amount] against [saleId], created at [at]. */
    private fun refund(id: String, saleId: String, amount: Double, at: Long) =
        CashBasisRefundRow(id = id, saleId = saleId, at = at, refundTotal = amount)

    private fun netBySale(
        sales: List<CashBasisSaleRow>,
        ledger: List<CreditTxn> = emptyList(),
        refunds: List<CashBasisRefundRow> = emptyList()
    ): Map<String, Double> =
        CashBasis.contributions(sales, ledger, refunds)
            .groupBy { it.saleId }
            .mapValues { (_, cs) -> cs.sumOf { it.net } }

    // ── The invariant ────────────────────────────────────────────────────

    /**
     * ★ THE CORE GUARANTEE. Over any mix of cash, part-paid, over-paid and fully-unpaid
     * sales with repayments spread over days, no sale may ever contribute more than the
     * money actually collected on it.
     */
    @Test
    fun `a sale never contributes more than was collected on it`() {
        val sales = listOf(
            sale("cash", d1, total = 50.0, paid = 50.0, cost = 30.0),
            sale("part", d1, total = 100.0, paid = 40.0, cost = 60.0),
            sale("none", d1, total = 80.0, paid = 0.0, cost = 50.0),
            // Handed over 100 for a 90 sale: the extra 10 is change owed, NOT revenue.
            sale("over", d1, total = 90.0, paid = 100.0, cost = 40.0),
            sale("other", d2, total = 25.0, paid = 25.0, cost = 10.0, customerId = "c2")
        )
        val ledger = listOf(
            owed("part", 60.0, d1),
            owed("none", 80.0, d1),
            paid(30.0, d2),      // settles part of "part"
            paid(70.0, d3)       // finishes "part" (30) and starts "none" (40)
        )

        val byLifetime = CashBasis.contributions(sales, ledger, emptyList())
            .groupBy { it.saleId }
            .mapValues { (_, cs) -> cs.sumOf { it.collected } }

        // "part" collected 40 at the till + 60 repaid = exactly its 100 total, no more.
        assertEquals(100.0, byLifetime["part"]!!, 0.0001)
        // "none" collected nothing at the till and 40 of its 80 so far.
        assertEquals(40.0, byLifetime["none"]!!, 0.0001)
        // A plain cash sale contributes once.
        assertEquals(50.0, byLifetime["cash"]!!, 0.0001)
        // ★ The overpayment is CLAMPED to the sale value — change owed is not revenue.
        assertEquals(90.0, byLifetime["over"]!!, 0.0001)

        // And the general form of the same statement, for every sale.
        for (s in sales) {
            val contributed = byLifetime[s.id] ?: 0.0
            assertTrue(
                "sale ${s.id} contributed $contributed, more than its ${s.total} total",
                contributed <= s.total + 0.0001
            )
        }
    }

    /** Recognition is spread across DAYS, and the days sum back to the lifetime total. */
    @Test
    fun `a repayment counts on its own day and windows sum to the lifetime total`() {
        val sales = listOf(sale("s1", d1, total = 100.0, paid = 40.0, cost = 60.0))
        val ledger = listOf(owed("s1", 60.0, d1), paid(60.0, d3))

        val day1 = CashBasis.compute(sales, ledger, emptyList(), d1, d1 + DAY)
        val day2 = CashBasis.compute(sales, ledger, emptyList(), d2, d2 + DAY)
        val day3 = CashBasis.compute(sales, ledger, emptyList(), d3, d3 + DAY)

        assertEquals(40.0, day1.collected, 0.0001)   // taken at the till
        assertEquals(0.0, day2.collected, 0.0001)    // nothing happened
        assertEquals(60.0, day3.collected, 0.0001)   // the repayment, on ITS day
        assertEquals(100.0, day1.collected + day2.collected + day3.collected, 0.0001)

        // Billed-but-unpaid is reported against the day of the SALE, and only there.
        assertEquals(60.0, day1.uncollected, 0.0001)
        assertEquals(0.0, day3.uncollected, 0.0001)
    }

    // ── Pro-rated cost of goods ──────────────────────────────────────────

    /** The owner's own worked example: $100 sale, $60 cost, $40 collected → 40/24/16. */
    @Test
    fun `cost of goods is pro-rated to the collected share`() {
        val sales = listOf(sale("s1", d1, total = 100.0, paid = 40.0, cost = 60.0))
        val ledger = listOf(owed("s1", 60.0, d1))

        val p = CashBasis.compute(sales, ledger, emptyList(), d1, d1 + DAY)
        assertEquals(40.0, p.netRevenue, 0.0001)
        assertEquals(24.0, p.cogs, 0.0001)
        assertEquals(16.0, p.grossProfit, 0.0001)
    }

    /** A part-paid sale must never show a LOSS just because the cost is front-loaded. */
    @Test
    fun `a thinly paid sale still shows a proportional profit, never a fake loss`() {
        // 10% collected on a sale with a 60% cost ratio.
        val sales = listOf(sale("s1", d1, total = 100.0, paid = 10.0, cost = 60.0))
        val ledger = listOf(owed("s1", 90.0, d1))

        val p = CashBasis.compute(sales, ledger, emptyList(), d1, d1 + DAY)
        assertEquals(10.0, p.netRevenue, 0.0001)
        assertEquals(6.0, p.cogs, 0.0001)
        assertEquals(4.0, p.grossProfit, 0.0001)
        assertTrue("a part-paid sale must not read as a loss", p.grossProfit > 0.0)
    }

    /** Across the whole life of a sale, the cost recognised is the WHOLE cost — once. */
    @Test
    fun `full settlement recognises the full cost exactly once`() {
        val sales = listOf(sale("s1", d1, total = 100.0, paid = 40.0, cost = 60.0))
        val ledger = listOf(owed("s1", 60.0, d1), paid(60.0, d3))

        val all = CashBasis.compute(sales, ledger, emptyList(), 0L, Long.MAX_VALUE)
        assertEquals(100.0, all.netRevenue, 0.0001)
        assertEquals(60.0, all.cogs, 0.0001)
        assertEquals(40.0, all.grossProfit, 0.0001)
    }

    // ── The deliberate exclusions ────────────────────────────────────────

    /** A write-off clears the debt but no money arrived — it must recognise nothing. */
    @Test
    fun `a debt write-off clears the lot but is not revenue`() {
        val sales = listOf(sale("s1", d1, total = 100.0, paid = 0.0, cost = 60.0))
        val ledger = listOf(
            owed("s1", 100.0, d1),
            paid(100.0, d2, note = CashBasis.WRITE_OFF_NOTE)
        )

        val all = CashBasis.compute(sales, ledger, emptyList(), 0L, Long.MAX_VALUE)
        assertEquals("a write-off is not money", 0.0, all.collected, 0.0001)
        assertEquals(0.0, all.netRevenue, 0.0001)
        assertEquals(0.0, all.grossProfit, 0.0001)
    }

    /**
     * A written-off lot is CONSUMED, so a later genuine repayment cannot be re-matched
     * against it and double-recognise the same sale.
     */
    @Test
    fun `a write-off consumes its lot so later money cannot re-recognise the sale`() {
        val sales = listOf(
            sale("s1", d1, total = 100.0, paid = 0.0, cost = 60.0),
            sale("s2", d2, total = 50.0, paid = 0.0, cost = 20.0)
        )
        val ledger = listOf(
            owed("s1", 100.0, d1),
            paid(100.0, d2, note = CashBasis.WRITE_OFF_NOTE),   // s1 written off
            owed("s2", 50.0, d2),
            paid(50.0, d3)                                       // real money, for s2
        )

        val byLifetime = netBySale(sales, ledger)

        assertEquals(null, byLifetime["s1"])                     // never recognised
        assertEquals(50.0, byLifetime["s2"]!!, 0.0001)           // recognised exactly once
    }

    /**
     * An over-given-change correction is booked as `credit_owed` with NO sale behind it.
     * Recovering it is not a sale, so it must recognise nothing — otherwise revenue would
     * exist with no goods to back it.
     */
    @Test
    fun `a correction lot with no sale behind it recognises nothing`() {
        val sales = listOf(sale("s1", d1, total = 20.0, paid = 20.0, cost = 5.0))
        val ledger = listOf(
            owed(null, 8.0, d1),     // over-given change: the customer owes the shop
            paid(8.0, d2)            // they hand it back
        )

        val all = CashBasis.compute(sales, ledger, emptyList(), 0L, Long.MAX_VALUE)
        assertEquals("only the real sale counts", 20.0, all.collected, 0.0001)
        assertEquals(20.0, all.netRevenue, 0.0001)
    }

    // ── FIFO ordering ────────────────────────────────────────────────────

    /** Repayments settle the OLDEST debt first, and one payment can span two sales. */
    @Test
    fun `a repayment is matched FIFO and can span two sales`() {
        val sales = listOf(
            sale("old", d1, total = 60.0, paid = 0.0, cost = 30.0),
            sale("new", d2, total = 40.0, paid = 0.0, cost = 10.0)
        )
        val ledger = listOf(
            owed("old", 60.0, d1),
            owed("new", 40.0, d2),
            paid(75.0, d3)   // clears "old" entirely and 15 of "new"
        )

        val byLifetime = netBySale(sales, ledger)

        assertEquals(60.0, byLifetime["old"]!!, 0.0001)
        assertEquals(15.0, byLifetime["new"]!!, 0.0001)
        assertEquals(75.0, byLifetime.values.sum(), 0.0001)
    }

    /** Two customers' ledgers never bleed into each other. */
    @Test
    fun `one customer's repayment cannot settle another customer's debt`() {
        val sales = listOf(
            sale("a", d1, total = 50.0, paid = 0.0, cost = 20.0, customerId = "c1"),
            sale("b", d1, total = 50.0, paid = 0.0, cost = 20.0, customerId = "c2")
        )
        val ledger = listOf(
            owed("a", 50.0, d1, customerId = "c1"),
            owed("b", 50.0, d1, customerId = "c2"),
            paid(50.0, d2, customerId = "c1")
        )

        val byLifetime = netBySale(sales, ledger)

        assertEquals(50.0, byLifetime["a"]!!, 0.0001)
        assertEquals(null, byLifetime["b"])
    }

    /** Paying MORE than is owed cannot recognise more than the sales behind it. */
    @Test
    fun `an over-repayment recognises only what was actually owed`() {
        val sales = listOf(sale("s1", d1, total = 30.0, paid = 0.0, cost = 10.0))
        val ledger = listOf(owed("s1", 30.0, d1), paid(100.0, d2))

        val all = CashBasis.compute(sales, ledger, emptyList(), 0L, Long.MAX_VALUE)
        assertEquals(30.0, all.collected, 0.0001)
    }

    /** A deleted ledger row is ignored on both sides of the match. */
    @Test
    fun `tombstoned ledger rows are ignored`() {
        val sales = listOf(sale("s1", d1, total = 40.0, paid = 0.0, cost = 10.0))
        val ledger = listOf(
            owed("s1", 40.0, d1),
            paid(40.0, d2).copy(deleted = true)
        )

        val all = CashBasis.compute(sales, ledger, emptyList(), 0L, Long.MAX_VALUE)
        assertEquals(0.0, all.collected, 0.0001)
    }

    /** A zero-total sale (a full giveaway) has no ratio to apply and cannot divide by 0. */
    @Test
    fun `a zero total sale recognises no goods economics`() {
        val sales = listOf(sale("free", d1, total = 0.0, paid = 0.0, cost = 0.0))
        val all = CashBasis.compute(sales, emptyList(), emptyList(), 0L, Long.MAX_VALUE)
        assertEquals(0.0, all.collected, 0.0001)
        assertEquals(0.0, all.netRevenue, 0.0001)
        assertEquals(0.0, all.grossProfit, 0.0001)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  VAT — ZIMRA's money, never revenue
    // ══════════════════════════════════════════════════════════════════════

    /**
     * ★ THE TWO IDENTITIES the card on screen is built out of. The bug this replaced put a
     * VAT-INCLUSIVE "collected" directly above a VAT-EXCLUSIVE cost and printed the
     * difference as profit — the profit was right, the subtraction shown was not.
     */
    @Test
    fun `netRevenue plus vat equals collected, and netRevenue less cogs equals grossProfit`() {
        // $100 of goods at 15% VAT: the customer pays 115, of which 15 is ZIMRA's.
        val sales = listOf(sale("s1", d1, total = 115.0, paid = 115.0, cost = 60.0, tax = 15.0))

        val p = CashBasis.compute(sales, emptyList(), emptyList(), d1, d1 + DAY)
        assertEquals("gross cash that arrived", 115.0, p.collected, CENT)
        assertEquals("VAT inside it", 15.0, p.vat, CENT)
        assertEquals("revenue is net of VAT", 100.0, p.netRevenue, CENT)
        assertEquals(60.0, p.cogs, CENT)
        assertEquals(40.0, p.grossProfit, CENT)

        assertEquals("netRevenue + vat == collected", p.collected, p.netRevenue + p.vat, CENT)
        assertEquals("netRevenue − cogs == grossProfit", p.grossProfit, p.netRevenue - p.cogs, CENT)
    }

    /** VAT pro-rates on the SAME ratio as everything else: 40% paid ⇒ 40% of the VAT in. */
    @Test
    fun `VAT is pro-rated to the collected share, not recognised up front`() {
        val sales = listOf(sale("s1", d1, total = 115.0, paid = 46.0, cost = 60.0, tax = 15.0))
        val ledger = listOf(owed("s1", 69.0, d1))

        val p = CashBasis.compute(sales, ledger, emptyList(), d1, d1 + DAY)
        assertEquals(46.0, p.collected, CENT)
        assertEquals(6.0, p.vat, CENT)            // 40% of 15
        assertEquals(40.0, p.netRevenue, CENT)    // 40% of 100
        assertEquals(24.0, p.cogs, CENT)          // 40% of 60
        assertEquals(16.0, p.grossProfit, CENT)
    }

    /**
     * ★ WHY THERE ARE TWO IDENTITIES AND NOT ONE. Profit is knowable only over lines that
     * carry a cost price; an item with no cost is unknown-cost, not zero-cost. So on a
     * partly-costed sale `netRevenue − cogs` is NOT profit — but
     * `costedRevenue − cogs` is, exactly, which is why the Reports card subtracts from
     * the costed slice and shows the uncosted gap on its own line instead of hiding it.
     */
    @Test
    fun `uncosted lines break the netRevenue subtraction but never the costed one`() {
        // A 100 sale, half of it goods with no cost price recorded. Only the costed half
        // has an economics: take 50, cost 30.
        val sales = listOf(
            CashBasisSaleRow(
                id = "s1", soldAt = d1, total = 100.0, taxTotal = 0.0, amountPaid = 100.0,
                customerId = "c1", costedRevenue = 50.0, lineProfit = 20.0
            )
        )

        val p = CashBasis.compute(sales, emptyList(), emptyList(), d1, d1 + DAY)
        assertEquals(100.0, p.netRevenue, CENT)
        assertEquals(50.0, p.costedRevenue, CENT)
        assertEquals(30.0, p.cogs, CENT)
        assertEquals(20.0, p.grossProfit, CENT)
        assertEquals("the honest subtraction", p.grossProfit, p.costedRevenue - p.cogs, CENT)
        assertEquals("and the gap is visible", 50.0, p.uncostedRevenue, CENT)
        assertTrue(
            "netRevenue − cogs must NOT be read as profit when lines are uncosted",
            kotlin.math.abs((p.netRevenue - p.cogs) - p.grossProfit) > CENT
        )
    }

    // ══════════════════════════════════════════════════════════════════════
    //  REFUNDS — the sale is taken back on the day the goods came back
    // ══════════════════════════════════════════════════════════════════════

    /**
     * ★ THE AUDIT'S OWN WORKED EXAMPLE. A $100 sale costing $60, fully refunded, used to
     * keep reporting $100 collected and $40 profit with the goods back on the shelf —
     * because [PosRepository.createRefund] never touches the sale and [CashBasis] never
     * read the refunds. Lifetime revenue and profit must now be zero, and the reversal
     * must land on the REFUND's day, never back on the day the sale was rung up.
     */
    @Test
    fun `a fully refunded sale nets to zero revenue and zero profit, on the refund's day`() {
        val sales = listOf(sale("s1", d1, total = 100.0, paid = 100.0, cost = 60.0))
        val refunds = listOf(refund("r1", "s1", 100.0, d2))

        val all = CashBasis.compute(sales, emptyList(), refunds, 0L, Long.MAX_VALUE)
        assertEquals("the cash really did arrive", 100.0, all.collected, CENT)
        assertEquals("and all of it was reversed", 100.0, all.refunded, CENT)
        assertEquals(0.0, all.netRevenue, CENT)
        assertEquals(0.0, all.cogs, CENT)
        assertEquals(0.0, all.grossProfit, CENT)

        // ★ The reversal is dated the refund, and the day of the sale is UNTOUCHED — that
        // day may already carry a counted till short/over.
        val day1 = CashBasis.compute(sales, emptyList(), refunds, d1, d1 + DAY)
        assertEquals(100.0, day1.collected, CENT)
        assertEquals(0.0, day1.refunded, CENT)
        assertEquals(100.0, day1.netRevenue, CENT)
        assertEquals(40.0, day1.grossProfit, CENT)

        val day2 = CashBasis.compute(sales, emptyList(), refunds, d2, d2 + DAY)
        assertEquals(0.0, day2.collected, CENT)
        assertEquals(100.0, day2.refunded, CENT)
        assertEquals(-100.0, day2.netRevenue, CENT)
        assertEquals(-40.0, day2.grossProfit, CENT)
    }

    /** A refund in a LATER window cannot rewrite what an earlier window already reported. */
    @Test
    fun `a refund in a later window leaves the earlier window's figures untouched`() {
        val sales = listOf(sale("s1", d1, total = 100.0, paid = 100.0, cost = 60.0))
        val noRefund = CashBasis.compute(sales, emptyList(), emptyList(), d1, d1 + DAY)
        val withRefund = CashBasis.compute(
            sales, emptyList(), listOf(refund("r1", "s1", 100.0, d3)), d1, d1 + DAY
        )
        assertEquals(noRefund.collected, withRefund.collected, CENT)
        assertEquals(0.0, withRefund.refunded, CENT)
        assertEquals(noRefund.netRevenue, withRefund.netRevenue, CENT)
        assertEquals(noRefund.vat, withRefund.vat, CENT)
        assertEquals(noRefund.cogs, withRefund.cogs, CENT)
        assertEquals(noRefund.grossProfit, withRefund.grossProfit, CENT)
        assertEquals(noRefund.costedRevenue, withRefund.costedRevenue, CENT)
        assertEquals(noRefund.uncollected, withRefund.uncollected, CENT)
    }

    /** A partial refund scales revenue, VAT, cost and profit by ONE ratio, together. */
    @Test
    fun `a partial refund pro-rates revenue VAT cost and profit together`() {
        // 100 of goods + 15 VAT = 115 paid; 40% of the goods come back ⇒ refundTotal 46.
        val sales = listOf(sale("s1", d1, total = 115.0, paid = 115.0, cost = 60.0, tax = 15.0))
        val refunds = listOf(refund("r1", "s1", 46.0, d2))

        val all = CashBasis.compute(sales, emptyList(), refunds, 0L, Long.MAX_VALUE)
        assertEquals(115.0, all.collected, CENT)
        assertEquals(46.0, all.refunded, CENT)
        assertEquals("60% of the VAT is still ZIMRA's", 9.0, all.vat, CENT)
        assertEquals("60% of the goods stayed sold", 60.0, all.netRevenue, CENT)
        assertEquals(36.0, all.cogs, CENT)
        assertEquals(24.0, all.grossProfit, CENT)
        assertEquals(all.collected, all.netRevenue + all.vat + all.refunded, CENT)
        assertEquals(all.grossProfit, all.netRevenue - all.cogs, CENT)
    }

    /** Several partial refunds accumulate, and the last one can only take the remainder. */
    @Test
    fun `two partial refunds accumulate and a third clamps at the full sale`() {
        val sales = listOf(sale("s1", d1, total = 100.0, paid = 100.0, cost = 60.0))
        val refunds = listOf(
            refund("r1", "s1", 40.0, d2),
            refund("r2", "s1", 40.0, d3),
            refund("r3", "s1", 40.0, d4)   // would take the sale to 120% — must clamp
        )

        assertEquals(40.0, CashBasis.compute(sales, emptyList(), refunds, d2, d2 + DAY).refunded, CENT)
        assertEquals(40.0, CashBasis.compute(sales, emptyList(), refunds, d3, d3 + DAY).refunded, CENT)
        assertEquals(
            "the third can only reverse the 20 that was left",
            20.0, CashBasis.compute(sales, emptyList(), refunds, d4, d4 + DAY).refunded, CENT
        )

        val all = CashBasis.compute(sales, emptyList(), refunds, 0L, Long.MAX_VALUE)
        assertEquals("never more than the sale", 100.0, all.refunded, CENT)
        assertEquals(0.0, all.netRevenue, CENT)
        assertEquals(0.0, all.grossProfit, CENT)
    }

    /** An absurd refundTotal (bad data, a mis-typed manual figure) still cannot over-reverse. */
    @Test
    fun `a refund larger than the sale reverses only the sale`() {
        val sales = listOf(sale("s1", d1, total = 100.0, paid = 100.0, cost = 60.0))
        val refunds = listOf(refund("r1", "s1", 500.0, d2))

        val all = CashBasis.compute(sales, emptyList(), refunds, 0L, Long.MAX_VALUE)
        assertEquals(100.0, all.refunded, CENT)
        assertEquals(0.0, all.netRevenue, CENT)
        assertEquals(0.0, all.grossProfit, CENT)
    }

    /**
     * ★ A FULLY-UNPAID CREDIT SALE RECOGNISED NOTHING, so refunding it must reverse
     * NOTHING. There is no revenue to take back — what gets cancelled is the DEBT, and
     * that lives in the credit ledger, not in the books' revenue.
     */
    @Test
    fun `a fully unpaid credit sale refunded reverses nothing`() {
        val sales = listOf(sale("s1", d1, total = 100.0, paid = 0.0, cost = 60.0))
        val ledger = listOf(owed("s1", 100.0, d1))
        val refunds = listOf(refund("r1", "s1", 100.0, d2))

        val all = CashBasis.compute(sales, ledger, refunds, 0L, Long.MAX_VALUE)
        assertEquals(0.0, all.collected, CENT)
        assertEquals("nothing was recognised, so nothing can be reversed", 0.0, all.refunded, CENT)
        assertEquals(0.0, all.netRevenue, CENT)
        assertEquals(0.0, all.grossProfit, CENT)
    }

    /** And money arriving AFTER that refund cannot resurrect a sale whose goods came back. */
    @Test
    fun `a repayment after a full refund recognises nothing`() {
        val sales = listOf(sale("s1", d1, total = 100.0, paid = 0.0, cost = 60.0))
        val ledger = listOf(owed("s1", 100.0, d1), paid(100.0, d3))
        val refunds = listOf(refund("r1", "s1", 100.0, d2))

        val all = CashBasis.compute(sales, ledger, refunds, 0L, Long.MAX_VALUE)
        assertEquals(0.0, all.netRevenue, CENT)
        assertEquals(0.0, all.grossProfit, CENT)
    }

    /**
     * ★ RECOGNITION THAT ARRIVES AFTER A REFUND is capped at the part of the sale still
     * live. Half the goods came back, so the most this sale can ever recognise is half its
     * value however much money turns up later.
     */
    @Test
    fun `a repayment after a partial refund tops up only the live half`() {
        // Sold 100 entirely on account, half returned, then the customer pays the lot.
        val sales = listOf(sale("s1", d1, total = 100.0, paid = 0.0, cost = 60.0))
        val ledger = listOf(owed("s1", 100.0, d1), paid(100.0, d3))
        val refunds = listOf(refund("r1", "s1", 50.0, d2))

        val all = CashBasis.compute(sales, ledger, refunds, 0L, Long.MAX_VALUE)
        assertEquals("only the live half of the sale", 50.0, all.netRevenue, CENT)
        assertEquals(30.0, all.cogs, CENT)
        assertEquals(20.0, all.grossProfit, CENT)
        // And it lands on the day the money arrived, not on the day of the sale.
        assertEquals(0.0, CashBasis.compute(sales, ledger, refunds, d1, d1 + DAY).collected, CENT)
        assertEquals(50.0, CashBasis.compute(sales, ledger, refunds, d3, d3 + DAY).collected, CENT)
    }

    /**
     * The awkward case: part was collected at the till, then MORE was refunded than had
     * been recognised, then the rest was repaid. The reversal is clamped at what the books
     * actually held, and the later repayment tops the sale back up to its live share — so
     * lifetime recognition equals the live half, which is also exactly the cash the shop
     * ended up keeping (40 in, 50 out, 60 in = 50).
     */
    @Test
    fun `a part-paid sale refunded then repaid ends at the live share, never above it`() {
        val sales = listOf(sale("s1", d1, total = 100.0, paid = 40.0, cost = 60.0))
        val ledger = listOf(owed("s1", 60.0, d1), paid(60.0, d3))
        val refunds = listOf(refund("r1", "s1", 50.0, d2))

        val cs = CashBasis.contributions(sales, ledger, refunds)
        val lifetimeNet = cs.filter { it.saleId == "s1" }.sumOf { it.net }
        assertEquals(50.0, lifetimeNet, CENT)

        // Nothing was reversed on the refund's day: at that moment only 40 had been
        // recognised, and 40 is less than the 50 the sale was still allowed to keep.
        assertEquals(0.0, CashBasis.compute(sales, ledger, refunds, d2, d2 + DAY).refunded, CENT)
        // The repayment tops recognition up to the live 50 and stops.
        assertEquals(10.0, CashBasis.compute(sales, ledger, refunds, d3, d3 + DAY).collected, CENT)

        val all = CashBasis.compute(sales, ledger, refunds, 0L, Long.MAX_VALUE)
        assertEquals("half the sale's cost, and only half", 30.0, all.cogs, CENT)
        assertEquals(20.0, all.grossProfit, CENT)
        assertEquals(all.grossProfit, all.costedRevenue - all.cogs, CENT)
    }

    /** A refund whose original sale isn't in the read model has no basis to pro-rate. */
    @Test
    fun `a refund against an unknown sale is ignored`() {
        val sales = listOf(sale("s1", d1, total = 100.0, paid = 100.0, cost = 60.0))
        val refunds = listOf(refund("r1", "ghost", 100.0, d2))

        val all = CashBasis.compute(sales, emptyList(), refunds, 0L, Long.MAX_VALUE)
        assertEquals(100.0, all.collected, CENT)
        assertEquals(0.0, all.refunded, CENT)
        assertEquals(100.0, all.netRevenue, CENT)
    }

    /**
     * ★ THE INVARIANT, RE-ASSERTED WITH REFUNDS IN THE MIX. For every sale, lifetime NET
     * recognition must be exactly `min(money collected on it, the share not refunded)` —
     * never negative, never above the sale, and never above what was collected.
     */
    @Test
    fun `no sale is ever recognised beyond what it collected less what was refunded`() {
        val sales = listOf(
            sale("full", d1, total = 100.0, paid = 100.0, cost = 60.0),
            sale("half", d1, total = 80.0, paid = 80.0, cost = 40.0),
            sale("credit", d1, total = 60.0, paid = 0.0, cost = 30.0),
            sale("part", d2, total = 50.0, paid = 20.0, cost = 25.0),
            sale("clean", d2, total = 30.0, paid = 30.0, cost = 10.0, customerId = "c2")
        )
        val ledger = listOf(
            owed("credit", 60.0, d1),
            owed("part", 30.0, d2),
            paid(90.0, d4)      // clears "credit" (60) and 30 of "part"
        )
        val refunds = listOf(
            refund("r1", "full", 100.0, d2),   // whole sale back
            refund("r2", "half", 40.0, d3),    // half back
            refund("r3", "credit", 60.0, d3),  // never paid for ⇒ nothing to reverse
            refund("r4", "part", 12.5, d3)     // a quarter back
        )

        val cs = CashBasis.contributions(sales, ledger, refunds)

        // Expected live share of each sale, and the raw money that ever arrived on it.
        val liveShare = mapOf(
            "full" to 0.0, "half" to 40.0, "credit" to 0.0, "part" to 37.5, "clean" to 30.0
        )
        val rawCollected = mapOf(
            "full" to 100.0, "half" to 80.0, "credit" to 60.0, "part" to 50.0, "clean" to 30.0
        )
        val net = cs.groupBy { it.saleId }.mapValues { (_, l) -> l.sumOf { c -> c.net } }

        for (s in sales) {
            val n = net[s.id] ?: 0.0
            val expected = minOf(rawCollected[s.id]!!, liveShare[s.id]!!)
            assertEquals("sale ${s.id}", expected, n, CENT)
            assertTrue("sale ${s.id} went negative: $n", n >= -CENT)
            assertTrue("sale ${s.id} exceeded its total: $n", n <= s.total + CENT)
        }

        // Windows still partition the whole history, refunds included.
        val whole = CashBasis.compute(sales, ledger, refunds, 0L, Long.MAX_VALUE)
        val days = listOf(d1, d2, d3, d4).map { CashBasis.compute(sales, ledger, refunds, it, it + DAY) }
        assertEquals(whole.collected, days.sumOf { it.collected }, CENT)
        assertEquals(whole.refunded, days.sumOf { it.refunded }, CENT)
        assertEquals(whole.grossProfit, days.sumOf { it.grossProfit }, CENT)
        // And the profit identity holds on the whole history as well as on each day.
        assertEquals(whole.grossProfit, whole.costedRevenue - whole.cogs, CENT)
        days.forEach { assertEquals(it.grossProfit, it.costedRevenue - it.cogs, CENT) }
    }
}
