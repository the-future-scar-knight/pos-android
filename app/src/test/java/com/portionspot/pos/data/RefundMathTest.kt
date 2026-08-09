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
}
