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
