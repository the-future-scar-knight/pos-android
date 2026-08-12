package com.portionspot.pos.sync

import com.portionspot.pos.data.SaleLine
import com.portionspot.pos.sync.wire.SaleItemDto
import com.portionspot.pos.sync.wire.toPush
import com.portionspot.pos.sync.wire.toSaleLine
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `line_total` is one column with two meanings, and the seam between them is where a
 * discount turns into profit that never existed.
 *
 * LOCAL: gross — `unitPrice × qty`. The receipt prints the line at its full price and
 * shows the discount and the markup as their own lines beneath it.
 *
 * WIRE: net — `unitPrice × qty − lineDiscount + lineMarkup`. That is the web POS's `ep()`
 * and the meaning every row already in the cloud carries. It is not a preference: the
 * cloud's `sale_items.line_profit` is GENERATED as
 * `line_total − (unit_cost × qty × units_per_line)`, so a gross figure on the wire hands
 * the owner a profit inflated by exactly the discount the cashier gave away.
 *
 * These tests pin the conversion in both directions. The round trip has to be exact,
 * because `line_discount` and `line_markup` ride in the same row and nothing else can
 * reconstruct the difference once it is lost.
 */
class SaleLineWireTotalTest {
    private val eps = 1e-9

    private fun line(
        qty: Double = 1.0,
        unitPrice: Double = 200.0,
        lineDiscount: Double = 0.0,
        lineMarkup: Double = 0.0,
        unitCost: Double? = 10.0,
        unitsPerLine: Int = 12,
    ) = SaleLine(
        id = "l1", saleId = "s1", businessId = "biz", itemId = "i1", name = "Box of plugs",
        qty = qty, unitPrice = unitPrice, unitCost = unitCost,
        lineDiscount = lineDiscount, lineMarkup = lineMarkup, lineTax = 0.0,
        // What checkout actually persists: the GROSS goods value.
        lineTotal = unitPrice * qty,
        mode = "box", unitsPerLine = unitsPerLine, updatedAt = 1_754_745_588_000L,
    )

    @Test
    fun aDiscountedLineGoesUpNetSoTheCloudsGeneratedProfitIsRight() {
        // The worked case: one box at 200, twelve units at cost 10, 20 off.
        // line_cost is generated as 10 × 1 × 12 = 120.
        // Pushed gross (200) the cloud reads a profit of 80. The line made 60.
        val push = line(lineDiscount = 20.0).toPush()
        assertEquals(180.0, push.lineTotal, eps)
        assertEquals(60.0, push.lineTotal - (10.0 * 1.0 * 12.0), eps)
    }

    @Test
    fun aMarkedUpLineGoesUpWithTheMarkupIncluded() {
        // The cashier's markup is money the shop actually took, so it belongs in the
        // figure the cloud subtracts cost from — the mirror of the discount case.
        val push = line(lineMarkup = 15.0).toPush()
        assertEquals(215.0, push.lineTotal, eps)
    }

    @Test
    fun anUndiscountedLineIsUnchangedByTheConversion() {
        // Nothing to convert, and nothing may drift: this is every ordinary sale.
        assertEquals(200.0, line().toPush().lineTotal, eps)
    }

    @Test
    fun aWebSaleComesBackDownAsThisAppsGrossFigure() {
        // A sale rung on the web arrives net. Stored as-is it would print its discount
        // twice — folded into the line, then again on the discount line below it.
        val dto = SaleItemDto(
            id = "l1", saleId = "s1", itemId = "i1", name = "Box of plugs",
            qty = "1", unitPrice = "200.00", unitCost = "10.00",
            lineDiscount = "20.00", lineMarkup = "0.00", lineTax = "0.00",
            lineTotal = "180.00", mode = "box", unitsPerLine = "12",
            updatedAt = "2026-08-12T06:00:00Z",
        )
        assertEquals(200.0, dto.toSaleLine("biz", null).lineTotal, eps)
    }

    @Test
    fun theRoundTripIsExactWithBothAdjustmentsOnOneLine() {
        // Discount AND markup on the same line, which is the case that catches a
        // conversion that only ever got tested one adjustment at a time.
        val local = line(qty = 3.0, unitPrice = 45.0, lineDiscount = 12.5, lineMarkup = 4.0)
        val push = local.toPush()
        assertEquals(126.5, push.lineTotal, eps)   // 135 − 12.5 + 4

        val back = SaleItemDto(
            id = local.id, saleId = local.saleId, itemId = local.itemId, name = local.name,
            qty = push.qty.toString(), unitPrice = push.unitPrice.toString(),
            unitCost = push.unitCost?.toString(),
            lineDiscount = push.lineDiscount.toString(),
            lineMarkup = push.lineMarkup.toString(),
            lineTax = push.lineTax.toString(),
            lineTotal = push.lineTotal.toString(),
            mode = push.mode, unitsPerLine = push.unitsPerLine.toString(),
            updatedAt = "2026-08-12T06:00:00Z",
        ).toSaleLine("biz", null)

        assertEquals(local.lineTotal, back.lineTotal, eps)
        assertEquals(local.lineDiscount, back.lineDiscount, eps)
        assertEquals(local.lineMarkup, back.lineMarkup, eps)
    }
}
