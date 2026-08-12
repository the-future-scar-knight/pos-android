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
 * tombstoned, and only those [countsTowardBaseline] admits.
 */
fun stockOnHandFromLedger(item: Item, movements: List<StockMovement>): Double? {
    if (item.stockBaseAt <= 0L) return null
    val delta = movements
        .filter { it.itemId == item.id && !it.deleted && countsTowardBaseline(item, it) }
        .sumOf { it.delta }
    return stockOnHandFromDelta(item, delta)
}

/**
 * Movement types that assert an ABSOLUTE figure rather than a change. Only these can be
 * the movement a stock count wrote alongside the shop's figure; a `sale` or a `return`
 * never is, however close its stamp lands.
 */
private val ABSOLUTE_TYPES = setOf("adjust", "restock", "reset")

/**
 * True when [m] is the movement that PRODUCED [item]'s baseline, rather than one that
 * merely shares its instant.
 *
 * A count writes two things at once — the shop's figure and the movement that records it —
 * so that one movement is already inside the baseline and counting it applies it twice.
 * Every OTHER movement at that instant belongs to a different till doing its own work.
 *
 * Identified by what the row IS, not by when it happened: an absolute type whose
 * `balanceAfter` is the counted figure. That is the web POS's rule, and it is the reason
 * both clients now read the same shelf.
 */
fun producedBaseline(item: Item, m: StockMovement): Boolean =
    m.createdAt == item.stockBaseAt &&
        m.type in ABSOLUTE_TYPES &&
        kotlin.math.abs(m.balanceAfter - item.stockBaseQty) < QTY_EPSILON

/**
 * Whether [m] is movement the baseline has NOT already accounted for.
 *
 * Anything stamped before the count is inside the figure. Anything after it is new. At the
 * exact instant of the count — which is a real collision, not a theoretical one, because
 * two tills in a shop share a wall clock to the millisecond — only the count's own
 * movement is excluded.
 *
 * ★ The SQL twin of this lives in `StockMovementDao.deltaSinceBaselineByItem`. The sync
 * pass sums across the whole catalogue in one query and cannot call back into Kotlin, so
 * the rule is written twice on purpose. Change both.
 */
fun countsTowardBaseline(item: Item, m: StockMovement): Boolean =
    m.createdAt >= item.stockBaseAt && !producedBaseline(item, m)

/** The ledger entry a catalogue edit owes, from [stockEditFor]. */
data class StockEdit(
    val delta: Double,
    val balanceAfter: Double,
    val type: String,
    val note: String
)

/** Half-a-cent, matching the repository's money and quantity comparisons. */
private const val QTY_EPSILON = 0.005

/**
 * The on-hand [item] actually keeps its stock in.
 *
 * A `measure` product's quantity lives in [Item.stockMeasured] and everything else in
 * [Item.stockQty] — two fields on this side, even though the cloud keeps both in
 * `stock_qty`. Reading the wrong one is how a change to a measured product goes
 * unrecorded while an untouched whole-unit count appears to have moved.
 */
fun stockOnHandOf(item: Item): Double =
    if (item.productType == "measured") item.stockMeasured else item.stockQty

/**
 * The movement a catalogue save owes, or null when it owes none.
 *
 * Editing an item's stock figure without logging this is not a restock. `items.stockQty`
 * is a CACHE that the sync pass rebuilds from the ledger, so a figure with no movement
 * behind it survives exactly until the next pull and is then computed away — and because
 * the catalogue is pull-only, it never reaches another till either. The ledger is the
 * only channel a till has for saying that stock moved.
 *
 * [prior] is the row as the device currently holds it, which is what the recompute last
 * settled on; measuring the delta against it means applying the movement lands back on
 * exactly the figure that was typed. Null [prior] is a brand-new product, whose whole
 * opening count is the entry.
 *
 * ★ CHANGING A PRODUCT'S TYPE is deliberately not modelled. Switching between `measure`
 * and the whole-unit types moves the quantity from one column to the other, which reads
 * here as the old column emptying or the new one filling — a movement for stock that
 * never left the shelf. It is a rare admin action on a product that should not be selling
 * yet, and every rule that would paper over it (summing both columns, comparing across
 * types) risks getting the ordinary daily edit wrong to rescue the rare one. Left as a
 * known limitation rather than guessed at.
 */
fun stockEditFor(prior: Item?, next: Item): StockEdit? {
    // No on-hand to speak of, so no entry to make.
    if (!next.trackStock) return null
    val after = stockOnHandOf(next)
    val before = prior?.let { stockOnHandOf(it) } ?: 0.0
    val delta = after - before
    // A figure that did not move needs no entry; an "adjust" of zero is noise in the
    // item's history and a row for every unrelated rename or reprice.
    if (delta > -QTY_EPSILON && delta < QTY_EPSILON) return null
    return StockEdit(
        delta = delta,
        balanceAfter = after,
        // Both are in the shared vocabulary (`sale · restock · adjust · return · reset`).
        // Goods entering the system with a new product read as a restock; a change to an
        // existing one is a stock-take correction.
        type = if (prior == null) "restock" else "adjust",
        note = if (prior == null) "Opening stock" else "Stock edited in Inventory"
    )
}

/**
 * The entry the Danger-zone "reset all stock" owes for [item], or null when it owes none.
 *
 * Zeroing `items.stockQty` in a single UPDATE looked like it worked and did not: the row
 * is a cache, so the next pull recomputed `stockBaseQty + Σ deltas` and put every figure
 * straight back. The owner reaches for this button precisely when the till is holding
 * numbers they want gone — a reset that quietly undoes itself one sync later is the worst
 * possible answer, because the shop has already moved on believing the shelves are clear.
 *
 * A `reset` movement of `-on-hand` is what actually empties it, on this till and on every
 * other one. `reset` is in the shared vocabulary alongside sale/restock/adjust/return.
 */
fun stockResetFor(item: Item): StockEdit? {
    if (!item.trackStock) return null
    val onHand = stockOnHandOf(item)
    // Already empty. A zero-delta row per untouched product would bury the real ones.
    if (onHand > -QTY_EPSILON && onHand < QTY_EPSILON) return null
    return StockEdit(
        delta = -onHand,
        balanceAfter = 0.0,
        type = "reset",
        note = "Stock reset"
    )
}
