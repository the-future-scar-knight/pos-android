package com.portionspot.pos.sync

import com.portionspot.pos.data.Refund
import com.portionspot.pos.sync.wire.RefundDto
import com.portionspot.pos.sync.wire.toPush
import com.portionspot.pos.sync.wire.toRefund
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `refunds.payable_total` on the wire — the difference between what the goods were worth
 * and what may actually be handed back in money.
 *
 * ── THE MONEY ─────────────────────────────────────────────────────────────────
 *
 * A customer brings back $100 of goods and owes the shop $60. The till cancels the $60 off
 * the account and hands back $40; `refundTotal` is 100 and `payableTotal` is 40. Those are
 * two different facts and only one of them used to travel. A phone that pulled that refund
 * saw a $100 refund with no cap on it, showed "$100 to hand back", and the shop paid the
 * customer the $60 it had just recovered in stock. Nothing errored. The two tills simply
 * disagreed about a number neither of them could see the other's copy of.
 *
 * ── AND THE SYMMETRICAL MISTAKE ───────────────────────────────────────────────
 *
 * Reading a NULL as a zero costs exactly as much in the other direction: the web raises
 * refunds without settling debt first, so its rows state no cap, and a till that read that
 * silence as "nothing is payable" would refuse to pay a customer standing at the counter
 * holding their own goods. Every row written before the column existed carries NULL too.
 */
class RefundPayableWireTest {

    private val bid = "b-1"

    private fun dto(
        refundTotal: String? = "100.00",
        payableTotal: String? = null,
        updatedAt: String = "2026-08-18T10:00:00.000Z",
    ) = RefundDto(
        id = "r-1",
        saleId = "s-1",
        refundTotal = refundTotal,
        payableTotal = payableTotal,
        status = "owed",
        createdAt = "2026-08-18T09:00:00.000Z",
        updatedAt = updatedAt,
    )

    private fun local(payable: Double, total: Double = 100.0) = Refund(
        id = "r-1",
        businessId = bid,
        saleId = "s-1",
        refundTotal = total,
        payableTotal = payable,
    )

    // ── reading ───────────────────────────────────────────────────────────

    @Test
    fun `a NULL payable_total on a row this device has never seen reads as the whole total`() {
        // The web-raised refund. It does not settle debt first, so the goods value IS the
        // payout — and a zero here would leave the customer unpaid at the counter.
        val merged = dto(payableTotal = null).toRefund(bid, local = null)!!
        assertEquals(100.0, merged.payableTotal, 0.0001)
        assertEquals(100.0, merged.refundTotal, 0.0001)
    }

    @Test
    fun `a stated payable_total is respected, not rounded up to the goods value`() {
        val merged = dto(payableTotal = "40.00").toRefund(bid, local = null)!!
        assertEquals(40.0, merged.payableTotal, 0.0001)
        // The goods that came back are still worth $100. The cap does not rewrite that.
        assertEquals(100.0, merged.refundTotal, 0.0001)
    }

    @Test
    fun `a local cap is NOT clobbered by a wire NULL`() {
        // The regression that costs $60: this device capped the payout at $40, and a row
        // that says nothing about the cap must not un-cap it.
        val merged = dto(payableTotal = null).toRefund(bid, local = local(payable = 40.0))!!
        assertEquals(40.0, merged.payableTotal, 0.0001)
    }

    @Test
    fun `a stated wire cap wins over a stale local one`() {
        // The pull only reaches this mapper once the row's clock is ahead of the local
        // one (see PosSyncEngine.pullRefunds), so a stated figure is the newer truth —
        // an admin reduced what may be handed back and the till must hear it.
        val merged = dto(payableTotal = "25.00").toRefund(bid, local = local(payable = 40.0))!!
        assertEquals(25.0, merged.payableTotal, 0.0001)
    }

    @Test
    fun `a wire ZERO is a real cap and is not mistaken for silence`() {
        // "The whole refund settles debt and nothing crosses the counter" is a legitimate
        // outcome. Only a MISSING column falls back; a stated 0 stands.
        val merged = dto(payableTotal = "0").toRefund(bid, local = local(payable = 100.0))!!
        assertEquals(0.0, merged.payableTotal, 0.0001)
    }

    @Test
    fun `the column decodes off a real PostgREST row`() {
        // numeric renders as a JSON STRING, so a Double field here would fail to parse the
        // shared schema's own output.
        val decoded = Json { ignoreUnknownKeys = true }.decodeFromString<List<RefundDto>>(
            """[{"id":"r-9","sale_id":"s-9","refund_total":"100.00","payable_total":"40.00",
                 "status":"owed","created_at":"2026-08-18T09:00:00.000Z",
                 "updated_at":"2026-08-18T10:00:00.000Z","deleted":false}]"""
        )
        val merged = decoded.single().toRefund(bid, local = null)!!
        assertEquals(40.0, merged.payableTotal, 0.0001)
    }

    // ── writing ───────────────────────────────────────────────────────────

    @Test
    fun `the cap goes UP, so the next device cannot pay it out again`() {
        val push = local(payable = 40.0).toPush()
        assertEquals(40.0, push.payableTotal, 0.0001)
        assertEquals(100.0, push.refundTotal, 0.0001)
    }

    @Test
    fun `a refund with no debt to settle travels with payable equal to its total`() {
        val push = local(payable = 100.0).toPush()
        assertEquals(push.refundTotal, push.payableTotal, 0.0001)
    }

    @Test
    fun `push then pull is the same refund, cap intact`() {
        // The round trip is the claim that matters: a capped refund that leaves this phone
        // and lands on another must still be capped when it gets there.
        val sent = local(payable = 40.0).toPush()
        val arrived = RefundDto(
            id = sent.id,
            saleId = sent.saleId,
            refundTotal = sent.refundTotal.toString(),
            payableTotal = sent.payableTotal.toString(),
            status = sent.status,
            createdAt = sent.createdAt,
            updatedAt = "2026-08-18T11:00:00.000Z",
        ).toRefund(bid, local = null)!!
        assertEquals(40.0, arrived.payableTotal, 0.0001)
        assertTrue(arrived.payableTotal < arrived.refundTotal)
    }
}
