package com.portionspot.pos.data

/**
 * The money math for one sale, pulled out of [PosRepository.checkout] into a
 * pure function so it can be unit-tested without Room or a device.
 *
 *  - [subtotal]    pre-tax value of the goods (Σ line subtotals).
 *  - [discount]    whole-sale discount applied to the subtotal (clamped below).
 *  - [markup]      extra charged on top (Σ per-item markups); added after discount.
 *  - [taxableBase] subtotal − discount + markup; the base VAT is charged on.
 *  - [taxTotal]    VAT collected (0 when VAT is off).
 *  - [total]       tax-inclusive amount the customer pays.
 */
data class SaleTotals(
    val subtotal: Double,
    val discount: Double,
    val markup: Double,
    val taxableBase: Double,
    val taxTotal: Double,
    val total: Double
)

/**
 * Compute the totals for a sale. Behaviour pinned by SaleMathTest:
 *  - [discount] is clamped to `[0, subtotal]` — you can't discount more than the
 *    goods are worth, and a negative discount is treated as zero.
 *  - [markup] is added AFTER the discount (floored at 0); there is no cap, so it can
 *    lift the taxable base above the gross subtotal.
 *  - When [vatEnabled], VAT is [vatPercent]% of the **discounted + marked-up** base
 *    (so both a discount and a markup move the tax) — never of the gross subtotal.
 *  - [total] is tax-inclusive; with VAT off it equals the discounted + marked-up base.
 */
fun computeSaleTotals(
    subtotal: Double,
    discount: Double,
    vatEnabled: Boolean,
    vatPercent: Double,
    markup: Double = 0.0
): SaleTotals {
    // coerceAtLeast(0.0) guards the degenerate empty/negative-subtotal case so
    // coerceIn never sees max < min (which would throw).
    val saleDiscount = discount.coerceIn(0.0, subtotal.coerceAtLeast(0.0))
    val saleMarkup = markup.coerceAtLeast(0.0)
    val taxableBase = subtotal - saleDiscount + saleMarkup
    val taxTotal = if (vatEnabled) taxableBase * vatPercent / 100.0 else 0.0
    return SaleTotals(
        subtotal = subtotal,
        discount = saleDiscount,
        markup = saleMarkup,
        taxableBase = taxableBase,
        taxTotal = taxTotal,
        total = taxableBase + taxTotal
    )
}

/**
 * What a sale actually made, on the lines that HAVE a cost recorded.
 *
 *  - [costTotal]      cost of goods for the costed lines, at the price frozen at sale time.
 *  - [costedRevenue]  those same lines' take AFTER their share of the whole-sale
 *                     discount — the honest margin denominator.
 *  - [discountShare]  the costed lines' pro-rata slice of the whole-sale discount.
 *  - [profit]         costedRevenue − costTotal. Signed: a line sold below cost is a
 *                     real loss and says so.
 */
data class SaleMargin(
    val costTotal: Double,
    val costedRevenue: Double,
    val discountShare: Double,
    val profit: Double
)

/**
 * The single source of truth for margin — one function behind the dashboard's gross
 * profit, the margin denominator and the cash-basis recognition in [CashBasis], because
 * when those disagree the shop has three profit figures and no way to tell which is right.
 *
 * Everything is VAT-EXCLUSIVE. VAT is collected for ZIMRA, never earned.
 *
 * WHAT THE INPUTS MEAN
 *  - [allLinesNet]     Σ over every live line of `unitPrice×qty − lineDiscount + lineMarkup`.
 *                      The goods value the customer was billed for, before any whole-sale
 *                      discount. NOT [SaleEntity.subtotal], which is gross of both.
 *  - [costedLinesNet]  the same measure over only the lines carrying a cost.
 *  - [costedLinesCost] Σ over those lines of `unitCost × unitsPerLine × qty`. The
 *                      multiplier lifts a per-STOCK-UNIT cost to the LINE-UNIT price a
 *                      box line is sold at, exactly once.
 *  - [saleNetTake]     `total − taxTotal` — the VAT-exclusive money actually billed. The
 *                      whole-sale discount is recovered as `allLinesNet − saleNetTake`
 *                      rather than read off `discountTotal`, because `discountTotal` also
 *                      contains the per-item discounts that are ALREADY off the lines;
 *                      subtracting it whole would count them twice.
 *
 * THREE RULES, EACH OF WHICH THIS APP GOT WRONG BEFORE
 *
 *  - Only COSTED lines count. A line with no cost price is not zero-cost, it is
 *    unknown, and booking it as pure profit flatters the figure.
 *
 *  - Line-level discounts and markups are part of the take. The old report SQL summed
 *    a bare `unitPrice × qty`, so a discount the cashier gave cost the shop nothing in
 *    the figures and a markup earned it nothing.
 *
 *  - The whole-sale discount is shared PRO-RATA with the costed lines. Taking all of it
 *    off understates margin whenever part of a sale is uncosted; taking none of it off —
 *    which is what the old SQL did — overstates it on every discounted sale.
 *
 *  - With nothing costed at all the answer is ZERO, not minus the discount. A sale whose
 *    profit is unknown made no known profit; it did not make a loss.
 */
fun computeSaleMargin(
    allLinesNet: Double,
    costedLinesNet: Double,
    costedLinesCost: Double,
    saleNetTake: Double
): SaleMargin {
    if (costedLinesNet <= 0.0) return SaleMargin(0.0, 0.0, 0.0, 0.0)
    // Floored: a total rounded UP at checkout would otherwise read as a negative
    // discount and inflate profit by the rounding.
    val wholeSaleDiscount = (allLinesNet - saleNetTake).coerceAtLeast(0.0)
    val discountShare =
        if (allLinesNet > 0.0) wholeSaleDiscount * (costedLinesNet / allLinesNet) else 0.0
    val costedRevenue = costedLinesNet - discountShare
    return SaleMargin(
        costTotal = costedLinesCost,
        costedRevenue = costedRevenue,
        discountShare = discountShare,
        profit = costedRevenue - costedLinesCost
    )
}

// ───────────────────────────── the wire figures ─────────────────────────────
// Two columns the shared cloud schema asks each client to state outright rather
// than leave to be reconstructed: `sales.line_discount_total` and `sales.profit_total`.
//
// Both are derived HERE, from the sale's own lines, and neither is stored on
// [SaleEntity]. That is deliberate and it is the same rule that governs refunds
// (see RefundMath's header): the lines are the record, and a stored total is a
// second copy that can drift from them. A column that only exists at the moment
// of push cannot go stale in the database.

/**
 * Σ `lineDiscount` over the sale's live lines — the cloud's `sales.line_discount_total`.
 *
 * This is the figure the sale's own [SaleEntity.discountTotal] already contains: that
 * column is the COMBINED total (whole-sale discount + every per-item discount, see
 * [PosRepository.checkout]). Stating the per-item half separately is what lets a reader
 * recover the other half by subtraction —
 *
 *     wholeSaleDiscount = discountTotal − lineDiscountTotal
 *
 * — instead of reconstructing it as `allLinesNet − (total − taxTotal)`, which is what
 * [computeSaleMargin] does above. That reconstruction is correct, but it is an IMPLICIT
 * invariant: it holds only while every one of those four figures agrees, and it fails
 * silently rather than loudly when one of them doesn't (a checkout rounding adjustment
 * lands in it, which is exactly why line 119 needs its floor).
 *
 * ★ CLAMPED to [SaleEntity.discountTotal]. `computeSaleTotals` clamps the combined
 * discount to the goods value, so a whole-sale discount larger than the cart makes the
 * stored `discountTotal` SMALLER than the discounts the lines actually carry. Left
 * unclamped, the subtraction above would then hand a reader a negative whole-sale
 * discount for a sale that never had one.
 *
 * [lines] must already be filtered to the live ones — a tombstoned line removed by an
 * in-place receipt edit is not part of what the receipt says now.
 */
fun saleLineDiscountTotal(sale: SaleEntity, lines: List<SaleLine>): Double =
    lines.sumOf { it.lineDiscount }
        .coerceIn(0.0, sale.discountTotal.coerceAtLeast(0.0))

/**
 * This sale's margin, computed from its own lines — the cloud's `sales.profit_total`,
 * and byte-for-byte the figure the dashboard shows, because it goes through the same
 * [computeSaleMargin]. Pushing a profit the shop's own reports disagree with would give
 * the owner two answers and no way to choose.
 *
 * Matches the shared definition: costed revenue − discount share − cost, over the
 * COSTED lines only. A line with no [SaleLine.unitCost] is unknown-cost, not zero-cost,
 * and a sale with nothing costed reports zero rather than a loss.
 *
 * `unitsPerLine` is floored at 1 so a malformed line can never zero out its own cost —
 * the same guard the margin read model uses.
 */
fun saleMarginFromLines(sale: SaleEntity, lines: List<SaleLine>): SaleMargin {
    val costed = lines.filter { it.unitCost != null }
    return computeSaleMargin(
        allLinesNet = saleGoodsValue(lines),
        costedLinesNet = saleGoodsValue(costed),
        costedLinesCost = costed.sumOf { it.unitCost!! * maxOf(it.unitsPerLine, 1) * it.qty },
        saleNetTake = sale.total - sale.taxTotal
    )
}

/** [saleMarginFromLines]'s bottom line — what goes in `sales.profit_total`. */
fun saleProfitTotal(sale: SaleEntity, lines: List<SaleLine>): Double =
    saleMarginFromLines(sale, lines).profit

/** Σ `unitCost × unitsPerLine × qty` over the costed lines — the cloud's `sales.cost_total`. */
fun saleCostTotal(sale: SaleEntity, lines: List<SaleLine>): Double =
    saleMarginFromLines(sale, lines).costTotal
