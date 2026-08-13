package com.portionspot.pos.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WHAT COUNTS AS A RECEIPT, AND WHAT MAY BE REFUNDED.
 *
 * ══ The bug this pins ══
 * `sales` is not a table of sales. A QUOTE — a document priced for a customer who paid
 * nothing and took nothing — and a PARKED cart still on the counter live in it too, under
 * their own [SaleEntity.status]. The Receipts list used to query the table with no status
 * filter at all, so a quote rendered beside real takings with reprint, share and, for
 * anyone holding PROCESS_REFUNDS, a Refund button. Refunding it would have restocked goods
 * that never left the shop and booked a real `refund_owed` debt against a document that
 * was never a sale.
 *
 * The list is filtered now, but a list is a presentation decision and the next screen that
 * wants a sale list can be written without knowing that. [SaleEntity.isRefundable] is the
 * guard on the money path itself, which is what these assert.
 */
class ReceiptStatusTest {

    private fun sale(status: String, deleted: Boolean = false) =
        SaleEntity(businessId = "b1", status = status, deleted = deleted)

    /** ★ The hole: a quote is not a sale and can never be reversed as one. */
    @Test
    fun `a quote is not refundable`() {
        assertFalse(sale("quote").isRefundable())
    }

    /** A cart on hold has not been paid for and no goods have left. */
    @Test
    fun `a parked cart is not refundable`() {
        assertFalse(sale("parked").isRefundable())
    }

    /**
     * A voided sale has already been reversed on purpose — its goods and money were put
     * back by whatever voided it. Refunding it would reverse the same transaction twice.
     */
    @Test
    fun `a voided sale is not refundable`() {
        assertFalse(sale("void").isRefundable())
    }

    /** The ordinary case: money moved, goods left, it can come back. */
    @Test
    fun `a completed sale is refundable`() {
        assertTrue(sale("completed").isRefundable())
    }

    /**
     * ★ Refunds are per line and partial, so a sale that has already had something
     * returned stays refundable — what is left to return is capped by
     * [PosRepository.qtyReturnedForLine], not by the status.
     */
    @Test
    fun `an already-refunded sale is still refundable`() {
        assertTrue(sale("refunded").isRefundable())
    }

    /** A tombstoned row is gone, whatever it once was. */
    @Test
    fun `a deleted sale is not refundable`() {
        assertFalse(sale("completed", deleted = true).isRefundable())
    }

    /**
     * An unrecognised status fails CLOSED. A future status arriving from the cloud or a
     * newer web build must not be assumed to be a receipt — the set is an allow-list, and
     * the cost of being wrong is stock and money moving against a document nobody sold.
     */
    @Test
    fun `an unknown status is not refundable`() {
        assertFalse(sale("layby").isRefundable())
        assertFalse(sale("").isRefundable())
    }

    /** The receipt set and the refundable set are deliberately the same set. */
    @Test
    fun `the receipt statuses are exactly completed and refunded`() {
        assertTrue("completed" in RECEIPT_STATUSES)
        assertTrue("refunded" in RECEIPT_STATUSES)
        assertFalse("quote" in RECEIPT_STATUSES)
        assertFalse("parked" in RECEIPT_STATUSES)
        assertFalse("void" in RECEIPT_STATUSES)
    }
}
