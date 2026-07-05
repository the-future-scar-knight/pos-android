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
 *     effectiveRatio = if (saleSubtotal > 0) saleTotal / saleSubtotal else 1.0
 *
 * so   refundTotal = Σ(returned line subtotal) * effectiveRatio.
 *
 *  - FULL return (every unit back): Σ == saleSubtotal, so refundTotal == saleTotal —
 *    the customer gets back exactly what they paid, VAT and discount included.
 *  - A discounted sale (subtotal 100 → total 90) refunds a $40 return as $36.
 *  - A VAT sale (subtotal 100 → total 115) refunds a $50 return as $57.50.
 *  - Guarded against a zero/absent subtotal (free or ad-hoc sale ⇒ ratio 1.0, so the
 *    refund equals the returned goods' face value and we never divide by zero).
 *
 * The [returnedSubtotal] is the pre-adjustment value of the returned goods, i.e.
 * Σ(original unitPrice × qtyReturned). The multiplier is applied EXACTLY ONCE here
 * (mistake-ledger: unitsPerLine/total math — verify the multiplier isn't doubled).
 */
data class RefundQuote(
    val returnedSubtotal: Double,   // pre-adjustment value of the returned goods
    val effectiveRatio: Double,     // paid/goods ratio carrying discount + VAT
    val refundTotal: Double         // what the shop owes the customer
)

fun computeRefundTotal(
    returnedSubtotal: Double,
    saleSubtotal: Double,
    saleTotal: Double
): RefundQuote {
    val base = returnedSubtotal.coerceAtLeast(0.0)
    val ratio = if (saleSubtotal > 0.0) saleTotal / saleSubtotal else 1.0
    return RefundQuote(
        returnedSubtotal = base,
        effectiveRatio = ratio,
        refundTotal = base * ratio
    )
}
