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

/**
 * How a refund SETTLES: what it cancels off the customer's debt, and what may actually
 * cross the counter.
 *
 *  - [debtRelieved] the unpaid part of the sale that this refund cancels. Goods that came
 *    back are not owed for.
 *  - [payable]      the MOST that may be handed back in money — cash, EcoCash, anything.
 *    The remainder of [refundTotal] after the debt is cancelled.
 */
data class RefundSettlement(
    val refundTotal: Double,
    val debtRelieved: Double,
    val payable: Double,
)

/**
 * Split a refund into debt cancelled and money payable — DEBT FIRST.
 *
 * ══ THE BUG THIS EXISTS FOR ══
 * A refund used to be worth [refundTotal] in CASH, whatever the customer had actually
 * paid. On a real till: a $100 sale taking $40 cash with $60 on account, refunded in
 * full, handed back $100 — and left the drawer reading MINUS $60. The shop paid out
 * money it was never given, and the $60 the customer still owed sat untouched on their
 * account. Both halves of that are this function.
 *
 * ══ WHY DEBT FIRST AND NOT PRO-RATA ══
 * A part-returned, part-paid sale could plausibly split either way. Debt first is the
 * owner's rule and it is the conservative one: the shop does not hand cash across the
 * counter to somebody who still owes it money for the same receipt. Worked through, on a
 * $100 sale with $40 paid and $60 owed:
 *
 *  - return $40 of goods → cancels $40 of debt, pays out nothing. Debt $20, and the
 *    customer has paid $40 for the $60 of goods they kept. Correct.
 *  - then return the remaining $60 → cancels the last $20 of debt, pays back the $40.
 *    Nothing owed either way, and the money that arrived is the money that went back.
 *
 * ══ THE INVARIANT ══
 * Summed over every refund of one sale, [payable] can never exceed what the sale
 * COLLECTED. The debt is `saleTotal − alreadyRefunded − collectedOnSale` and is eaten
 * before any money is payable, so the payouts left over come to at most `collectedOnSale`
 * — which is precisely the drawer never going negative on a refund again. [payable] is
 * also clamped at [collectedOnSale] outright, so a bad input cannot break the invariant
 * a subtraction was relied on to hold.
 *
 * [collectedOnSale] is [CashBasis.rawCollectedBySale] for this sale: money settled at the
 * till PLUS later repayments FIFO-matched to it, so a debt paid off next week is
 * refundable in cash the week after. [alreadyRefunded] is the sum of prior live refunds'
 * `refundTotal` against the same sale, which is how the debt shrinks as goods go back.
 */
fun planRefundSettlement(
    refundTotal: Double,
    saleTotal: Double,
    collectedOnSale: Double,
    alreadyRefunded: Double,
): RefundSettlement {
    val total = refundTotal.coerceAtLeast(0.0)
    val collected = collectedOnSale.coerceIn(0.0, saleTotal.coerceAtLeast(0.0))
    val saleDebt =
        (saleTotal - alreadyRefunded.coerceAtLeast(0.0) - collected).coerceAtLeast(0.0)
    val relieved = minOf(total, saleDebt)
    return RefundSettlement(
        refundTotal = total,
        debtRelieved = relieved,
        payable = (total - relieved).coerceIn(0.0, collected),
    )
}

/** What voiding a refund has to put back: see [planRefundVoid]. */
data class RefundVoid(
    /** Compensating `refund_paid` — cancels the outstanding "we owe you" balance. */
    val refundPaid: Double,
    /** Compensating `credit_owed` — puts back the debt the refund cancelled. */
    val debtRestored: Double,
)

/**
 * Undo a refund, in the two ledgers it touched.
 *
 * ══ THE BUG THIS EXISTS FOR ══
 * Voiding measured its compensating row against the GOODS value while the refund had
 * booked its liability against what was PAYABLE. On a $100 sale that had collected $40:
 * the refund owed the customer $40, and voiding it credited $100 — driving the shop's
 * "we owe this customer" balance to MINUS $60, a customer owing money in a ledger that
 * only runs the other way. The debt the refund cancelled was never restored either, so
 * the customer kept $60 of goods free while the void un-restocked them.
 *
 * ★ IT HID IN THE HEADLINE FIGURE. The over-credit and the unrestored debt cancelled to
 * the cent, so the customer's NET balance came out exactly right and any test asserting
 * on it passed against the bug. It showed only in the we-owe figure and in a credit lot
 * left consumed, which made the sale permanently unrecognisable. Assert on those two.
 *
 * ══ WHY BOTH ARE NEW ROWS ══
 * Neither undoes anything by deletion or edit. A correction here is always an appended
 * row — the same rule the cash ledger follows — because a deletion made on one device
 * races a pull on the other, and the two would never converge. The restored `credit_owed`
 * opens a FRESH FIFO lot carrying the sale id, which is right: the void put the goods back
 * in the customer's hands, so a later repayment should recognise revenue for that sale.
 *
 * [paidOut] is what has actually been handed over against this refund so far.
 */
fun planRefundVoid(
    refundTotal: Double,
    payableTotal: Double,
    paidOut: Double,
): RefundVoid {
    val total = refundTotal.coerceAtLeast(0.0)
    // A refund written before the two figures were distinguished carries payable == total,
    // so this is a no-op for every historical row.
    val payable = payableTotal.coerceIn(0.0, total)
    return RefundVoid(
        refundPaid = (payable - paidOut.coerceAtLeast(0.0)).coerceAtLeast(0.0),
        debtRestored = (total - payable).coerceAtLeast(0.0),
    )
}

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
