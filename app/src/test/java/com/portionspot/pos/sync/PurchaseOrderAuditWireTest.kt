package com.portionspot.pos.sync

import com.portionspot.pos.data.AuditEntry
import com.portionspot.pos.data.PurchaseOrder
import com.portionspot.pos.data.purchaseOrderStatusFromWire
import com.portionspot.pos.data.purchaseOrderStatusToWire
import com.portionspot.pos.data.uuidOrNull
import com.portionspot.pos.sync.wire.AuditEntryDto
import com.portionspot.pos.sync.wire.PurchaseOrderDto
import com.portionspot.pos.sync.wire.toAuditEntry
import com.portionspot.pos.sync.wire.toPurchaseOrder
import com.portionspot.pos.sync.wire.toPush
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two ways the five newly-wired tables can go wrong WITHOUT anything reporting an
 * error — the failures that look like success.
 *
 *  1. `purchase_orders.status`. The cloud CHECK-constrains it to four values and this app
 *     writes two others. A violation fails the WHOLE upsert batch, so one bad order takes
 *     every other order in the push with it; and because the translation is lossy in the
 *     other direction, a careless pull turns a half-received order back into an un-received
 *     one and the shop books the same goods into stock twice.
 *
 *  2. `audit_entries.meta`. It is `jsonb` on the cloud and a JSON *string* on this side.
 *     Typing the field `String?` compiles perfectly and then fails at runtime the first
 *     time a real row comes down — a unit test is the only place that gets found early.
 */
class PurchaseOrderAuditWireTest {

    /** The exact CHECK on the live table, re-read from the database rather than recalled. */
    private val legalWireStatuses = setOf("draft", "sent", "received", "cancelled")

    /** Every status this app is known to write, including the two that are illegal up there. */
    private val localStatuses = listOf("draft", "placed", "partial", "received", "cancelled")

    // ── the trip UP: nothing illegal may leave this device ───────────────────────

    @Test
    fun everyLocalStatus_goesUpAsAValueTheCheckAllows() {
        // The property that actually matters. One illegal value does not fail its own row;
        // it fails the batch, and every other purchase order in the push disappears with it.
        for (s in localStatuses) {
            assertTrue(
                "'$s' went up as '${purchaseOrderStatusToWire(s)}', which the CHECK rejects",
                purchaseOrderStatusToWire(s) in legalWireStatuses
            )
        }
    }

    @Test
    fun placedAndPartial_bothCollapseToSent() {
        // Neither word exists on the shared schema. `sent` is what it calls "ordered, not
        // yet fully received", which is true of both.
        assertEquals("sent", purchaseOrderStatusToWire("placed"))
        assertEquals("sent", purchaseOrderStatusToWire("partial"))
    }

    @Test
    fun theStatusesBothSidesShare_travelUnchanged() {
        assertEquals("draft", purchaseOrderStatusToWire("draft"))
        assertEquals("received", purchaseOrderStatusToWire("received"))
        assertEquals("cancelled", purchaseOrderStatusToWire("cancelled"))
    }

    @Test
    fun anUnknownStatus_stillLeavesAsSomethingLegal() {
        // A value this code has never heard of is exactly the one that would be illegal,
        // so it must not travel as-is however honest that would be.
        assertTrue(purchaseOrderStatusToWire("half-here") in legalWireStatuses)
        assertTrue(purchaseOrderStatusToWire("") in legalWireStatuses)
    }

    // ── the trip DOWN: a partial order must stay partial ─────────────────────────

    @Test
    fun sent_landsOnPlaced_whenTheDeviceHasNoOpinion() {
        assertEquals("placed", purchaseOrderStatusFromWire("sent", null))
        assertEquals("placed", purchaseOrderStatusFromWire("sent", "placed"))
        assertEquals("placed", purchaseOrderStatusFromWire("sent", "draft"))
    }

    @Test
    fun sent_PRESERVES_aLocalPartial() {
        // ★ The whole reason the pull is handed the local row. `partial` went up as `sent`
        // because nothing else was legal; letting `sent` come back as `placed` would make a
        // half-received order look un-received, and the missing half could be received
        // again — real stock counted twice, with no error anywhere.
        assertEquals("partial", purchaseOrderStatusFromWire("sent", "partial"))
    }

    @Test
    fun theOtherThreeStatuses_comeBackAsThemselves() {
        assertEquals("draft", purchaseOrderStatusFromWire("draft", "placed"))
        assertEquals("received", purchaseOrderStatusFromWire("received", "partial"))
        assertEquals("cancelled", purchaseOrderStatusFromWire("cancelled", "draft"))
    }

    @Test
    fun anUnreadableWireStatus_keepsWhatTheDeviceAlreadyHad() {
        // A status we cannot read is not evidence the order went back to draft.
        assertEquals("partial", purchaseOrderStatusFromWire("in-transit", "partial"))
        assertEquals("draft", purchaseOrderStatusFromWire("in-transit", null))
    }

    // ── the round trip, through the real DTOs ────────────────────────────────────

    private fun po(status: String, supplierId: String? = null) = PurchaseOrder(
        id = "11111111-1111-4111-8111-111111111111",
        businessId = "22222222-2222-4222-8222-222222222222",
        ref = "PO-260811-0001",
        supplierId = supplierId,
        supplierName = "Acme Parts",
        status = status,
    )

    private fun wireRow(status: String) = PurchaseOrderDto(
        id = "11111111-1111-4111-8111-111111111111",
        ref = "PO-260811-0001",
        supplierName = "Acme Parts",
        status = status,
        updatedAt = "2026-08-11T10:00:00.000Z",
    )

    @Test
    fun aHalfReceivedOrder_survivesPushThenPull() {
        val local = po("partial")
        val wire = local.toPush()
        assertEquals("sent", wire.status)
        // …and comes home still knowing it is half received.
        val back = wireRow(wire.status).toPurchaseOrder(local.businessId, local)
        assertEquals("partial", back.status)
    }

    @Test
    fun aPlacedOrder_survivesPushThenPull() {
        val local = po("placed")
        val back = wireRow(local.toPush().status).toPurchaseOrder(local.businessId, local)
        assertEquals("placed", back.status)
    }

    @Test
    fun theLocalOnlyMoneyAndArrivalFields_survivetheMerge() {
        // None of these has a cloud column. A pull that replaced the row instead of
        // merging onto it would silently zero what the shop paid for the order.
        val local = po("partial").copy(
            cashPaid = 40.0,
            capitalPaid = 10.0,
            payableRemainder = 25.0,
            eta = 1_760_000_000_000L,
            arrivalPromptedAt = 1_760_000_100_000L,
        )
        val back = wireRow("sent").toPurchaseOrder(local.businessId, local)
        assertEquals(40.0, back.cashPaid, 1e-9)
        assertEquals(10.0, back.capitalPaid, 1e-9)
        assertEquals(25.0, back.payableRemainder, 1e-9)
        assertEquals(1_760_000_000_000L, back.eta)
        assertEquals(1_760_000_100_000L, back.arrivalPromptedAt)
    }

    // ── supplier_id is a uuid column and a free String on this side ──────────────

    @Test
    fun aSupplierIdThatIsNotAUuid_goesUpAsNull_ratherThanKillingTheBatch() {
        assertNull(po("draft", supplierId = "acme-parts").toPush().supplierId)
        assertNull(po("draft", supplierId = "").toPush().supplierId)
        assertNull(po("draft", supplierId = null).toPush().supplierId)
    }

    @Test
    fun arealSupplierUuid_isKept() {
        val id = "33333333-3333-4333-8333-333333333333"
        assertEquals(id, po("draft", supplierId = id).toPush().supplierId)
        assertEquals(id, uuidOrNull("  $id  "))
    }

    // ── audit_entries.meta is jsonb, not text ────────────────────────────────────

    private fun entry(meta: String?) = AuditEntry(
        id = "44444444-4444-4444-8444-444444444444",
        businessId = "22222222-2222-4222-8222-222222222222",
        action = "void_refund",
        entityType = "refund",
        entityId = "not-a-uuid-and-that-is-fine",
        summary = "Voided a \$12 refund",
        meta = meta,
        createdAt = 1_760_000_000_000L,
    )

    @Test
    fun meta_goesUpAsARealJsonObject_notAQuotedString() {
        // Encoded as a string the column would hold the TEXT of the JSON, and every reader
        // on the other side would have to un-quote it before it meant anything.
        val json = syncJson.encodeToString(listOf(entry("""{"amount":12,"by":"ryan"}""").toPush()))
        assertTrue("meta was encoded as a string, not jsonb: $json", json.contains("\"meta\":{"))
        assertTrue(json.contains("\"amount\":12"))
    }

    @Test
    fun meta_survivesTheFullRoundTrip() {
        // push → wire JSON → pull → local string, and the two ends must agree.
        val local = entry("""{"amount":12,"reason":"damaged","by":"ryan"}""")
        val wire = syncJson.encodeToString(listOf(local.toPush()))
        // Read it back exactly as the pull does: a jsonb object arriving in the DTO.
        val decoded = syncJson.decodeFromString<List<AuditEntryDto>>(wire.asPulledRows())
        val back = decoded.single().toAuditEntry(local.businessId)
        assertEquals(
            syncJson.parseToJsonElement(local.meta!!),
            syncJson.parseToJsonElement(back.meta!!)
        )
        assertEquals("void_refund", back.action)
        assertEquals("not-a-uuid-and-that-is-fine", back.entityId)
    }

    @Test
    fun anAuditRowWithNoMeta_staysNull_ratherThanBecomingTheStringNull() {
        assertNull(entry(null).toPush().meta)
        assertNull(entry("   ").toPush().meta)
        // And a jsonb NULL coming down reads as absent, not as the four letters "null".
        val dto = AuditEntryDto(
            id = "44444444-4444-4444-8444-444444444444",
            action = "stock_adjust",
            meta = null,
            createdAt = "2026-08-11T10:00:00.000Z",
        )
        assertNull(dto.toAuditEntry("biz").meta)
    }

    @Test
    fun aFreeTextNote_isWhatThisColumnActuallyHolds_andSurvivesUnchanged() {
        // Every meta this app writes is a human note, not JSON: "$12.50", "No line
        // changes", "qty 2 → 3". It travels as a jsonb STRING and must come home byte for
        // byte — no quotes gained, no number coerced.
        for (note in listOf("\$12.50", "No line changes", "qty 2 → 3; price 8.00 → 7.50")) {
            val push = entry(note).toPush()
            assertEquals(JsonPrimitive(note), push.meta)
            val wire = syncJson.encodeToString(listOf(push)).asPulledRows()
            val back = syncJson.decodeFromString<List<AuditEntryDto>>(wire).single()
                .toAuditEntry("biz")
            assertEquals(note, back.meta)
        }
    }

    @Test
    fun aNumericLookingNote_isNotSilentlyRewrittenAsANumber() {
        // "12.50" handed to a lenient JSON parser becomes the number 12.5 and comes home
        // as "12.5" — a shop's audit trail quietly editing itself.
        val push = entry("12.50").toPush()
        assertEquals(JsonPrimitive("12.50"), push.meta)
        val wire = syncJson.encodeToString(listOf(push)).asPulledRows()
        val back = syncJson.decodeFromString<List<AuditEntryDto>>(wire).single().toAuditEntry("biz")
        assertEquals("12.50", back.meta)
    }

    @Test
    fun aNoteThatMerelyStartsWithABrace_stillTravels() {
        // Shaped like JSON, isn't. Losing an audit entry over its punctuation would be the
        // worse bug, so it goes up as text.
        assertEquals(JsonPrimitive("{not really json}"), entry("{not really json}").toPush().meta)
    }

    @Test
    fun metaObject_isNotFlattenedIntoText_whenItCameFromTheWeb() {
        // The shape a web-authored row arrives in: a genuine JSON object in the column.
        val dto = AuditEntryDto(
            id = "55555555-5555-4555-8555-555555555555",
            action = "price_override",
            meta = JsonObject(mapOf("old" to JsonPrimitive(10.0), "new" to JsonPrimitive(8.5))),
            createdAt = "2026-08-11T10:00:00.000Z",
            updatedAt = "2026-08-11T10:00:00.000Z",
        )
        val local = dto.toAuditEntry("biz")
        val reparsed = syncJson.parseToJsonElement(local.meta!!) as JsonObject
        assertEquals(JsonPrimitive(10.0), reparsed["old"])
        assertEquals(JsonPrimitive(8.5), reparsed["new"])
    }

    /**
     * The push payload names `client_updated_at`; a PULLED row names `updated_at`. The two
     * DTOs are deliberately different shapes, so the round-trip test renames the one field
     * rather than pretending a push row can be decoded as a pull row.
     */
    private fun String.asPulledRows(): String = replace("client_updated_at", "updated_at")
}
