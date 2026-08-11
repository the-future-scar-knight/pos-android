package com.portionspot.pos.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The oversell decision — "does this cart take more off the shelf than the shop has?"
 *
 * This exists because the app had NO such check anywhere, and a real shop found out the
 * hard way: an item was oversold, checkout floored the cached figure at zero while writing
 * the full movement to the ledger, and the next sync recomputed `baseline + Σ deltas` and
 * put -2 on the owner's phone. Two separate faults wearing one symptom — nothing warned,
 * and the two records of the same shelf disagreed.
 *
 * What is pinned here is only the first: the arithmetic, kept pure so it cannot drift from
 * the draw-down it describes. The honest-ledger half is a property of [PosRepository]'s
 * checkout (the clamp is gone), and the "may they proceed" half is
 * [com.portionspot.pos.auth.Capability.SELL_BELOW_STOCK].
 */
class OversellTest {

    private fun item(
        name: String = "Widget",
        track: Boolean = true,
        qty: Double = 0.0,
        measured: Double = 0.0,
        type: String = "box",
        boxSize: Int = 1,
        unit: String = "pc",
    ) = Item(
        id = "i-$name",
        businessId = "b1",
        name = name,
        trackStock = track,
        stockQty = qty,
        stockMeasured = measured,
        productType = type,
        boxSize = boxSize,
        unit = unit,
    )

    private fun line(
        item: Item,
        qty: Double,
        mode: String = "retail",
        unitsPerLine: Int = 1,
        measured: Boolean = false,
    ) = CartLine(
        itemId = item.id,
        name = item.name,
        unitPrice = 1.0,
        taxRate = 0.0,
        qty = qty,
        mode = mode,
        unitsPerLine = unitsPerLine,
        measured = measured,
    )

    /** Adding to an empty cart, which is what a tap on a product card does. */
    private fun adding(item: Item, vararg lines: CartLine) =
        oversellFor(item, emptyList(), lines.toList())

    // ── The boundary: exact stock is a sale, not an incident ──────────────

    @Test
    fun `selling exactly what is on hand is not an oversell`() {
        val it = item(qty = 2.0)
        assertNull(
            "selling the last two of two was flagged — the end of a shelf is not an error",
            adding(it, line(it, 2.0))
        )
    }

    @Test
    fun `selling less than is on hand is not an oversell`() {
        val it = item(qty = 2.0)
        assertNull(adding(it, line(it, 1.0)))
    }

    @Test
    fun `one past the on-hand is an oversell, and says by how much`() {
        val it = item(qty = 2.0)
        val over = adding(it, line(it, 3.0))
        assertNotNull("3 sold against 2 on hand did not warn", over)
        assertEquals(2.0, over!!.onHand, 1e-9)
        assertEquals(3.0, over.requested, 1e-9)
        assertEquals(1.0, over.shortfall, 1e-9)
        assertFalse("a shelf of 2 is not already short", over.alreadyShort)
    }

    /** A hair under the on-hand must not trip on float noise — the same half-a-unit
     *  tolerance the money comparisons use. */
    @Test
    fun `a rounding-sized excess is not an oversell`() {
        val it = item(qty = 2.0)
        assertNull(adding(it, line(it, 2.0 + 0.001)))
    }

    // ── Untracked items have no on-hand to exceed ─────────────────────────

    @Test
    fun `an untracked item never warns, at any quantity`() {
        val it = item(track = false, qty = 0.0)
        assertNull(adding(it, line(it, 1.0)))
        assertNull(adding(it, line(it, 9_999.0)))
    }

    /** An untracked item can carry a stale stockQty from before it was untracked; the
     *  trackStock flag decides, not the number sitting next to it. */
    @Test
    fun `an untracked item with a stale figure still never warns`() {
        val it = item(track = false, qty = 1.0)
        assertNull(adding(it, line(it, 50.0)))
    }

    // ── Measured products are counted in the column they live in ──────────

    /**
     * A measured product parks its on-hand in [Item.stockMeasured] and deliberately leaves
     * [Item.stockQty] at 0. Comparing against stockQty would warn on every single sale of
     * every measured product in the shop — the alarm nobody reads.
     */
    @Test
    fun `a measured product compares against stockMeasured, not stockQty`() {
        val oil = item(type = "measured", qty = 0.0, measured = 5.0, unit = "L")

        assertNull(
            "2.5 L out of 5 L warned — it read the empty stockQty",
            adding(oil, line(oil, 2.5, mode = "measured", measured = true))
        )

        val over = adding(oil, line(oil, 6.0, mode = "measured", measured = true))
        assertNotNull("6 L out of 5 L did not warn", over)
        assertEquals(5.0, over!!.onHand, 1e-9)
        assertEquals(1.0, over.shortfall, 1e-9)
        assertTrue(over.measured)
        assertEquals("L", over.unitLabel)
    }

    /** The mirror: a counted product must not be read out of the measured column. */
    @Test
    fun `a counted product ignores stockMeasured`() {
        val it = item(qty = 1.0, measured = 99.0)
        assertNotNull("a stray stockMeasured masked a real oversell", adding(it, line(it, 2.0)))
    }

    // ── Box lines draw whole packs ────────────────────────────────────────

    /**
     * A box line's quantity is BOXES; the shelf loses `qty * boxSize` units. Comparing the
     * box count against a unit on-hand is the arithmetic that lets 2 boxes of 4 look like 2
     * units and empty a shelf of 6 without a word.
     */
    @Test
    fun `a box line compares whole units, not boxes`() {
        val oil = item(qty = 6.0, boxSize = 4)

        assertNull(
            "one box of 4 out of 6 units warned",
            adding(oil, line(oil, 1.0, mode = "box", unitsPerLine = 4))
        )

        val over = adding(oil, line(oil, 2.0, mode = "box", unitsPerLine = 4))
        assertNotNull("2 boxes of 4 against 6 units did not warn", over)
        assertEquals(8.0, over!!.requested, 1e-9)
        assertEquals(2.0, over.shortfall, 1e-9)
    }

    /** Box and loose lines of the same product are separate cart lines and one shelf. */
    @Test
    fun `box and loose lines of the same item add up against one on-hand`() {
        val oil = item(qty = 6.0, boxSize = 4)
        val box = line(oil, 1.0, mode = "box", unitsPerLine = 4)   // 4 units
        val loose = line(oil, 2.0)                                 // 2 units

        assertNull("4 + 2 out of 6 warned", oversellFor(oil, emptyList(), listOf(box, loose)))
        assertEquals(6.0, cartStockUnits(oil, listOf(box, loose)), 1e-9)

        val over = oversellFor(oil, listOf(box), listOf(box, line(oil, 3.0)))
        assertNotNull("4 + 3 out of 6 did not warn", over)
        assertEquals(7.0, over!!.requested, 1e-9)
    }

    /** Other products in the cart are somebody else's shelf. */
    @Test
    fun `another item in the cart does not count against this one`() {
        val a = item(name = "A", qty = 1.0)
        val b = item(name = "B", qty = 50.0)
        assertNull(oversellFor(a, emptyList(), listOf(line(a, 1.0), line(b, 40.0))))
    }

    // ── An item already below zero ────────────────────────────────────────

    /**
     * The state the owner's phone was actually in. Every further sale is an oversell — the
     * shelf is not merely empty, the RECORD is wrong — and it reads differently so the
     * answer can be "count it" rather than "reorder".
     */
    @Test
    fun `an already-negative item warns on the very first unit and says it is already short`() {
        val it = item(qty = -2.0)
        val over = adding(it, line(it, 1.0))
        assertNotNull("selling against a -2 shelf did not warn", over)
        assertEquals(-2.0, over!!.onHand, 1e-9)
        assertEquals(3.0, over.shortfall, 1e-9)
        assertTrue("a shelf recorded at -2 is a data problem, not a busy day", over.alreadyShort)
    }

    @Test
    fun `an already-negative measured item is short too`() {
        val oil = item(type = "measured", measured = -0.5, unit = "kg")
        val over = adding(oil, line(oil, 1.0, mode = "measured", measured = true))
        assertNotNull(over)
        assertTrue(over!!.alreadyShort)
        assertEquals("kg", over.unitLabel)
    }

    /** Zero is NOT already-short: an empty shelf is an ordinary state and must not be
     *  reported as a counting error. It still warns on the sale itself. */
    @Test
    fun `an item at zero warns but is not reported as already short`() {
        val it = item(qty = 0.0)
        val over = adding(it, line(it, 1.0))
        assertNotNull(over)
        assertFalse("an empty shelf was called a data problem", over!!.alreadyShort)
    }

    // ── "Short" is a deficit, not float residue ───────────────────────────

    /**
     * A measured on-hand is a running sum of decimals and does not always land on 0.0 in
     * binary: 2 kg sold as 1.1 then 0.9 leaves -1.11e-16, not zero. Read strictly, measured
     * products would drift into announcing stock takes they do not need — the false-alarm
     * class that already cost this app the owner's trust once, over low stock.
     *
     * (Note that plenty of decimal triples DO cancel exactly — 5 - 2.35 - 2.65 is a clean
     * 0.0 — which is precisely why this cannot be reasoned about and has to be pinned.)
     */
    @Test
    fun `a float residue is an empty shelf, not a deficit`() {
        val residue = 2.0 - 1.1 - 0.9
        assertTrue("precondition: this needs a NEGATIVE residue to be testing anything", residue < 0.0)

        val oil = item(type = "measured", measured = residue, unit = "kg")
        assertFalse("a -4e-16 residue was called a stock-take case", oil.stockIsShort())
        assertFalse(adding(oil, line(oil, 1.0, "measured", measured = true))!!.alreadyShort)
    }

    @Test
    fun `a real deficit is short, on either kind of product`() {
        assertTrue(item(qty = -2.0).stockIsShort())
        assertTrue(item(type = "measured", measured = -0.5, unit = "kg").stockIsShort())
        assertFalse(item(qty = 0.0).stockIsShort())
        assertFalse(item(qty = 3.0).stockIsShort())
    }

    // ── Only on the way up ────────────────────────────────────────────────

    /**
     * Once a cart is over, every later touch of it is still over. Re-asking on a REDUCTION
     * would interrupt the cashier for a state they are in the middle of fixing, and a
     * warning that fires while you correct the thing it warned about is one you learn to
     * dismiss without reading.
     */
    @Test
    fun `reducing an over-quantity line does not warn again`() {
        val it = item(qty = 2.0)
        val current = listOf(line(it, 5.0))
        assertNull(oversellFor(it, current, listOf(line(it, 4.0))))
        assertNull("removing the line entirely warned", oversellFor(it, current, emptyList()))
    }

    @Test
    fun `raising an already-over line warns again`() {
        val it = item(qty = 2.0)
        val over = oversellFor(it, listOf(line(it, 3.0)), listOf(line(it, 4.0)))
        assertNotNull("going from 3 over to 4 over passed silently", over)
        assertEquals(4.0, over!!.requested, 1e-9)
    }

    @Test
    fun `an unchanged cart does not warn`() {
        val it = item(qty = 2.0)
        val current = listOf(line(it, 5.0))
        assertNull(oversellFor(it, current, current))
    }

    // ── The unit basis itself ─────────────────────────────────────────────

    @Test
    fun `stockUnitsDrawn matches the checkout draw-down for each shape of line`() {
        val counted = item(qty = 10.0, boxSize = 4)
        assertEquals(3.0, stockUnitsDrawn(counted, line(counted, 3.0)), 1e-9)
        assertEquals(12.0, stockUnitsDrawn(counted, line(counted, 3.0, "box", 4)), 1e-9)

        // A measured ITEM draws its decimal quantity even when the line forgot to say so…
        val oil = item(type = "measured", measured = 10.0, unit = "kg")
        assertEquals(2.35, stockUnitsDrawn(oil, line(oil, 2.35, "measured")), 1e-9)
        // …and a line rung up BY MEASURE draws a measure, which is what checkout does.
        assertEquals(2.35, stockUnitsDrawn(counted, line(counted, 2.35, "measured", 4, measured = true)), 1e-9)
    }

    @Test
    fun `cartStockUnits ignores lines belonging to other items`() {
        val a = item(name = "A", qty = 1.0)
        val b = item(name = "B", qty = 1.0)
        assertEquals(2.0, cartStockUnits(a, listOf(line(a, 2.0), line(b, 7.0))), 1e-9)
        assertEquals(0.0, cartStockUnits(a, listOf(line(b, 7.0))), 1e-9)
    }
}
