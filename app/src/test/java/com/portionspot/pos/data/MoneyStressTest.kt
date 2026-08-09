package com.portionspot.pos.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Adversarial sweep over the money surface where a SALE, a REFUND and a MARGIN meet.
 *
 * The other *MathTests pin worked examples. This one pins INVARIANTS: properties that
 * must hold for every sale the shop can ring up, checked over randomised inputs with a
 * fixed seed so a failure is always reproducible.
 *
 * ★ WHY IT EXISTS. Two faults hid for a long time behind example-based tests, because
 * every example used lines with no discount and sales with no uncosted goods:
 *
 *  1. A refund valued a returned line at a bare `unitPrice × qtyReturned`, so a line
 *     carrying its own discount or cashier markup had that adjustment smeared across
 *     every other line of the sale instead of going back with it.
 *  2. Gross profit summed a bare `unitPrice × qty` and never touched the whole-sale
 *     discount, so a discount cost the shop nothing in the figures and a markup earned
 *     it nothing.
 *
 * Numbers are compared with [eps] rather than exactly: these are IEEE doubles, and the
 * app's own tolerance for "same money" is CENT = 0.005.
 */
class MoneyStressTest {
    private val eps = 1e-9
    private val cent = 0.005

    // ── the shape checkout actually persists ────────────────────────────────────────

    private fun line(
        qty: Double,
        unitPrice: Double,
        lineDiscount: Double = 0.0,
        lineMarkup: Double = 0.0,
        unitCost: Double? = null,
        unitsPerLine: Int = 1
    ) = SaleLine(
        saleId = "s", businessId = "b", itemId = "i", name = "x",
        qty = qty, unitPrice = unitPrice, unitCost = unitCost,
        lineDiscount = lineDiscount, lineMarkup = lineMarkup, unitsPerLine = unitsPerLine
    )

    /**
     * The totals checkout would write for these lines — built the SAME way
     * [PosRepository.checkout] builds them, which is the only reason these assertions
     * mean anything:
     *   subtotal   = Σ unitPrice×qty          (GROSS — per-item adjustments are NOT in it)
     *   discount   = whole-sale discount + Σ lineDiscount
     *   markup     = Σ lineMarkup
     */
    private fun totalsFor(
        lines: List<SaleLine>,
        saleDiscount: Double = 0.0,
        vatEnabled: Boolean = false,
        vatPercent: Double = 0.0
    ): SaleTotals = computeSaleTotals(
        subtotal = lines.sumOf { it.unitPrice * it.qty },
        discount = saleDiscount + lines.sumOf { it.lineDiscount },
        vatEnabled = vatEnabled,
        vatPercent = vatPercent,
        markup = lines.sumOf { it.lineMarkup }
    )

    /** The margin the reports compute for these lines — via the one function that decides. */
    private fun marginFor(lines: List<SaleLine>, t: SaleTotals): SaleMargin {
        val costed = lines.filter { it.unitCost != null }
        return computeSaleMargin(
            allLinesNet = saleGoodsValue(lines),
            costedLinesNet = saleGoodsValue(costed),
            costedLinesCost = costed.sumOf {
                it.unitCost!! * maxOf(it.unitsPerLine, 1) * it.qty
            },
            saleNetTake = t.total - t.taxTotal
        )
    }

    /** The valuation the app used BEFORE the fix, kept so the damage can be measured. */
    private fun oldReturnedValue(l: SaleLine, qtyReturned: Double) = l.unitPrice * qtyReturned

    /** A random sale whose discounts always fit inside the goods value, so
     *  [computeSaleTotals]'s clamp never fires and the invariants stay exact. */
    private fun randomSale(r: Random): Pair<List<SaleLine>, Double> {
        val lines = (1..(1 + r.nextInt(6))).map {
            val qty = 1 + r.nextInt(10).toDouble()
            val price = r.nextDouble(0.5, 400.0)
            val gross = price * qty
            line(
                qty = qty,
                unitPrice = price,
                lineDiscount = if (r.nextBoolean()) r.nextDouble(0.0, gross * 0.4) else 0.0,
                lineMarkup = if (r.nextBoolean()) r.nextDouble(0.0, gross * 0.2) else 0.0,
                unitCost = if (r.nextBoolean()) r.nextDouble(0.1, price * 0.7) else null,
                unitsPerLine = if (r.nextBoolean()) 1 else 1 + r.nextInt(12)
            )
        }
        val gross = lines.sumOf { it.unitPrice * it.qty }
        val headroom = gross - lines.sumOf { it.lineDiscount }
        val saleDiscount = if (r.nextBoolean()) r.nextDouble(0.0, headroom * 0.5) else 0.0
        return lines to saleDiscount
    }

    // ─────────────────── returnedLineValue: the returned-goods fault ───────────────────

    @Test
    fun returnedLineValue_subtractsTheLinesOwnDiscount() {
        // The regression this function exists for: $100 of goods with $20 off was worth
        // $80 to the customer, so returning all of it is worth $80.
        assertEquals(80.0, returnedLineValue(1.0, 100.0, 20.0, 0.0, 1.0), eps)
    }

    @Test
    fun returnedLineValue_addsTheLinesOwnMarkup() {
        // A cashier markup is money the customer HANDED OVER. It goes back with the goods.
        assertEquals(110.0, returnedLineValue(1.0, 100.0, 0.0, 10.0, 1.0), eps)
    }

    @Test
    fun returnedLineValue_proRatesAdjustmentsOnAPartialReturn() {
        // Half of a $100 line with $20 off is worth $40, not $50.
        assertEquals(40.0, returnedLineValue(2.0, 50.0, 20.0, 0.0, 1.0), eps)
        // Half of a $100 line with $10 added on is worth $55.
        assertEquals(55.0, returnedLineValue(2.0, 50.0, 0.0, 10.0, 1.0), eps)
    }

    @Test
    fun returnedLineValue_isFaceValueWhenTheLineCarriesNoAdjustment() {
        // Most lines carry neither, so the fix must be a no-op for them — otherwise it
        // would move money on the shop's ordinary sales.
        val r = Random(20260809)
        repeat(500) {
            val qty = 1 + r.nextInt(20).toDouble()
            val price = r.nextDouble(0.01, 500.0)
            val back = r.nextDouble(0.0, qty)
            assertEquals(price * back, returnedLineValue(qty, price, 0.0, 0.0, back), 1e-9)
        }
    }

    @Test
    fun returnedLineValue_clampsAnOverLargeReturnToTheWholeLine() {
        // Returning 5 of a 3-unit line can never be worth more than the whole line.
        assertEquals(24.0, returnedLineValue(3.0, 10.0, 6.0, 0.0, 5.0), eps)
    }

    @Test
    fun returnedLineValue_isZeroForDegenerateInput() {
        assertEquals(0.0, returnedLineValue(0.0, 10.0, 0.0, 0.0, 1.0), eps)   // no qty: no divide-by-zero
        assertEquals(0.0, returnedLineValue(3.0, 10.0, 0.0, 0.0, 0.0), eps)   // nothing returned
        assertEquals(0.0, returnedLineValue(3.0, 10.0, 0.0, 0.0, -2.0), eps)  // negative return
    }

    @Test
    fun returnedLineValue_ignoresPackSize_multiplierAppliedExactlyOnce() {
        // unitsPerLine drives STOCK movement, never money. A box of 12 priced at $60 is
        // worth $60 back, not $720. (Mistake-ledger: the doubled multiplier.)
        val box = line(qty = 1.0, unitPrice = 60.0, unitsPerLine = 12)
        assertEquals(60.0, returnedLineValue(box, 1.0), eps)
    }

    @Test
    fun returnedLineValue_isMonotonicInQuantityReturned() {
        val r = Random(4242)
        repeat(200) {
            val qty = 1 + r.nextInt(10).toDouble()
            val price = r.nextDouble(1.0, 200.0)
            val disc = r.nextDouble(0.0, price * qty)
            var prev = -1.0
            var back = 0.0
            while (back <= qty) {
                val v = returnedLineValue(qty, price, disc, 0.0, back)
                assertTrue("value must not decrease as more is returned", v >= prev - eps)
                prev = v
                back += qty / 8.0
            }
        }
    }

    @Test
    fun oldFormula_missedExactlyTheReturnedShareOfTheLinesAdjustments() {
        // Quantifies the fault: the gap is the returned fraction of the line's own
        // discount and markup, never anything else.
        val r = Random(77)
        repeat(500) {
            val qty = 1 + r.nextInt(12).toDouble()
            val price = r.nextDouble(1.0, 300.0)
            val disc = r.nextDouble(0.0, price * qty)
            val markup = r.nextDouble(0.0, price)
            val back = r.nextDouble(0.0, qty)
            val l = line(qty, price, disc, markup)

            val gap = oldReturnedValue(l, back) - returnedLineValue(l, back)
            assertEquals((disc - markup) * (back / qty), gap, 1e-9)
        }
    }

    // ─────────────────────── the whole-sale refund invariant ───────────────────────

    @Test
    fun fullReturnOfEveryLine_refundsExactlyTheSaleTotal() {
        // THE invariant. Whatever the mix of line discounts, cashier markups, whole-sale
        // discount and VAT, handing everything back returns exactly what was paid — no
        // more and no less. Pairing a net numerator with the GROSS sale.subtotal (the
        // shape this branch stores) breaks it, which is why both sides come from
        // returnedLineValue.
        val r = Random(20260809)
        repeat(2000) {
            val (lines, saleDiscount) = randomSale(r)
            val vatOn = r.nextBoolean()
            val t = totalsFor(lines, saleDiscount, vatOn, if (vatOn) 15.0 else 0.0)

            val goods = saleGoodsValue(lines)
            val returned = lines.sumOf { returnedLineValue(it, it.qty) }
            val refund = computeRefundTotal(returned, goods, t.total).refundTotal

            assertEquals("a full return must repay the sale total", t.total, refund, cent)
        }
    }

    @Test
    fun partialReturnsOfDisjointLines_sumToTheFullRefund() {
        // Returning the lines one at a time must come to the same money as returning
        // them together — otherwise the shop's exposure depends on how the cashier
        // splits the return.
        val r = Random(31337)
        repeat(1000) {
            val (lines, saleDiscount) = randomSale(r)
            val t = totalsFor(lines, saleDiscount, vatEnabled = true, vatPercent = 15.0)
            val goods = saleGoodsValue(lines)

            val oneByOne = lines.sumOf {
                computeRefundTotal(returnedLineValue(it, it.qty), goods, t.total).refundTotal
            }
            assertEquals(t.total, oneByOne, cent)
        }
    }

    @Test
    fun aPartialReturnNeverRepaysMoreThanTheSale() {
        val r = Random(909)
        repeat(1000) {
            val (lines, saleDiscount) = randomSale(r)
            val t = totalsFor(lines, saleDiscount, vatEnabled = r.nextBoolean(), vatPercent = 15.0)
            val goods = saleGoodsValue(lines)

            val returned = lines.sumOf { returnedLineValue(it, r.nextDouble(0.0, it.qty)) }
            val refund = computeRefundTotal(returned, goods, t.total).refundTotal
            assertTrue("partial refund $refund exceeded sale total ${t.total}", refund <= t.total + cent)
            assertTrue("refund must never be negative", refund >= -cent)
        }
    }

    @Test
    fun returningOneDiscountedLineOfTwo_repaysWhatThatLineCost() {
        // The concrete case the old code got wrong. Two $100 lines, $20 off ONE of them.
        // Sale total $180. The old valuation charged the discount to BOTH lines and
        // repaid $90 for each; the discounted line was only ever worth $80.
        val discounted = line(qty = 1.0, unitPrice = 100.0, lineDiscount = 20.0)
        val plain = line(qty = 1.0, unitPrice = 100.0)
        val lines = listOf(discounted, plain)
        val t = totalsFor(lines)
        assertEquals(180.0, t.total, eps)

        val goods = saleGoodsValue(lines)
        fun refundOf(l: SaleLine) =
            computeRefundTotal(returnedLineValue(l, l.qty), goods, t.total).refundTotal

        assertEquals(80.0, refundOf(discounted), cent)
        assertEquals(100.0, refundOf(plain), cent)
    }

    // ─────────────────────────── computeSaleMargin ───────────────────────────

    @Test
    fun margin_isZeroWhenNothingIsCosted() {
        // Unknown profit is not a loss. With no cost anywhere the answer is zero —
        // never minus the discount.
        val lines = listOf(line(qty = 2.0, unitPrice = 50.0), line(qty = 1.0, unitPrice = 30.0))
        val m = marginFor(lines, totalsFor(lines, saleDiscount = 25.0))
        assertEquals(0.0, m.profit, eps)
        assertEquals(0.0, m.costedRevenue, eps)
        assertEquals(0.0, m.costTotal, eps)
        assertEquals(0.0, m.discountShare, eps)
    }

    @Test
    fun margin_countsOnlyCostedLines() {
        // An uncosted line is unknown, not free: it must not book as pure profit.
        val lines = listOf(
            line(qty = 1.0, unitPrice = 100.0, unitCost = 60.0),
            line(qty = 1.0, unitPrice = 100.0)          // no cost — excluded entirely
        )
        val m = marginFor(lines, totalsFor(lines))
        assertEquals(100.0, m.costedRevenue, eps)
        assertEquals(60.0, m.costTotal, eps)
        assertEquals(40.0, m.profit, eps)
    }

    @Test
    fun margin_subtractsALineDiscountFromProfit() {
        // The second fault: a discount the cashier gave used to cost the shop nothing.
        val full = listOf(line(qty = 1.0, unitPrice = 100.0, unitCost = 60.0))
        val cut = listOf(line(qty = 1.0, unitPrice = 100.0, lineDiscount = 20.0, unitCost = 60.0))
        assertEquals(40.0, marginFor(full, totalsFor(full)).profit, eps)
        assertEquals(20.0, marginFor(cut, totalsFor(cut)).profit, eps)
    }

    @Test
    fun margin_addsALineMarkupToProfit() {
        val marked = listOf(line(qty = 1.0, unitPrice = 100.0, lineMarkup = 15.0, unitCost = 60.0))
        assertEquals(55.0, marginFor(marked, totalsFor(marked)).profit, eps)
    }

    @Test
    fun margin_sharesTheWholeSaleDiscountProRataWithTheCostedLines() {
        // $200 of goods, half of it costed, $40 off the whole sale. The costed half
        // carries $20 of that — not all $40 (which understates) and not none of it
        // (which is what the old SQL did, and overstates on every discounted sale).
        val lines = listOf(
            line(qty = 1.0, unitPrice = 100.0, unitCost = 60.0),
            line(qty = 1.0, unitPrice = 100.0)
        )
        val m = marginFor(lines, totalsFor(lines, saleDiscount = 40.0))
        assertEquals(20.0, m.discountShare, eps)
        assertEquals(80.0, m.costedRevenue, eps)
        assertEquals(20.0, m.profit, eps)
    }

    @Test
    fun margin_liftsCostToTheLineUnitOnABoxSale() {
        // unitPrice is the price of a whole BOX; unitCost is the cost of ONE UNIT. A box
        // of 4 at $80 costing $15 a unit makes $20, not $65.
        val lines = listOf(line(qty = 1.0, unitPrice = 80.0, unitCost = 15.0, unitsPerLine = 4))
        val m = marginFor(lines, totalsFor(lines))
        assertEquals(60.0, m.costTotal, eps)
        assertEquals(20.0, m.profit, eps)
    }

    @Test
    fun margin_treatsAZeroPackSizeAsOne() {
        // A stray 0 box size must not zero the cost and hand back the whole price as profit.
        val lines = listOf(line(qty = 1.0, unitPrice = 80.0, unitCost = 15.0, unitsPerLine = 0))
        assertEquals(15.0, marginFor(lines, totalsFor(lines)).costTotal, eps)
    }

    @Test
    fun margin_ignoresVat_whichIsCollectedNotEarned() {
        val lines = listOf(line(qty = 1.0, unitPrice = 100.0, unitCost = 60.0))
        val plain = marginFor(lines, totalsFor(lines))
        val vatted = marginFor(lines, totalsFor(lines, vatEnabled = true, vatPercent = 15.0))
        assertEquals(plain.profit, vatted.profit, eps)
        assertEquals(40.0, vatted.profit, eps)
    }

    @Test
    fun margin_reportsALossWhenGoodsWentOutBelowCost() {
        // A real loss must show as a real loss. Only the NOTHING-COSTED case floors at zero.
        val lines = listOf(line(qty = 1.0, unitPrice = 50.0, unitCost = 80.0))
        assertEquals(-30.0, marginFor(lines, totalsFor(lines)).profit, eps)
    }

    @Test
    fun margin_neverExceedsTheMoneyActuallyBilled() {
        val r = Random(5150)
        repeat(2000) {
            val (lines, saleDiscount) = randomSale(r)
            val vatOn = r.nextBoolean()
            val t = totalsFor(lines, saleDiscount, vatOn, if (vatOn) 15.0 else 0.0)
            val m = marginFor(lines, t)
            val netTake = t.total - t.taxTotal

            assertTrue("costed revenue ${m.costedRevenue} exceeded net take $netTake",
                m.costedRevenue <= netTake + cent)
            assertTrue("profit exceeded costed revenue", m.profit <= m.costedRevenue + cent)
            assertEquals("profit must reconcile to revenue less cost",
                m.costedRevenue - m.costTotal, m.profit, cent)
        }
    }

    @Test
    fun margin_onAFullyCostedSale_isTheNetTakeLessCost() {
        // With every line costed, the costed revenue IS the VAT-exclusive money billed —
        // the pro-rata share collapses to the whole discount, and nothing is left behind.
        val r = Random(8675309)
        repeat(1000) {
            val (raw, saleDiscount) = randomSale(r)
            val lines = raw.map { if (it.unitCost == null) it.copy(unitCost = it.unitPrice * 0.5) else it }
            val vatOn = r.nextBoolean()
            val t = totalsFor(lines, saleDiscount, vatOn, if (vatOn) 15.0 else 0.0)
            val m = marginFor(lines, t)
            val netTake = t.total - t.taxTotal
            val cost = lines.sumOf { it.unitCost!! * maxOf(it.unitsPerLine, 1) * it.qty }

            assertEquals(netTake, m.costedRevenue, cent)
            assertEquals(netTake - cost, m.profit, cent)
        }
    }

    @Test
    fun margin_recoversTheWholeSaleDiscountFromTheTotals() {
        // The whole-sale discount is derived as allLinesNet − netTake rather than read
        // off sales.discountTotal, which ALSO contains the per-item discounts already
        // taken off the lines. Reading that column whole would double-count them.
        val r = Random(1123)
        repeat(1000) {
            val (raw, saleDiscount) = randomSale(r)
            val lines = raw.map { if (it.unitCost == null) it.copy(unitCost = 1.0) else it }
            val t = totalsFor(lines, saleDiscount)
            val m = marginFor(lines, t)
            // Every line is costed, so the costed share IS the whole-sale discount.
            assertEquals(saleDiscount, m.discountShare, cent)
        }
    }

    @Test
    fun oldProfitFormula_overstatedByTheDiscountsItIgnored() {
        // Measures what the reported figure is about to move by, so the change is not a
        // surprise on the device: the old SQL summed a bare unitPrice×qty and never
        // touched the whole-sale discount.
        val lines = listOf(
            line(qty = 1.0, unitPrice = 100.0, lineDiscount = 20.0, unitCost = 60.0),
            line(qty = 1.0, unitPrice = 100.0, unitCost = 60.0)
        )
        val t = totalsFor(lines, saleDiscount = 30.0)
        val old = lines.sumOf { it.unitPrice * it.qty - it.unitCost!! * it.qty }   // 80
        val now = marginFor(lines, t).profit                                       // 80 − 20 − 30
        assertEquals(80.0, old, eps)
        assertEquals(30.0, now, eps)
    }
}
