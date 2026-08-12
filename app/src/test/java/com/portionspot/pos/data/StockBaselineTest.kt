package com.portionspot.pos.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the stock arithmetic that emptied a real shelf.
 *
 * `ADVAN Front Right Light` had 2 in the shop and 2 on the till. A cashier sold ONE. The
 * device held exactly one `stock_movements` row — the -1 — because nothing has ever
 * written an opening entry for an item, and the sync pass read `SUM(delta)` as the
 * on-hand. The item came back as -1 and the till reported it out of stock with two of
 * them physically on the shelf.
 *
 * The rule that replaces it: **on-hand = the shop's figure, plus every movement made
 * after the instant that figure was true.** The sync pass sums that delta in SQL, across
 * the whole catalogue in one query, then hands it to the same `stockOnHandFromDelta` these
 * tests exercise — so the baseline arithmetic is shared, while the "which movements count"
 * filter is asserted here against real movement rows.
 */
class StockBaselineTest {

    // The moment the shop's figure was true (cloud `items.updated_at`).
    private val baseAt = 1_754_745_588_000L   // 2026-08-09 13:39
    private val afterBase = baseAt + 172_800_000L  // two days later — the sale
    private val beforeBase = baseAt - 86_400_000L  // the day before

    private fun item(qty: Double, baseQty: Double, baseAt: Long) = Item(
        id = "i1", businessId = "biz", name = "ADVAN Front Right Light",
        stockQty = qty, trackStock = true, stockBaseQty = baseQty, stockBaseAt = baseAt,
    )

    private fun move(delta: Double, at: Long) = StockMovement(
        businessId = "biz", itemId = "i1", type = "sale", delta = delta,
        balanceAfter = 0.0, createdAt = at,
    )

    @Test
    fun theBugItself_sellingOneOfTwoLeavesOne() {
        // The exact case off the shop floor. The old rule returned -1 here.
        val onHand = stockOnHandFromLedger(
            item(qty = 1.0, baseQty = 2.0, baseAt = baseAt),
            listOf(move(-1.0, afterBase)),
        )
        assertEquals(1.0, onHand!!, 1e-9)
    }

    @Test
    fun noMovementsSinceTheBaselineSettlesBackToTheShopsFigure() {
        // Not "skip because nothing moved" — an item whose only movements PREDATE its
        // baseline has to land on the shop's number, not keep a stale local one.
        val onHand = stockOnHandFromLedger(
            item(qty = 99.0, baseQty = 20.0, baseAt = baseAt),
            listOf(move(-5.0, beforeBase)),
        )
        assertEquals(20.0, onHand!!, 1e-9)
    }

    @Test
    fun movementsBeforeTheBaselineAreNotDoubleCounted() {
        // The shop's figure ALREADY accounts for anything that happened before it was
        // taken. Replaying those would subtract the same sale twice.
        val onHand = stockOnHandFromLedger(
            item(qty = 0.0, baseQty = 20.0, baseAt = baseAt),
            listOf(move(-5.0, beforeBase), move(-3.0, afterBase)),
        )
        assertEquals(17.0, onHand!!, 1e-9)
    }

    @Test
    fun everyTillsMovementsAddUpWhateverOrderTheyArriveIn() {
        // Two tills, both selling the same product while offline from each other. Deltas
        // are independent facts; the answer cannot depend on which pull landed first.
        val moves = listOf(move(-1.0, afterBase), move(-2.0, afterBase + 5), move(4.0, afterBase + 9))
        val forward = stockOnHandFromLedger(item(0.0, 10.0, baseAt), moves)!!
        val reversed = stockOnHandFromLedger(item(0.0, 10.0, baseAt), moves.reversed())!!
        assertEquals(11.0, forward, 1e-9)
        assertEquals(forward, reversed, 1e-9)
    }

    @Test
    fun anItemWithNoBaselineIsLeftAlone() {
        // Never reconciled against the shop, so the ledger says nothing absolute about it.
        // Null means "do not touch" — the till's own bookkeeping stands. Erasing stock
        // that is physically on the shelf is the worse failure than a late update.
        assertNull(stockOnHandFromLedger(item(qty = 7.0, baseQty = 0.0, baseAt = 0L), listOf(move(-1.0, afterBase))))
    }

    @Test
    fun tombstonedMovementsDoNotCount() {
        val onHand = stockOnHandFromLedger(
            item(0.0, 10.0, baseAt),
            listOf(move(-4.0, afterBase).copy(deleted = true), move(-1.0, afterBase)),
        )
        assertEquals(9.0, onHand!!, 1e-9)
    }

    @Test
    fun theCountsOwnMovementIsAlreadyInTheFigure() {
        // A count writes the figure and the movement recording it at the same instant.
        // That one row is inside the baseline; counting it applies the count twice.
        val counted = StockMovement(
            businessId = "biz", itemId = "i1", type = "adjust", delta = 3.0,
            balanceAfter = 10.0, createdAt = baseAt,
        )
        val onHand = stockOnHandFromLedger(item(0.0, 10.0, baseAt), listOf(counted))
        assertEquals(10.0, onHand!!, 1e-9)
    }

    @Test
    fun anotherTillsSaleInTheSameMillisecondStillCounts() {
        // The divergence that made two clients read the same rows differently: till A takes
        // a count of 25, till B rings up two in the same millisecond. Excluding the whole
        // tie drops B's sale and the shelf reads 25 with 23 on it. Only the movement that
        // PRODUCED the figure is excluded — a sale never is, however close its stamp lands.
        val count = StockMovement(
            businessId = "biz", itemId = "i1", type = "adjust", delta = 5.0,
            balanceAfter = 25.0, createdAt = baseAt,
        )
        val onHand = stockOnHandFromLedger(
            item(0.0, 25.0, baseAt),
            listOf(count, move(-2.0, baseAt)),
        )
        assertEquals(23.0, onHand!!, 1e-9)
    }

    @Test
    fun anAbsoluteMovementThatDidNotProduceTheFigureStillCounts() {
        // Same instant, same absolute type, but its `balanceAfter` is not the counted
        // figure — so it is another till's stock-take, not the one this baseline came from.
        val otherTill = StockMovement(
            businessId = "biz", itemId = "i1", type = "restock", delta = 4.0,
            balanceAfter = 99.0, createdAt = baseAt,
        )
        val onHand = stockOnHandFromLedger(item(0.0, 10.0, baseAt), listOf(otherTill))
        assertEquals(14.0, onHand!!, 1e-9)
    }

    @Test
    fun aResetCountsBecauseTheBaselineIsNotWhatItLeftBehind() {
        // Reset is local: it writes `-on-hand` but never moves `stockBaseAt`, which only a
        // pull can set. So its movement lands after the baseline and must be applied, or
        // the Danger-zone reset undoes itself one sync later — the failure it exists to fix.
        val reset = StockMovement(
            businessId = "biz", itemId = "i1", type = "reset", delta = -10.0,
            balanceAfter = 0.0, createdAt = afterBase,
        )
        val onHand = stockOnHandFromLedger(item(10.0, 10.0, baseAt), listOf(reset))
        assertEquals(0.0, onHand!!, 1e-9)
    }
}
