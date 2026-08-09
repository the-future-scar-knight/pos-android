package com.portionspot.pos.data

/**
 * The money math for a refund, pulled out of [PosRepository.createRefund] into a
 * pure function so it can be unit-tested without Room or a device (mirrors
 * [computeSaleTotals] in SaleMath).
 *
 * A partial refund must return the customer the PROPORTIONAL share of what they
 * actually paid — carrying the original sale's whole-sale discount AND its VAT. We
 * capture both with a single effective ratio:
 *
 *     effectiveRatio = if (saleGoodsValue > 0) saleTotal / saleGoodsValue else 1.0
 *
 * so   refundTotal = Σ(returned line value) * effectiveRatio.
 *
 *  - FULL return (every unit back): Σ == saleGoodsValue, so refundTotal == saleTotal —
 *    the customer gets back exactly what they paid, VAT and discount included.
 *  - A discounted sale (goods 100 → total 90) refunds a $40 return as $36.
 *  - A VAT sale (goods 100 → total 115) refunds a $50 return as $57.50.
 *  - Guarded against a zero/absent goods value (free or ad-hoc sale ⇒ ratio 1.0, so
 *    the refund equals the returned goods' face value and we never divide by zero).
 *
 * ★ BOTH SIDES OF THE RATIO MUST BE THE SAME MEASURE. Build [returnedSubtotal] AND
 * [saleGoodsValue] with [returnedLineValue] — never with a bare `unitPrice × qty`,
 * and never from [SaleEntity.subtotal], which is the GROSS goods value with per-item
 * discounts and markups stripped out into the sale's own `discountTotal`/`markupTotal`.
 * Mixing the two measures mis-allocates every per-item adjustment across the whole
 * sale: two $100 lines with $20 off ONE of them refunded $90 for the discounted line
 * (it was worth $80) and $90 for the untouched one (it was worth $100).
 *
 * The multiplier is applied EXACTLY ONCE, inside [returnedLineValue] (mistake-ledger:
 * unitsPerLine/total math — verify the multiplier isn't doubled).
 */
data class RefundQuote(
    val returnedSubtotal: Double,   // value of the returned goods, net of their own adjustments
    val effectiveRatio: Double,     // paid/goods ratio carrying the whole-sale discount + VAT
    val refundTotal: Double         // what the shop owes the customer
)

/**
 * What a partly-returned line is worth against the sale's own goods value.
 *
 * The line's OWN adjustments come off first, pro-rata. A line priced at $100 with
 * $20 knocked off contributed $80 to the goods the customer paid for, so returning
 * all of it is worth $80 — using $100 refunds money that was never paid. Returning
 * half is worth $40, not $50. A cashier markup runs the same way in the opposite
 * direction: a $100 line with $10 added on is worth $110 back, or $55 for half.
 *
 *     value = (unitPrice × qty − lineDiscount + lineMarkup) × (qtyReturned / qty)
 *
 * Summed over every line of a sale at full [qtyReturned] this gives the sale's goods
 * value, which is exactly what [computeRefundTotal]'s ratio needs as its denominator
 * so that a full return lands on the sale total to the cent.
 *
 * Line adjustments reach this app two ways: local checkout writes the cart's per-item
 * discount and cashier markup onto every [SaleLine], and a sale pulled from the cloud
 * that was rung up on the web POS carries its own line discounts. Both are served by
 * deriving the value here rather than trusting the stored `lineTotal`, whose tax and
 * markup treatment differs between the cart and the persisted line.
 *
 * [qtyReturned] is clamped to [qty]: a caller can never return more of a line than
 * was bought, whatever it passes.
 */
fun returnedLineValue(
    qty: Double,
    unitPrice: Double,
    lineDiscount: Double,
    lineMarkup: Double,
    qtyReturned: Double
): Double {
    if (qty <= 0.0) return 0.0
    val back = qtyReturned.coerceAtLeast(0.0)
    if (back <= 0.0) return 0.0
    val net = unitPrice * qty - lineDiscount + lineMarkup
    return net * (minOf(back, qty) / qty)
}

/** [returnedLineValue] for a persisted line — the shape every caller actually holds. */
fun returnedLineValue(line: SaleLine, qtyReturned: Double): Double =
    returnedLineValue(
        qty = line.qty,
        unitPrice = line.unitPrice,
        lineDiscount = line.lineDiscount,
        lineMarkup = line.lineMarkup,
        qtyReturned = qtyReturned
    )

/** The whole sale's goods value — [returnedLineValue] over every line at full qty.
 *  The denominator [computeRefundTotal] needs, and the only figure that keeps a full
 *  return landing exactly on the sale total. */
fun saleGoodsValue(lines: List<SaleLine>): Double =
    lines.sumOf { returnedLineValue(it, it.qty) }

fun computeRefundTotal(
    returnedSubtotal: Double,
    saleGoodsValue: Double,
    saleTotal: Double
): RefundQuote {
    val base = returnedSubtotal.coerceAtLeast(0.0)
    val ratio = if (saleGoodsValue > 0.0) saleTotal / saleGoodsValue else 1.0
    return RefundQuote(
        returnedSubtotal = base,
        effectiveRatio = ratio,
        refundTotal = base * ratio
    )
}
