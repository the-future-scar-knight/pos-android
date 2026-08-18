package com.portionspot.pos.sync

import com.portionspot.pos.data.ShopPolicy
import com.portionspot.pos.data.ShopPolicyPlan
import com.portionspot.pos.data.ShopPolicyWireValues
import com.portionspot.pos.data.planShopPolicySync
import com.portionspot.pos.sync.wire.BusinessPolicyDto
import com.portionspot.pos.sync.wire.toPatch
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shop's money RULES going two ways over the shared `businesses` row.
 *
 * ── WHAT THESE TESTS ARE DEFENDING ────────────────────────────────────────────
 *
 * The per-line discount cap, its PIN-gate percentage and the cash-variance note threshold
 * used to live only in one phone's key/value settings. That made "the shop's discount cap"
 * mean "whatever the handset in your hand was last told": the owner raising the cap on his
 * phone left the cashier's till refusing at the old one, and LOWERING it left her till
 * allowing the larger discount on every line of every sale until somebody noticed the
 * discount report. No error, no log line, and the two phones each believing they were
 * enforcing the shop's rule.
 *
 * The two failure modes these tests exist for are the ones that are invisible from the
 * app: a null read as a zero (which in this app means NO LIMIT, so it takes the ceiling
 * off entirely), and a pull that silently reverts an edit the owner just made by hand.
 */
class ShopPolicyWireTest {

    /** What this till currently enforces: $2 off a line at most, PIN above 5%, explain
     *  a drawer that misses by more than a dollar. */
    private val local = ShopPolicy(
        maxItemDiscount = 2.0,
        discountThresholdPct = 5.0,
        varianceNoteThreshold = 1.0,
    )

    // ── the decision ──────────────────────────────────────────────────────

    @Test
    fun `a device that has never touched the policy adopts the shop's`() {
        val plan = planShopPolicySync(
            local = local,
            localChangedAt = 0L,
            wire = ShopPolicyWireValues(5.0, 10.0, 2.0),
            wireChangedAt = 1_000L,
        )
        assertEquals(ShopPolicyPlan.Adopt(ShopPolicy(5.0, 10.0, 2.0)), plan)
    }

    @Test
    fun `a NULL field keeps the local value and is NEVER read as zero`() {
        // ★ The one that would quietly disarm the till. maxItemDiscount = 0 means NO LIMIT
        // in this app, so reading an unstated column as zero would take the per-line
        // ceiling off every cashier's phone in the shop.
        val plan = planShopPolicySync(
            local = local,
            localChangedAt = 0L,
            wire = ShopPolicyWireValues(maxItemDiscount = null, discountThresholdPct = 12.0),
            wireChangedAt = 1_000L,
        )
        val adopted = (plan as ShopPolicyPlan.Adopt).policy
        assertEquals(2.0, adopted.maxItemDiscount, 0.0001)   // kept, not zeroed
        assertEquals(12.0, adopted.discountThresholdPct, 0.0001)
        assertEquals(1.0, adopted.varianceNoteThreshold, 0.0001)
    }

    @Test
    fun `a row that states nothing at all changes nothing`() {
        val plan = planShopPolicySync(local, 0L, ShopPolicyWireValues(), 1_000L)
        assertEquals(ShopPolicyPlan.Settled, plan)
    }

    @Test
    fun `an edit made on this phone is pushed, not silently reverted`() {
        // Pull-only was the tempting shape and this is why it is wrong: the owner
        // administers from his handset, and a pull-only design would put the old cap back
        // on the next pass with nothing to tell him it had happened.
        val edited = local.copy(maxItemDiscount = 5.0)
        val plan = planShopPolicySync(
            local = edited,
            localChangedAt = 2_000L,
            wire = ShopPolicyWireValues(2.0, 5.0, 1.0),
            wireChangedAt = 1_000L,
        )
        assertEquals(ShopPolicyPlan.Push(edited), plan)
    }

    @Test
    fun `the later edit wins, whichever side made it`() {
        val fromBrowser = ShopPolicyWireValues(9.0, 5.0, 1.0)
        // Browser edit at t=3000, phone edit at t=2000 → the browser's stands.
        assertTrue(planShopPolicySync(local, 2_000L, fromBrowser, 3_000L) is ShopPolicyPlan.Adopt)
        // Phone edit at t=4000 → the phone's goes up instead.
        assertTrue(planShopPolicySync(local, 4_000L, fromBrowser, 3_000L) is ShopPolicyPlan.Push)
    }

    @Test
    fun `a push settles after one pass instead of ping-ponging`() {
        val edited = local.copy(maxItemDiscount = 5.0)
        assertEquals(ShopPolicyPlan.Push(edited), planShopPolicySync(edited, 2_000L, ShopPolicyWireValues(2.0, 5.0, 1.0), 1_000L))
        // The server stamps updated_at = now() on the PATCH, so the next pass sees the
        // wire ahead and adopts back the values it just sent. Identical → nothing written.
        assertEquals(
            ShopPolicyPlan.Settled,
            planShopPolicySync(edited, 2_000L, ShopPolicyWireValues(5.0, 5.0, 1.0), 9_000L),
        )
    }

    @Test
    fun `agreement writes nothing at all`() {
        // A sync pass runs about once a minute in the foreground; adopting an identical
        // policy each time would rewrite three settings rows a minute for the life of the
        // till.
        assertEquals(
            ShopPolicyPlan.Settled,
            planShopPolicySync(local, 1_000L, ShopPolicyWireValues(2.0, 5.0, 1.0), 5_000L),
        )
    }

    @Test
    fun `no row means no decision`() {
        // A failed read must not change a money rule.
        assertEquals(ShopPolicyPlan.Settled, planShopPolicySync(local, 9_000L, null, 0L))
    }

    // ── the wire shapes ───────────────────────────────────────────────────

    @Test
    fun `the pull decodes numerics off a real PostgREST row`() {
        val decoded = Json { ignoreUnknownKeys = true }.decodeFromString<List<BusinessPolicyDto>>(
            """[{"id":"b-1","max_item_discount":"5.00","discount_threshold":"10.0",
                 "variance_note_threshold":"2.50","updated_at":"2026-08-18T10:00:00.000Z",
                 "bank_account_number":"0123456789","vat_number":"VAT-77"}]"""
        )
        val row = decoded.single()
        val values = row.toPolicyValues()
        assertEquals(5.0, values.maxItemDiscount!!, 0.0001)
        assertEquals(10.0, values.discountThresholdPct!!, 0.0001)
        assertEquals(2.5, values.varianceNoteThreshold!!, 0.0001)
        assertEquals("2026-08-18T10:00:00.000Z", row.cursorStamp())
    }

    @Test
    fun `a missing column stays null rather than becoming a number`() {
        val row = Json { ignoreUnknownKeys = true }
            .decodeFromString<List<BusinessPolicyDto>>("""[{"id":"b-1"}]""").single()
        val values = row.toPolicyValues()
        assertEquals(null, values.maxItemDiscount)
        assertEquals(null, values.discountThresholdPct)
        assertEquals(null, values.varianceNoteThreshold)
        // No server clock either, so a device that HAS changed the policy wins over it.
        assertEquals(IsoTime.EPOCH, row.cursorStamp())
    }

    @Test
    fun `the PATCH body carries THREE columns and the client clock, and nothing else`() {
        // ★★ THE HAZARD. PostgREST resolves an upsert of a partial row by NULLING every
        // column it was not handed. This body goes onto a row that also holds the shop's
        // bank account number, EcoCash merchant code, VAT number, receipt header/footer,
        // logo and seven payment-method flags. If this body ever grows a full-row shape,
        // or is ever sent through `upsert` instead of `updateById`, all of that is
        // destroyed on a 2xx with the sync reporting success.
        val json = Json.encodeToString(
            com.portionspot.pos.sync.wire.BusinessPolicyPatchDto.serializer(),
            local.toPatch(changedAt = 1_755_000_000_000L),
        )
        val keys = Json.parseToJsonElement(json).let {
            (it as kotlinx.serialization.json.JsonObject).keys
        }
        assertEquals(
            setOf(
                "max_item_discount",
                "discount_threshold",
                "variance_note_threshold",
                "client_updated_at",
            ),
            keys,
        )
        // Named explicitly because these are the ones that would be lost.
        assertFalse(keys.contains("bank_account_number"))
        assertFalse(keys.contains("vat_number"))
        assertFalse(keys.contains("receipt_header"))
        assertFalse(keys.contains("id"))
        // ★ `updated_at` is the SERVER's. A device that stamped it could park the row in
        // the future and every other phone would skip everything behind that cursor.
        assertFalse(keys.contains("updated_at"))
    }

    @Test
    fun `the patch sends the figures the till is actually enforcing`() {
        val patch = local.copy(maxItemDiscount = 5.0).toPatch(changedAt = 1_755_000_000_000L)
        assertEquals(5.0, patch.maxItemDiscount, 0.0001)
        assertEquals(5.0, patch.discountThreshold, 0.0001)
        assertEquals(1.0, patch.varianceNoteThreshold, 0.0001)
        assertEquals(IsoTime.toIso(1_755_000_000_000L), patch.clientUpdatedAt)
    }
}
