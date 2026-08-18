package com.portionspot.pos.sync

import com.portionspot.pos.data.CashLocation
import com.portionspot.pos.data.CashTxn
import com.portionspot.pos.data.cashMovementCountedElsewhere
import com.portionspot.pos.sync.wire.CashMovementDto
import com.portionspot.pos.sync.wire.ItemDto
import com.portionspot.pos.sync.wire.baselineStamp
import com.portionspot.pos.sync.wire.toCashTxn
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

    // ── the same movement coming back DOWN ───────────────────────────────────────
    //
    // `cash_movements` was pushed and never pulled. Confirmed on two phones: one closed
    // the trading day — a `variance` true-up plus a till→safe transfer — and recorded an
    // owner drawing; all of it reached the database and none of it reached the other
    // phone, so the two disagreed about the till and the safe with nothing saying why.
    //
    // The pull is what fixes that, and it has exactly two ways to make things worse:
    // re-typing a row it wrote itself, and importing money the local mirror has already
    // counted. Both are pinned here.

    private fun movement(
        id: String = "mv-1",
        type: String = "pay_out",
        amount: String = "40",
        reason: String? = "Petty cash for airtime",
        updatedAt: String = "2026-08-17T09:15:00.000Z",
        deleted: Boolean = false,
    ) = CashMovementDto(
        id = id,
        type = type,
        amount = amount,
        reason = reason,
        createdAt = "2026-08-17T09:14:58.000Z",
        updatedAt = updatedAt,
        deleted = deleted,
    )

    @Test
    fun aMovementThisDeviceHasNeverSeen_becomesACleanLocalRow() {
        val row = movement().toCashTxn("biz-1", local = null)
        assertEquals("mv-1", row.id)
        assertEquals(CashLocation.TILL, row.location)
        assertEquals(-40.0, row.amount, 1e-9)
        assertEquals("Petty cash for airtime", row.note)
        // ★ NOT dirty. A row marked pending on arrival is uploaded straight back to the
        // table it was just read from, and every device in the shop keeps bouncing it.
        assertFalse("a pulled row must never be queued for upload", row.pendingSync)
        // No ref_type on the wire, so none is invented — inventing one would hide a real
        // movement from the cash-up, which excludes sale/refund rows by name.
        assertEquals(null, row.refType)
        // The movement's own instant, not the moment of the sync: a phone coming back
        // online after midnight must not file yesterday's cash in today's count.
        assertEquals(IsoTime.toMillis("2026-08-17T09:14:58.000Z"), row.createdAt)
    }

    @Test
    fun aDeviceReadingBackItsOwnPushedRow_doesNotRewriteWhatItMeant() {
        // ★ THE ECHO. The push sends the local id as the cloud id and the server stamps
        // `updated_at` LATER than the client wrote it, so a device re-reads its own rows
        // and they always look newer. The trip out is lossy — this app's vocabulary is
        // far wider than the cloud's seven types — so an owner drawing of $100 goes up as
        // a bare `pay_out`. Apply that back and the drawer total stays right while the
        // drawing disappears from the equity split: money that was never profit starts
        // being reported as profit, and nothing errors.
        val mine = CashTxn(
            id = "mv-1",
            businessId = "biz-1",
            type = "drawing",
            amount = -100.0,
            location = CashLocation.TILL,
            note = "Owner took cash",
            updatedAt = 1_000L,
            pendingSync = false,
        )
        val after = movement(type = "pay_out", amount = "100").toCashTxn("biz-1", local = mine)
        assertEquals("drawing", after.type)
        assertEquals(-100.0, after.amount, 1e-9)
        assertEquals(CashLocation.TILL, after.location)
        assertEquals("Owner took cash", after.note)
        assertFalse(after.pendingSync)
        // Re-running the pull must be a no-op: the stamp moves forward so the row cannot
        // qualify a second time.
        assertTrue(after.updatedAt >= IsoTime.toMillis("2026-08-17T09:15:00.000Z"))
    }

    @Test
    fun aTombstoneIsTheOneThingThatDoesTravelOntoAnExistingRow() {
        // Deleting a movement is not lossy and it genuinely has to reach the other phone,
        // or cash the shop has decided never moved stays in one device's drawer forever.
        val mine = CashTxn(
            id = "mv-1", businessId = "biz-1", type = "drawing", amount = -100.0,
            location = CashLocation.TILL, updatedAt = 1_000L, pendingSync = false,
        )
        val after = movement(deleted = true).toCashTxn("biz-1", local = mine)
        assertTrue(after.deleted)
        assertEquals("drawing", after.type)
    }

    @Test
    fun aSalesOwnDrawerRow_isTheMirrorsToOwn_andThePullSkipsIt() {
        // ★ THE DOUBLE-COUNT GUARD. A sale's and a refund's drawer effect is deliberately
        // never pushed and is rebuilt locally from the pulled tenders by planCashMirror,
        // so a wire row must never be able to book that same money a second time. The
        // wire carries no `ref_type`, so the invariant is upheld by the WRITERS — this
        // app excludes them from the push and the web never writes them — and the pull's
        // own check is on the row it already holds: [cashMovementCountedElsewhere] over
        // the LOCAL refType, which is the same predicate the push partitions on.
        assertTrue(cashMovementCountedElsewhere("sale"))
        assertTrue(cashMovementCountedElsewhere("refund"))
        // And a mirror row that somehow met a wire row of the same id keeps every figure
        // the mirror put on it, so even reaching the guard cannot move the money.
        val mirrored = CashTxn(
            id = "mv-1", businessId = "biz-1", type = "sale", amount = 60.0,
            location = CashLocation.TILL, refType = "sale", refId = "sale-9",
            updatedAt = 1_000L, pendingSync = false,
        )
        val after = movement(type = "pay_in", amount = "60").toCashTxn("biz-1", local = mirrored)
        assertEquals("sale", after.refType)
        assertEquals("sale-9", after.refId)
        assertEquals(60.0, after.amount, 1e-9)
    }
}
