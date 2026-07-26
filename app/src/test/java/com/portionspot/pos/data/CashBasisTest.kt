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
 *     for every sale S:  Σ (contributions of S over all time)  ==  money collected on S
 *
 * plus the pro-rating rule, the window placement (a repayment counts on ITS day), and the
 * two deliberate exclusions (write-offs and correction lots).
 */
class CashBasisTest {

    private val DAY = 86_400_000L
    private val d1 = 1_700_000_000_000L      // "day 1"
    private val d2 = d1 + DAY
    private val d3 = d1 + 2 * DAY

    /** A sale of [total] whose costed lines carry [cost] of goods. */
    private fun sale(
        id: String,
        soldAt: Long,
        total: Double,
        paid: Double,
        cost: Double,
        customerId: String? = "c1"
    ) = CashBasisSaleRow(
        id = id,
        soldAt = soldAt,
        total = total,
        amountPaid = paid,
        customerId = customerId,
        costedRevenue = total,
        lineProfit = total - cost
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

        val byLifetime = CashBasis.contributions(sales, ledger)
            .groupBy { it.saleId }
            .mapValues { (_, cs) -> cs.sumOf { it.amount } }

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

        val day1 = CashBasis.compute(sales, ledger, d1, d1 + DAY)
        val day2 = CashBasis.compute(sales, ledger, d2, d2 + DAY)
        val day3 = CashBasis.compute(sales, ledger, d3, d3 + DAY)

        assertEquals(40.0, day1.revenue, 0.0001)   // taken at the till
        assertEquals(0.0, day2.revenue, 0.0001)    // nothing happened
        assertEquals(60.0, day3.revenue, 0.0001)   // the repayment, on ITS day
        assertEquals(100.0, day1.revenue + day2.revenue + day3.revenue, 0.0001)

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

        val p = CashBasis.compute(sales, ledger, d1, d1 + DAY)
        assertEquals(40.0, p.revenue, 0.0001)
        assertEquals(24.0, p.cogs, 0.0001)
        assertEquals(16.0, p.grossProfit, 0.0001)
    }

    /** A part-paid sale must never show a LOSS just because the cost is front-loaded. */
    @Test
    fun `a thinly paid sale still shows a proportional profit, never a fake loss`() {
        // 10% collected on a sale with a 60% cost ratio.
        val sales = listOf(sale("s1", d1, total = 100.0, paid = 10.0, cost = 60.0))
        val ledger = listOf(owed("s1", 90.0, d1))

        val p = CashBasis.compute(sales, ledger, d1, d1 + DAY)
        assertEquals(10.0, p.revenue, 0.0001)
        assertEquals(6.0, p.cogs, 0.0001)
        assertEquals(4.0, p.grossProfit, 0.0001)
        assertTrue("a part-paid sale must not read as a loss", p.grossProfit > 0.0)
    }

    /** Across the whole life of a sale, the cost recognised is the WHOLE cost — once. */
    @Test
    fun `full settlement recognises the full cost exactly once`() {
        val sales = listOf(sale("s1", d1, total = 100.0, paid = 40.0, cost = 60.0))
        val ledger = listOf(owed("s1", 60.0, d1), paid(60.0, d3))

        val all = CashBasis.compute(sales, ledger, 0L, Long.MAX_VALUE)
        assertEquals(100.0, all.revenue, 0.0001)
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

        val all = CashBasis.compute(sales, ledger, 0L, Long.MAX_VALUE)
        assertEquals("a write-off is not money", 0.0, all.revenue, 0.0001)
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

        val byLifetime = CashBasis.contributions(sales, ledger)
            .groupBy { it.saleId }
            .mapValues { (_, cs) -> cs.sumOf { it.amount } }

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

        val all = CashBasis.compute(sales, ledger, 0L, Long.MAX_VALUE)
        assertEquals("only the real sale counts", 20.0, all.revenue, 0.0001)
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

        val byLifetime = CashBasis.contributions(sales, ledger)
            .groupBy { it.saleId }
            .mapValues { (_, cs) -> cs.sumOf { it.amount } }

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

        val byLifetime = CashBasis.contributions(sales, ledger)
            .groupBy { it.saleId }
            .mapValues { (_, cs) -> cs.sumOf { it.amount } }

        assertEquals(50.0, byLifetime["a"]!!, 0.0001)
        assertEquals(null, byLifetime["b"])
    }

    /** Paying MORE than is owed cannot recognise more than the sales behind it. */
    @Test
    fun `an over-repayment recognises only what was actually owed`() {
        val sales = listOf(sale("s1", d1, total = 30.0, paid = 0.0, cost = 10.0))
        val ledger = listOf(owed("s1", 30.0, d1), paid(100.0, d2))

        val all = CashBasis.compute(sales, ledger, 0L, Long.MAX_VALUE)
        assertEquals(30.0, all.revenue, 0.0001)
    }

    /** A deleted ledger row is ignored on both sides of the match. */
    @Test
    fun `tombstoned ledger rows are ignored`() {
        val sales = listOf(sale("s1", d1, total = 40.0, paid = 0.0, cost = 10.0))
        val ledger = listOf(
            owed("s1", 40.0, d1),
            paid(40.0, d2).copy(deleted = true)
        )

        val all = CashBasis.compute(sales, ledger, 0L, Long.MAX_VALUE)
        assertEquals(0.0, all.revenue, 0.0001)
    }

    /** A zero-total sale (a full giveaway) has no ratio to apply and cannot divide by 0. */
    @Test
    fun `a zero total sale recognises no goods economics`() {
        val sales = listOf(sale("free", d1, total = 0.0, paid = 0.0, cost = 0.0))
        val all = CashBasis.compute(sales, emptyList(), 0L, Long.MAX_VALUE)
        assertEquals(0.0, all.revenue, 0.0001)
        assertEquals(0.0, all.grossProfit, 0.0001)
    }
}
