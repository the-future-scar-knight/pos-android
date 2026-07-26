package com.portionspot.pos.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Pins the wholesale cart maths — line pricing, the box→stock-units conversion
 * (a 2026-06 wholesale addition), and the price-mode-aware merge key.
 */
class CartLineTest {
    private val eps = 1e-9

    @Test
    fun retailLine_subtotalAndStockUnits() {
        val line = CartLine(itemId = "i1", name = "Spark Plug", unitPrice = 3.5, taxRate = 0.0, qty = 4.0)
        assertEquals(14.0, line.lineSubtotal, eps)   // 3.5 * 4
        assertEquals(4.0, line.stockUnits, eps)      // unitsPerLine defaults to 1
        assertEquals("i1#retail", line.lineKey)
    }

    @Test
    fun boxLine_consumesBoxSizeTimesQtyOfStock() {
        // 2 boxes of 12 => 24 stock units drawn down; the price is per whole box.
        val line = CartLine(
            itemId = "i2", name = "DELO 1L", unitPrice = 57.0, taxRate = 0.0,
            qty = 2.0, mode = "box", unitsPerLine = 12
        )
        assertEquals(114.0, line.lineSubtotal, eps)  // 2 boxes * 57
        assertEquals(24.0, line.stockUnits, eps)     // 2 * 12
        assertEquals("i2#box", line.lineKey)
    }

    @Test
    fun sameItemDifferentMode_hasDistinctLineKeys() {
        val retail = CartLine("i3", "Oil", 6.0, 0.0, 1.0, mode = "retail")
        val box = CartLine("i3", "Oil", 57.0, 0.0, 1.0, mode = "box", unitsPerLine = 12)
        // Same item at a different price mode must NOT merge into one cart line.
        assertNotEquals(retail.lineKey, box.lineKey)
    }

    @Test
    fun lineTax_appliesTaxRate() {
        val line = CartLine("i4", "Taxed", 100.0, 15.0, 1.0)
        assertEquals(15.0, line.lineTax, eps)
        assertEquals(115.0, line.lineTotal, eps)
    }

    @Test
    fun wholesaleLine_isPerUnit_notBoxed() {
        // Wholesale is a discounted per-unit price: unitsPerLine stays 1, so the
        // stock drawn down equals the quantity sold (unlike a box line).
        val line = CartLine("i5", "Filter", unitPrice = 4.2, taxRate = 0.0, qty = 6.0, mode = "wholesale")
        assertEquals(25.2, line.lineSubtotal, eps)   // 4.2 * 6
        assertEquals(6.0, line.stockUnits, eps)
        assertEquals("i5#wholesale", line.lineKey)
    }

    @Test
    fun fractionalQty_weighedGoods_subtotalAndStock() {
        // 1.5 units (e.g. 1.5 L decanted) at 8.00 => 12.00, and 1.5 stock units out.
        val line = CartLine("i6", "Bulk Oil", unitPrice = 8.0, taxRate = 0.0, qty = 1.5)
        assertEquals(12.0, line.lineSubtotal, eps)
        assertEquals(1.5, line.stockUnits, eps)
    }

    @Test
    fun boxLine_taxIsChargedOnBoxSubtotal() {
        // 3 boxes @ 40 = 120 subtotal; 15% VAT on the box price => 18 tax, 138 total.
        val line = CartLine("i7", "Coolant", unitPrice = 40.0, taxRate = 15.0, qty = 3.0, mode = "box", unitsPerLine = 6)
        assertEquals(120.0, line.lineSubtotal, eps)
        assertEquals(18.0, line.lineTax, eps)
        assertEquals(138.0, line.lineTotal, eps)
        assertEquals(18.0, line.stockUnits, eps)     // 3 boxes * 6 units
    }

    @Test
    fun zeroQty_lineIsAllZero() {
        val line = CartLine("i8", "Nothing", unitPrice = 99.0, taxRate = 15.0, qty = 0.0)
        assertEquals(0.0, line.lineSubtotal, eps)
        assertEquals(0.0, line.lineTax, eps)
        assertEquals(0.0, line.lineTotal, eps)
        assertEquals(0.0, line.stockUnits, eps)
    }

    @Test
    fun perItemDiscount_reducesNetAndTax_notGross() {
        // 4 @ 10 = 40 gross; $6 off the line => 34 net. lineSubtotal stays gross (it
        // feeds the sale subtotal); the discount comes off net + is taxed on net.
        val line = CartLine("d1", "Wiper", unitPrice = 10.0, taxRate = 15.0, qty = 4.0, lineDiscount = 6.0)
        assertEquals(40.0, line.lineGross, eps)
        assertEquals(40.0, line.lineSubtotal, eps)         // gross, unchanged
        assertEquals(6.0, line.lineDiscountApplied, eps)
        assertEquals(34.0, line.lineNet, eps)              // 40 - 6
        assertEquals(5.1, line.lineTax, eps)               // 15% of 34
        assertEquals(39.1, line.lineTotal, eps)            // 34 + 5.1
    }

    @Test
    fun perItemDiscount_clampedToLineValue_neverNegative() {
        // A $50 discount on a $12 line can't make the line negative — it clamps to 12.
        val line = CartLine("d2", "Fuse", unitPrice = 6.0, taxRate = 0.0, qty = 2.0, lineDiscount = 50.0)
        assertEquals(12.0, line.lineDiscountApplied, eps)
        assertEquals(0.0, line.lineNet, eps)
        assertEquals(0.0, line.lineTotal, eps)
    }

    @Test
    fun noDiscount_behavesExactlyAsBefore() {
        // Backward-compatibility: a zero-discount line's totals are unchanged.
        val line = CartLine("d3", "Belt", unitPrice = 25.0, taxRate = 15.0, qty = 2.0)
        assertEquals(50.0, line.lineSubtotal, eps)
        assertEquals(50.0, line.lineNet, eps)
        assertEquals(57.5, line.lineTotal, eps)
    }

    @Test
    fun threePriceModes_allHaveDistinctLineKeys() {
        val retail = CartLine("i9", "Oil", 6.0, 0.0, 1.0, mode = "retail")
        val wholesale = CartLine("i9", "Oil", 5.0, 0.0, 1.0, mode = "wholesale")
        val box = CartLine("i9", "Oil", 57.0, 0.0, 1.0, mode = "box", unitsPerLine = 12)
        val keys = setOf(retail.lineKey, wholesale.lineKey, box.lineKey)
        assertEquals(3, keys.size)   // no two modes collapse into one cart line
    }
}
