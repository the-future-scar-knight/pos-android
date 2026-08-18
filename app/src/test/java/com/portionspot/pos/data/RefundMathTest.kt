package com.portionspot.pos.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the refund money math. A partial refund must return the customer the
 * proportional share of what they actually paid, carrying the original sale's
 * discount and VAT. Backs [PosRepository.createRefund].
 */
class RefundMathTest {
    private val eps = 1e-9

    @Test
    fun fullReturn_refundsExactlyWhatWasPaid_plainSale() {
        // No discount, no VAT: subtotal == total, so a full return === total.
        val q = computeRefundTotal(returnedSubtotal = 100.0, saleGoodsValue = 100.0, saleTotal = 100.0)
        assertEquals(1.0, q.effectiveRatio, eps)
        assertEquals(100.0, q.refundTotal, eps)
    }

    @Test
    fun fullReturn_refundsGrossTotal_withVat() {
        // subtotal 100 → total 115 (15% VAT). Returning everything refunds the full 115.
        val q = computeRefundTotal(returnedSubtotal = 100.0, saleGoodsValue = 100.0, saleTotal = 115.0)
        assertEquals(115.0, q.refundTotal, eps)
    }

    @Test
    fun partialReturn_carriesVatShare() {
        // subtotal 100 → total 115. Return half the goods ($50) ⇒ refund $57.50.
        val q = computeRefundTotal(returnedSubtotal = 50.0, saleGoodsValue = 100.0, saleTotal = 115.0)
        assertEquals(1.15, q.effectiveRatio, eps)
        assertEquals(57.5, q.refundTotal, eps)
    }

    @Test
    fun partialReturn_carriesDiscountShare() {
        // subtotal 100 → total 90 (10% off, no VAT). Return $40 of goods ⇒ refund $36.
        val q = computeRefundTotal(returnedSubtotal = 40.0, saleGoodsValue = 100.0, saleTotal = 90.0)
        assertEquals(0.9, q.effectiveRatio, eps)
        assertEquals(36.0, q.refundTotal, eps)
    }

    @Test
    fun partialReturn_carriesDiscountAndVatTogether() {
        // subtotal 200 − 20 discount = 180 taxable; +15% VAT = 207 total. Ratio 1.035.
        // Return $50 of goods ⇒ 50 * 1.035 = 51.75.
        val q = computeRefundTotal(returnedSubtotal = 50.0, saleGoodsValue = 200.0, saleTotal = 207.0)
        assertEquals(1.035, q.effectiveRatio, eps)
        assertEquals(51.75, q.refundTotal, eps)
    }

    @Test
    fun zeroSaleSubtotal_fallsBackToFaceValue_noDivideByZero() {
        // Comp'd / ad-hoc sale with subtotal 0: ratio defaults to 1, refund == goods value.
        val q = computeRefundTotal(returnedSubtotal = 30.0, saleGoodsValue = 0.0, saleTotal = 0.0)
        assertEquals(1.0, q.effectiveRatio, eps)
        assertEquals(30.0, q.refundTotal, eps)
    }

    @Test
    fun nothingReturned_isZero() {
        val q = computeRefundTotal(returnedSubtotal = 0.0, saleGoodsValue = 100.0, saleTotal = 115.0)
        assertEquals(0.0, q.refundTotal, eps)
    }

    @Test
    fun negativeReturnedSubtotal_clampedToZero() {
        val q = computeRefundTotal(returnedSubtotal = -10.0, saleGoodsValue = 100.0, saleTotal = 100.0)
        assertEquals(0.0, q.returnedSubtotal, eps)
        assertEquals(0.0, q.refundTotal, eps)
    }

    // ── The settlement split: debt cancelled vs money payable ─────────────────
    //
    // Every case here is a real till behaviour the owner reported on 16-17 August, when a
    // refund was worth its full face value in CASH whatever the customer had paid.

    @Test
    fun fullyPaidSale_refundedInFull_paysBackEverything_andClearsNoDebt() {
        val s = planRefundSettlement(
            refundTotal = 100.0, saleTotal = 100.0, collectedOnSale = 100.0, alreadyRefunded = 0.0
        )
        assertEquals(0.0, s.debtRelieved, eps)
        assertEquals(100.0, s.payable, eps)
    }

    @Test
    fun partPaidCreditSale_refundedInFull_paysBackOnlyWhatArrived() {
        // ★ THE −$60 TILL. $100 sale, $40 taken at the counter, $60 on account, all of it
        // returned. The old code handed back $100 and left the drawer at minus sixty.
        val s = planRefundSettlement(
            refundTotal = 100.0, saleTotal = 100.0, collectedOnSale = 40.0, alreadyRefunded = 0.0
        )
        assertEquals(60.0, s.debtRelieved, eps)
        assertEquals(40.0, s.payable, eps)
    }

    @Test
    fun unpaidCreditSale_refundedInFull_paysBackNothing_andClearsTheWholeDebt() {
        // The owner's own words: it should just restock and clear the account.
        val s = planRefundSettlement(
            refundTotal = 100.0, saleTotal = 100.0, collectedOnSale = 0.0, alreadyRefunded = 0.0
        )
        assertEquals(100.0, s.debtRelieved, eps)
        assertEquals(0.0, s.payable, eps)
    }

    @Test
    fun partPaidSale_partiallyRefunded_eatsTheDebtFirst() {
        // $100 sale, $40 paid, $60 owed. Return $40 of goods: it comes off the debt, and
        // no cash crosses the counter to somebody who still owes for the same receipt.
        val s = planRefundSettlement(
            refundTotal = 40.0, saleTotal = 100.0, collectedOnSale = 40.0, alreadyRefunded = 0.0
        )
        assertEquals(40.0, s.debtRelieved, eps)
        assertEquals(0.0, s.payable, eps)
    }

    @Test
    fun partPaidSale_thenTheRest_settlesToNothingOwedEitherWay() {
        // Continues the case above: the remaining $60 of goods goes back. $20 of debt is
        // left to cancel, and the $40 the customer actually handed over comes back to them.
        val s = planRefundSettlement(
            refundTotal = 60.0, saleTotal = 100.0, collectedOnSale = 40.0, alreadyRefunded = 40.0
        )
        assertEquals(20.0, s.debtRelieved, eps)
        assertEquals(40.0, s.payable, eps)
    }

    @Test
    fun refundsOfOneSale_neverPayOutMoreThanItCollected() {
        // The invariant, walked: three partial refunds of a part-paid sale must hand back
        // at most the $40 that ever arrived, however they are sliced.
        var refundedSoFar = 0.0
        var paidOut = 0.0
        repeat(3) {
            val s = planRefundSettlement(
                refundTotal = 100.0 / 3.0,
                saleTotal = 100.0,
                collectedOnSale = 40.0,
                alreadyRefunded = refundedSoFar,
            )
            refundedSoFar += s.refundTotal
            paidOut += s.payable
        }
        assertEquals(40.0, paidOut, 1e-6)
    }

    @Test
    fun payable_isClampedToCollected_evenOnNonsenseInput() {
        // A refund bigger than the sale (a caller that skipped the line-quantity cap) must
        // not become a licence to empty the drawer.
        val s = planRefundSettlement(
            refundTotal = 500.0, saleTotal = 100.0, collectedOnSale = 40.0, alreadyRefunded = 0.0
        )
        assertEquals(40.0, s.payable, eps)
    }

    @Test
    fun walkInSaleThatCollectedNothing_hasNothingToPayBack() {
        val s = planRefundSettlement(
            refundTotal = 25.0, saleTotal = 25.0, collectedOnSale = 0.0, alreadyRefunded = 0.0
        )
        assertEquals(0.0, s.payable, eps)
    }

    // -- Undoing one: what a void has to put back ---------------------------

    @Test
    fun voidingAFullyPaidRefund_creditsWhatIsOwed_andRestoresNoDebt() {
        // payable == total, which is every refund written before debt-first settlement,
        // so this pins that the change is a no-op for historical rows.
        val v = planRefundVoid(refundTotal = 100.0, payableTotal = 100.0, paidOut = 0.0)
        assertEquals(100.0, v.refundPaid, eps)
        assertEquals(0.0, v.debtRestored, eps)
    }

    @Test
    fun voidingADebtFirstRefund_creditsOnlyThePayablePart() {
        // THE MINUS SIXTY. $100 sale, $40 collected: the refund owed the customer $40, so
        // voiding it must credit 40. Crediting the goods value drove the shop's "we owe
        // you" balance to -60 -- a customer owing money in a ledger that only runs the
        // other way.
        val v = planRefundVoid(refundTotal = 100.0, payableTotal = 40.0, paidOut = 0.0)
        assertEquals(40.0, v.refundPaid, eps)
        assertEquals(60.0, v.debtRestored, eps)
    }

    @Test
    fun voidingAfterAPartialPayout_creditsOnlyWhatIsStillOwed() {
        val v = planRefundVoid(refundTotal = 100.0, payableTotal = 40.0, paidOut = 25.0)
        assertEquals(15.0, v.refundPaid, eps)
        assertEquals(60.0, v.debtRestored, eps)
    }

    @Test
    fun voidingAFullyPaidOutRefund_creditsNothing_butStillRestoresTheDebt() {
        // The money already went back across the counter, so there is no liability left to
        // cancel. The debt still has to return: the void un-restocks, so the customer has
        // the goods again and owes for them.
        val v = planRefundVoid(refundTotal = 100.0, payableTotal = 40.0, paidOut = 40.0)
        assertEquals(0.0, v.refundPaid, eps)
        assertEquals(60.0, v.debtRestored, eps)
    }

    @Test
    fun voidingAnUnpaidCreditSalesRefund_restoresTheWholeDebt() {
        val v = planRefundVoid(refundTotal = 100.0, payableTotal = 0.0, paidOut = 0.0)
        assertEquals(0.0, v.refundPaid, eps)
        assertEquals(100.0, v.debtRestored, eps)
    }

    @Test
    fun voidNeverReturnsANegative_onNonsenseInput() {
        val over = planRefundVoid(refundTotal = 100.0, payableTotal = 500.0, paidOut = 900.0)
        assertEquals(0.0, over.refundPaid, eps)
        assertEquals(0.0, over.debtRestored, eps)
    }

    @Test
    fun settlementAndVoidAgreeAboutWhatWasCancelled() {
        // The two halves of the round trip, walked. Whatever the settlement cancelled off
        // the account, the void must put back -- if these ever disagree a debt is either
        // forgiven twice or charged twice, and nothing on screen would say so.
        val s = planRefundSettlement(
            refundTotal = 100.0, saleTotal = 100.0, collectedOnSale = 40.0, alreadyRefunded = 0.0
        )
        val v = planRefundVoid(
            refundTotal = 100.0, payableTotal = s.payable, paidOut = 0.0
        )
        assertEquals(s.debtRelieved, v.debtRestored, eps)
        assertEquals(s.payable, v.refundPaid, eps)
    }
}
