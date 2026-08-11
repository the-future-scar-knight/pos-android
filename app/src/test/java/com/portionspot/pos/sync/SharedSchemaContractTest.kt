package com.portionspot.pos.sync

import com.portionspot.pos.data.CashLocation
import com.portionspot.pos.data.CashTxn
import com.portionspot.pos.data.cashMovementCountedElsewhere
import com.portionspot.pos.sync.wire.ItemDto
import com.portionspot.pos.sync.wire.baselineStamp
import com.portionspot.pos.sync.wire.toMovementPush
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Three places where this app and the web POS have to agree about the SAME row, and
 * where disagreeing produces a wrong number rather than an error.
 *
 * Each of these was found on a real device against the shop's own database.
 */
class SharedSchemaContractTest {

    // ── cash_movements.amount is a MAGNITUDE ─────────────────────────────────────
    //
    // The shared schema puts the direction in `type` and reads `amount` as unsigned:
    // its cash-up multiplies a `pay_out` by −1. This app stores the same movement
    // SIGNED. Send the signed figure and a $5 refund arrives as −5, is negated again,
    // and ADDS $5 to the expected drawer — a refund that leaves the till looking
    // fuller than before it.

    private fun txn(amount: Double, type: String = "refund", refType: String? = null) = CashTxn(
        businessId = "biz-1",
        type = type,
        amount = amount,
        location = CashLocation.TILL,
        refType = refType,
    )

    @Test
    fun cashPayout_goesUpPositive_soTheDrawerIsNotCreditedForARefund() {
        val push = txn(-5.0).toMovementPush(sessionId = null)
        assertEquals("pay_out", push.type)
        assertEquals(5.0, push.amount, 1e-9)
    }

    @Test
    fun cashPayin_keepsItsMagnitude() {
        val push = txn(60.0, type = "sale").toMovementPush(sessionId = null)
        assertEquals("pay_in", push.type)
        assertEquals(60.0, push.amount, 1e-9)
    }

    // ── a sale's cash is counted on the other side already ───────────────────────
    //
    // The web derives takings from the tenders, change from `sales.change_due` and
    // payouts from `refund_payments`, and says so on its own Cash-up screen. This app
    // ALSO writes a drawer movement for each, so pushing them counts the money twice.

    @Test
    fun saleAndRefundDrawerMovements_areNotOurs_toPush() {
        assertTrue(cashMovementCountedElsewhere("sale"))
        assertTrue(cashMovementCountedElsewhere("refund"))
    }

    @Test
    fun everyOtherMovement_muststillGoUp_orAPhonesCashIsInvisibleAtCashUp() {
        // A petty spend, a bank drop, a float top-up made on a phone has no other
        // representation in the cloud at all.
        assertFalse(cashMovementCountedElsewhere(null))
        assertFalse(cashMovementCountedElsewhere("expense"))
        assertFalse(cashMovementCountedElsewhere("day_close"))
        assertFalse(cashMovementCountedElsewhere("purchase_order"))
    }

    // ── the stock baseline is stamped on the DEVICE clock ────────────────────────
    //
    // `stockBaseAt` is compared against `stock_movements.created_at`, which is a client
    // clock. `updated_at` is the SERVER's, rewritten by a trigger on arrival, so it runs
    // seconds ahead of the very movement that produced the figure — measure from it and
    // that movement is silently dropped, taking its sale with it.

    @Test
    fun baseline_prefersTheClientClock() {
        val dto = ItemDto(
            id = "i1",
            updatedAt = "2026-08-11T04:26:42.169Z",       // server: 3.6s later
            clientUpdatedAt = "2026-08-11T04:26:38.536Z", // device: when the edit happened
        )
        assertEquals(IsoTime.toMillis("2026-08-11T04:26:38.536Z"), dto.baselineStamp())
    }

    @Test
    fun baseline_fallsBackToTheServerStampForARowThatPredatesTheColumn() {
        // A bulk SQL import, or a client that knows nothing about `client_updated_at`.
        // "True when the server received it" is the best that can be said about those,
        // and it is what they used to compare as.
        val dto = ItemDto(id = "i1", updatedAt = "2026-08-09T13:39:48.836Z", clientUpdatedAt = null)
        assertEquals(IsoTime.toMillis("2026-08-09T13:39:48.836Z"), dto.baselineStamp())
    }
}
