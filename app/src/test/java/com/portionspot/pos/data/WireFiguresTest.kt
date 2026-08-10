package com.portionspot.pos.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The two figures Android states outright on the wire — `sales.line_discount_total`
 * and `sales.profit_total` — pinned against the worked example the shared money model
 * was settled on, plus the invariants a reader on the other side is entitled to rely on.
 *
 * The point of these columns is that the OTHER client should not have to reconstruct
 * anything. So the assertions here are mostly of the form "what a reader computes from
 * what we sent equals what we meant", because that is the only thing a wire contract
 * can actually promise.
 */
class WireFiguresTest {
    private val eps = 1e-9
    private val cent = 0.005

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

    /** A sale persisted exactly as [PosRepository.checkout] would persist these lines. */
    private fun saleFor(
        lines: List<SaleLine>,
        saleDiscount: Double = 0.0,
        vatEnabled: Boolean = false,
        vatPercent: Double = 0.0,
        totalRounding: Double = 0.0
    ): SaleEntity {
        val subtotal = lines.sumOf { it.unitPrice * it.qty }
        val perItemDiscount = lines.sumOf { it.lineDiscount }
        val perItemMarkup = lines.sumOf { it.lineMarkup }
        val t = computeSaleTotals(
            subtotal, saleDiscount + perItemDiscount, vatEnabled, vatPercent, perItemMarkup
        )
        val total =
            if (totalRounding > 0.0) Math.round(t.total / totalRounding) * totalRounding
            else t.total
        return SaleEntity(
            businessId = "b",
            subtotal = subtotal,
            discountTotal = t.discount,
            markupTotal = t.markup,
            taxTotal = t.taxTotal,
            total = total
        )
    }

    // ─────────────────────── the settled worked example ───────────────────────

    /**
     * The sale both sides agreed to compare row by row: a line discount AND a line
     * markup AND a whole-sale discount AND VAT, on one receipt.
     *
     *   line A   2 × $50.00  −$8.00 discount              = $92.00
     *   line B   1 × $30.00              +$5.00 markup    = $35.00
     *   goods (allLinesNet)                                 $127.00
     *   whole-sale discount                                 −$10.00
     *   taxable base  = 130 − 18 + 5                        $117.00
     *   VAT @ 15%                                            $17.55
     *   total                                               $134.55
     */
    @Test
    fun theWorkedExample_statesEveryFigureTheWebAsksFor() {
        val a = line(qty = 2.0, unitPrice = 50.0, lineDiscount = 8.0, unitCost = 20.0)
        val b = line(qty = 1.0, unitPrice = 30.0, lineMarkup = 5.0, unitCost = 12.0)
        val lines = listOf(a, b)
        val sale = saleFor(lines, saleDiscount = 10.0, vatEnabled = true, vatPercent = 15.0)

        // The three columns whose meaning was already shared, unchanged.
        assertEquals(130.00, sale.subtotal, cent)       // GROSS
        assertEquals(18.00, sale.discountTotal, cent)   // COMBINED: 10 whole-sale + 8 line
        assertEquals(5.00, sale.markupTotal, cent)      // Σ line markup
        assertEquals(17.55, sale.taxTotal, cent)
        assertEquals(134.55, sale.total, cent)

        // taxable_base = subtotal − discount_total + markup_total
        assertEquals(117.00, sale.subtotal - sale.discountTotal + sale.markupTotal, cent)

        // The new column, and the subtraction it exists to enable.
        val lineDiscountTotal = saleLineDiscountTotal(sale, lines)
        assertEquals(8.00, lineDiscountTotal, cent)
        assertEquals(10.00, sale.discountTotal - lineDiscountTotal, cent)  // sale_discount

        // profit_total: costed revenue − cost. Both lines are costed, so the costed
        // share of the whole-sale discount is all $10 of it.
        //   costedLinesNet 127.00 − discountShare 10.00 = costedRevenue 117.00
        //   cost           2×20 + 1×12 = 52.00
        val m = saleMarginFromLines(sale, lines)
        assertEquals(10.00, m.discountShare, cent)
        assertEquals(117.00, m.costedRevenue, cent)
        assertEquals(52.00, m.costTotal, cent)
        assertEquals(65.00, saleProfitTotal(sale, lines), cent)
        assertEquals(52.00, saleCostTotal(sale, lines), cent)
    }

    // ─────────────────────────── line_discount_total ───────────────────────────

    @Test
    fun lineDiscountTotal_isZeroWhenNoLineCarriesADiscount() {
        val lines = listOf(line(qty = 2.0, unitPrice = 50.0), line(qty = 1.0, unitPrice = 30.0))
        val sale = saleFor(lines, saleDiscount = 25.0)
        // All $25 of the discount is the whole-sale one; none of it came off a line.
        assertEquals(0.0, saleLineDiscountTotal(sale, lines), eps)
        assertEquals(25.0, sale.discountTotal - saleLineDiscountTotal(sale, lines), cent)
    }

    @Test
    fun lineDiscountTotal_excludesTombstonedLines() {
        // A line removed by an in-place receipt edit is not part of what the receipt
        // says now, so its discount must not be counted. The caller filters; this test
        // pins that the function honours the filtered list it is handed.
        val kept = line(qty = 1.0, unitPrice = 100.0, lineDiscount = 15.0)
        val removed = line(qty = 1.0, unitPrice = 40.0, lineDiscount = 9.0).copy(deleted = true)
        val live = listOf(kept, removed).filter { !it.deleted }
        val sale = saleFor(live)
        assertEquals(15.0, saleLineDiscountTotal(sale, live), cent)
    }

    /**
     * ★ The clamp. `computeSaleTotals` caps the COMBINED discount at the goods value, so
     * an over-large whole-sale discount makes the stored `discountTotal` smaller than the
     * discounts the lines themselves carry. Unclamped, `discountTotal − lineDiscountTotal`
     * would hand the reader a NEGATIVE whole-sale discount on a sale that had none.
     */
    @Test
    fun lineDiscountTotal_neverExceedsTheClampedDiscountTotal() {
        val lines = listOf(line(qty = 1.0, unitPrice = 100.0, lineDiscount = 90.0))
        // 90 line + 50 whole-sale = 140 requested, clamped to the 100 of goods.
        val sale = saleFor(lines, saleDiscount = 50.0)
        assertEquals(100.0, sale.discountTotal, cent)

        val lineDiscountTotal = saleLineDiscountTotal(sale, lines)
        assertEquals(90.0, lineDiscountTotal, cent)
        assertTrue(
            "sale_discount must never be negative",
            sale.discountTotal - lineDiscountTotal >= -eps
        )
    }

    @Test
    fun lineDiscountTotal_clampsWhenTheLinesAloneOverrunTheGoodsValue() {
        // Pathological but reachable: a fixed-amount per-item discount larger than the
        // line it sits on. discountTotal clamps to 100; the raw line sum is 130.
        val lines = listOf(
            line(qty = 1.0, unitPrice = 60.0, lineDiscount = 80.0),
            line(qty = 1.0, unitPrice = 40.0, lineDiscount = 50.0)
        )
        val sale = saleFor(lines)
        assertEquals(100.0, sale.discountTotal, cent)
        assertEquals(100.0, saleLineDiscountTotal(sale, lines), cent)
        assertEquals(0.0, sale.discountTotal - saleLineDiscountTotal(sale, lines), cent)
    }

    // ─────────────────────────────── profit_total ───────────────────────────────

    @Test
    fun profitTotal_isZeroWhenNothingIsCosted() {
        // Unknown profit is not a loss, and must not be reported as one to the web.
        val lines = listOf(line(qty = 2.0, unitPrice = 50.0), line(qty = 1.0, unitPrice = 30.0))
        val sale = saleFor(lines, saleDiscount = 25.0)
        assertEquals(0.0, saleProfitTotal(sale, lines), eps)
        assertEquals(0.0, saleCostTotal(sale, lines), eps)
    }

    @Test
    fun profitTotal_countsOnlyTheCostedLinesAndSharesTheDiscountProRata() {
        // Half the goods are costed, so the costed lines carry half the whole-sale
        // discount — not all of it (understates) and not none of it (overstates).
        val costed = line(qty = 1.0, unitPrice = 100.0, unitCost = 60.0)
        val uncosted = line(qty = 1.0, unitPrice = 100.0)
        val lines = listOf(costed, uncosted)
        val sale = saleFor(lines, saleDiscount = 20.0)

        val m = saleMarginFromLines(sale, lines)
        assertEquals(10.0, m.discountShare, cent)     // half of 20
        assertEquals(90.0, m.costedRevenue, cent)     // 100 − 10
        assertEquals(60.0, m.costTotal, cent)
        assertEquals(30.0, saleProfitTotal(sale, lines), cent)
    }

    @Test
    fun profitTotal_liftsAPerUnitCostToTheLineUnitExactlyOnce() {
        // A box line is PRICED per box and COSTED per stock unit. unitsPerLine bridges
        // them, and applying it twice was a live bug class in this app.
        val boxes = line(qty = 2.0, unitPrice = 120.0, unitCost = 10.0, unitsPerLine = 12)
        val sale = saleFor(listOf(boxes))
        // cost = 10 × 12 × 2 = 240; revenue = 240; profit = 0.
        assertEquals(240.0, saleCostTotal(sale, listOf(boxes)), cent)
        assertEquals(0.0, saleProfitTotal(sale, listOf(boxes)), cent)
    }

    @Test
    fun profitTotal_isSignedSoASaleBelowCostSaysSo() {
        val below = line(qty = 1.0, unitPrice = 50.0, unitCost = 80.0)
        val sale = saleFor(listOf(below))
        assertTrue(saleProfitTotal(sale, listOf(below)) < 0.0)
        assertEquals(-30.0, saleProfitTotal(sale, listOf(below)), cent)
    }

    @Test
    fun profitTotal_isVatExclusive() {
        // VAT is collected for ZIMRA, never earned: the same goods must report the same
        // profit whether or not the shop is VAT-registered.
        val l = line(qty = 1.0, unitPrice = 100.0, unitCost = 40.0)
        val noVat = saleFor(listOf(l))
        val vat = saleFor(listOf(l), vatEnabled = true, vatPercent = 15.0)
        assertEquals(115.0, vat.total, cent)
        assertEquals(
            saleProfitTotal(noVat, listOf(l)),
            saleProfitTotal(vat, listOf(l)),
            cent
        )
    }

    @Test
    fun profitTotal_matchesTheDashboardForTheSameSale() {
        // The one that matters most: what we PUSH and what the owner SEES have to be the
        // same number, or the shop has two profit figures and no way to choose.
        val lines = listOf(
            line(qty = 3.0, unitPrice = 25.0, lineDiscount = 5.0, unitCost = 11.0),
            line(qty = 1.0, unitPrice = 60.0, lineMarkup = 4.0, unitCost = 30.0),
            line(qty = 2.0, unitPrice = 10.0)
        )
        val sale = saleFor(lines, saleDiscount = 12.0, vatEnabled = true, vatPercent = 15.0)

        val costed = lines.filter { it.unitCost != null }
        val dashboard = SaleMarginRow(
            id = sale.id,
            soldAt = sale.soldAt,
            total = sale.total,
            taxTotal = sale.taxTotal,
            amountPaid = sale.total,
            customerId = null,
            allLinesNet = saleGoodsValue(lines),
            costedLinesNet = saleGoodsValue(costed),
            costedLinesCost = costed.sumOf { it.unitCost!! * maxOf(it.unitsPerLine, 1) * it.qty }
        ).margin()

        assertEquals(dashboard.profit, saleProfitTotal(sale, lines), eps)
        assertEquals(dashboard.costTotal, saleCostTotal(sale, lines), eps)
    }

    // ───────────────────────────── invariants, swept ─────────────────────────────

    /**
     * Over a thousand randomised sales: whatever we send, a reader doing the documented
     * subtraction gets a whole-sale discount that is neither negative nor larger than the
     * combined total. This is the promise `line_discount_total` exists to make.
     */
    @Test
    fun saleDiscountBySubtraction_isAlwaysWellFormed() {
        val r = Random(20260810)
        repeat(1000) {
            val n = r.nextInt(1, 5)
            val lines = List(n) {
                val price = r.nextInt(1, 200).toDouble()
                val qty = r.nextInt(1, 5).toDouble()
                line(
                    qty = qty,
                    unitPrice = price,
                    // Deliberately allowed to overrun the line, to exercise the clamp.
                    lineDiscount = if (r.nextBoolean()) r.nextInt(0, (price * qty).toInt() + 40).toDouble() else 0.0,
                    lineMarkup = if (r.nextBoolean()) r.nextInt(0, 30).toDouble() else 0.0,
                    unitCost = if (r.nextBoolean()) r.nextInt(1, 100).toDouble() else null,
                    unitsPerLine = if (r.nextBoolean()) r.nextInt(1, 13) else 1
                )
            }
            val sale = saleFor(
                lines,
                saleDiscount = r.nextInt(0, 60).toDouble(),
                vatEnabled = r.nextBoolean(),
                vatPercent = 15.0
            )
            val ldt = saleLineDiscountTotal(sale, lines)
            val saleDiscount = sale.discountTotal - ldt

            assertTrue("line_discount_total must not be negative", ldt >= -eps)
            assertTrue("sale_discount must not be negative", saleDiscount >= -eps)
            assertTrue(
                "sale_discount must not exceed discount_total",
                saleDiscount <= sale.discountTotal + eps
            )
        }
    }

    /**
     * Profit never silently exceeds the VAT-exclusive money the shop actually billed.
     * A figure that does is the signature of a cost that was dropped or a discount that
     * was counted on the wrong side.
     */
    @Test
    fun profitNeverExceedsTheNetTake() {
        val r = Random(760413)
        repeat(1000) {
            val n = r.nextInt(1, 5)
            val lines = List(n) {
                line(
                    qty = r.nextInt(1, 5).toDouble(),
                    unitPrice = r.nextInt(1, 200).toDouble(),
                    lineDiscount = r.nextInt(0, 20).toDouble(),
                    lineMarkup = r.nextInt(0, 20).toDouble(),
                    unitCost = if (r.nextBoolean()) r.nextInt(1, 80).toDouble() else null,
                    unitsPerLine = 1
                )
            }
            val sale = saleFor(lines, saleDiscount = r.nextInt(0, 40).toDouble())
            val netTake = sale.total - sale.taxTotal
            assertTrue(
                "profit must not exceed the net take",
                saleProfitTotal(sale, lines) <= netTake + cent
            )
        }
    }
}
