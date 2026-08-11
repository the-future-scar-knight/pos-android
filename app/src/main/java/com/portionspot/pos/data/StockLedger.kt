package com.portionspot.pos.data

/**
 * Turning the stock ledger into an on-hand.
 *
 * `stock_movements` is a log of CHANGES. Nothing writes an opening entry: an item pulled
 * from the shop with 20 on the shelf, or typed into the catalogue with 20, arrives with no
 * movement saying so. `SUM(delta)` is therefore *how much this item has moved*, and reading
 * it as an on-hand is what emptied a real shelf — `ADVAN Front Right Light` had 2, sold 1,
 * held exactly one movement of -1, and came back from the sync pass as out of stock.
 *
 * The rule instead: **the shop's own figure, plus every movement made after the instant
 * that figure was true.** [Item.stockBaseQty] is the figure and [Item.stockBaseAt] the
 * instant, both taken straight off the cloud row. Deltas are independent facts, so this
 * gives the same answer however many tills contributed and in whatever order their rows
 * arrive — which is the property the whole ledger exists for.
 *
 * `balanceAfter` is deliberately not used, even though it is an absolute: two tills selling
 * the same product while offline each snapshot a balance from the stock THEY could see, so
 * the later-arriving snapshot silently discards the other till's sale.
 */

/**
 * On-hand for [item] given the NET delta of its movements since its baseline, or null when
 * the item has no baseline and must be left exactly as the till has it.
 *
 * This is the arithmetic the sync pass runs; it takes a pre-summed delta because the engine
 * sums in SQL (`StockMovementDao.deltaSinceBaselineByItem`) across the whole catalogue in
 * one query. [stockOnHandFromLedger] applies the same filter in Kotlin for callers holding
 * the movements themselves.
 */
fun stockOnHandFromDelta(item: Item, deltaSinceBaseline: Double): Double? {
    // No baseline => never reconciled against the shop, so the ledger says nothing
    // absolute about this item. Null means "do not touch". Skipping is the safe
    // direction: a late update from another till beats erasing stock that is on the shelf.
    if (item.stockBaseAt <= 0L) return null
    return item.stockBaseQty + deltaSinceBaseline
}

/**
 * On-hand for [item] computed from [movements] directly. Only rows for this item, not
 * tombstoned, and stamped STRICTLY after the baseline count — a movement stamped at the
 * same instant as the shop's figure is what produced it, and counting it applies it twice.
 */
fun stockOnHandFromLedger(item: Item, movements: List<StockMovement>): Double? {
    if (item.stockBaseAt <= 0L) return null
    val delta = movements
        .filter { it.itemId == item.id && !it.deleted && it.createdAt > item.stockBaseAt }
        .sumOf { it.delta }
    return stockOnHandFromDelta(item, delta)
}
