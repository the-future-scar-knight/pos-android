package com.portionspot.pos.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Restocking from the till has to reach the LEDGER, not just the cached figure.
 *
 * `items.stockQty` is a cache the sync pass rebuilds as `stockBaseQty + Σ deltas since
 * the baseline`. Typing a new figure into the Inventory form and writing only that row
 * therefore restocked nothing: the number showed until the next pull and was then
 * computed straight back off, because no movement ever said the stock had arrived. And
 * the catalogue is pull-only, so it never reached another till either — the ledger is the
 * only channel a till has for saying that stock moved.
 *
 * These pin the rule that decides what entry an edit owes.
 */
class StockEditTest {

    private fun item(
        qty: Double = 0.0,
        measured: Double = 0.0,
        track: Boolean = true,
        type: String = "piece"
    ) = Item(
        id = "i1", businessId = "biz", name = "ATF Type IV 1L",
        stockQty = qty, stockMeasured = measured, trackStock = track, productType = type
    )

    @Test
    fun addingStockToAnExistingProductLogsTheDifference() {
        // 21 on the shelf, a delivery of 9 typed in as the new figure.
        val edit = stockEditFor(item(qty = 21.0), item(qty = 30.0))!!
        assertEquals(9.0, edit.delta, 1e-9)
        assertEquals(30.0, edit.balanceAfter, 1e-9)
        assertEquals("adjust", edit.type)
    }

    @Test
    fun theDeltaLandsBackOnTheFigureThatWasTyped() {
        // The whole point: the recompute applies baseline + deltas, so an edit is only
        // correct if replaying it from the PREVIOUS figure reproduces the new one.
        val before = item(qty = 21.0)
        val after = item(qty = 30.0)
        val edit = stockEditFor(before, after)!!
        assertEquals(after.stockQty, before.stockQty + edit.delta, 1e-9)
    }

    @Test
    fun correctingAnOvercountLogsANegativeDelta() {
        val edit = stockEditFor(item(qty = 12.0), item(qty = 5.0))!!
        assertEquals(-7.0, edit.delta, 1e-9)
        assertEquals(5.0, edit.balanceAfter, 1e-9)
    }

    @Test
    fun aBrandNewProductLogsItsOpeningCount() {
        // No baseline exists for a till-created product, so the recompute leaves it
        // alone; the entry is there to say where the stock came from.
        val edit = stockEditFor(null, item(qty = 20.0))!!
        assertEquals(20.0, edit.delta, 1e-9)
        assertEquals(20.0, edit.balanceAfter, 1e-9)
        assertEquals("restock", edit.type)
    }

    @Test
    fun renamingOrRepricingLogsNothing() {
        // An "adjust" of zero on every unrelated edit would bury the real movements.
        assertNull(stockEditFor(item(qty = 21.0), item(qty = 21.0)))
    }

    @Test
    fun aNewProductWithNoStockLogsNothing() {
        assertNull(stockEditFor(null, item(qty = 0.0)))
    }

    @Test
    fun anUntrackedItemHasNoOnHandToMove() {
        assertNull(stockEditFor(item(qty = 0.0, track = false), item(qty = 99.0, track = false)))
    }

    @Test
    fun aMeasuredProductIsMeasuredOnItsOwnField() {
        // Fractional stock lives in stockMeasured; reading stockQty here would invent a
        // movement for a change that never happened, and miss the one that did.
        val before = item(measured = 4.5, type = "measured")
        val after = item(measured = 7.25, type = "measured")
        val edit = stockEditFor(before, after)!!
        assertEquals(2.75, edit.delta, 1e-9)
        assertEquals(7.25, edit.balanceAfter, 1e-9)
    }

    @Test
    fun aMeasuredProductIgnoresTheWholeUnitColumn() {
        val before = item(qty = 3.0, measured = 4.5, type = "measured")
        val after = item(qty = 99.0, measured = 4.5, type = "measured")
        assertNull(stockEditFor(before, after))
    }

    @Test
    fun subCentDriftIsNotAMovement() {
        assertNull(stockEditFor(item(qty = 10.0), item(qty = 10.004)))
    }

    @Test
    fun resettingStockLogsTheWholeOnHandAway() {
        val reset = stockResetFor(item(qty = 21.0))!!
        assertEquals(-21.0, reset.delta, 1e-9)
        assertEquals(0.0, reset.balanceAfter, 1e-9)
        assertEquals("reset", reset.type)
    }

    @Test
    fun resettingAMeasuredProductEmptiesItsOwnField() {
        val reset = stockResetFor(item(measured = 4.5, type = "measured"))!!
        assertEquals(-4.5, reset.delta, 1e-9)
    }

    @Test
    fun resettingSkipsWhatIsAlreadyEmptyOrUntracked() {
        // A zero-delta row for every untouched product would bury the real entries.
        assertNull(stockResetFor(item(qty = 0.0)))
        assertNull(stockResetFor(item(qty = 30.0, track = false)))
    }

    @Test
    fun aResetSurvivesTheRecompute() {
        // The bug this fixes: the blanket UPDATE zeroed the cached figure and the next
        // pull recomputed baseline + deltas and restored all 24.
        val baseAt = 1_754_745_588_000L
        val onShelf = Item(
            id = "i1", businessId = "biz", name = "ATF Type IV 1L",
            stockQty = 24.0, trackStock = true, stockBaseQty = 24.0, stockBaseAt = baseAt
        )
        val reset = stockResetFor(onShelf)!!
        val move = StockMovement(
            businessId = "biz", itemId = "i1", type = reset.type,
            delta = reset.delta, balanceAfter = reset.balanceAfter, createdAt = baseAt + 1000L
        )
        assertEquals(0.0, stockOnHandFromLedger(onShelf, listOf(move))!!, 1e-9)
    }

    @Test
    fun aStockEditsMovementMustOutrankTheRowItCameWith() {
        // The bug this pins, found on the shop's own data. `saveItem` stamps the item row
        // and its movement from the same `stamp`, and the row's client_updated_at IS the
        // baseline instant — so a movement stamped ON it is read as the change that
        // PRODUCED the figure and is never replayed. The originating device never
        // noticed (it keeps its existing baseline, because the catalogue push does not
        // send stock_qty so the cloud figure never changes), but every OTHER device
        // adopted the row as a fresh baseline and dropped the movement.
        //
        // `Hamburger` was created on the till with 3, arrived in the cloud with
        // stock_qty 0, and its `restock +3` was stamped to the identical millisecond.
        // Every other phone would have read it as out of stock.
        val rowStamp = 1_754_745_588_000L
        val newProduct = Item(
            id = "i1", businessId = "biz", name = "Hamburger",
            stockQty = 3.0, trackStock = true,
            // What a second device adopts from the cloud: the figure is 0, because the
            // catalogue push never carries stock, as of the row's own instant.
            stockBaseQty = 0.0, stockBaseAt = rowStamp,
        )
        val opening = StockMovement(
            businessId = "biz", itemId = "i1", type = "restock",
            delta = 3.0, balanceAfter = 3.0, createdAt = rowStamp + 1,
        )
        assertEquals(3.0, stockOnHandFromLedger(newProduct, listOf(opening))!!, 1e-9)

        // Stamped ON the row instead, it is silently discarded — the old behaviour.
        val onTheBaseline = opening.copy(createdAt = rowStamp)
        assertEquals(0.0, stockOnHandFromLedger(newProduct, listOf(onTheBaseline))!!, 1e-9)
    }

    @Test
    fun anEditThenTheRecomputeAgree() {
        // End to end on the arithmetic that matters: an item with a cloud baseline of 24,
        // one sale of -3, then a delivery of 9 typed in. The recompute must land on 30.
        val baseAt = 1_754_745_588_000L
        val soldAt = baseAt + 60_000L
        val editedAt = baseAt + 120_000L
        val onShelf = Item(
            id = "i1", businessId = "biz", name = "ATF Type IV 1L",
            stockQty = 21.0, trackStock = true, stockBaseQty = 24.0, stockBaseAt = baseAt
        )
        val sale = StockMovement(
            businessId = "biz", itemId = "i1", type = "sale",
            delta = -3.0, balanceAfter = 21.0, createdAt = soldAt
        )
        val edit = stockEditFor(onShelf, onShelf.copy(stockQty = 30.0))!!
        val restock = StockMovement(
            businessId = "biz", itemId = "i1", type = edit.type,
            delta = edit.delta, balanceAfter = edit.balanceAfter, createdAt = editedAt
        )
        assertEquals(30.0, stockOnHandFromLedger(onShelf, listOf(sale, restock))!!, 1e-9)
    }
}
