package com.portionspot.pos.sync

import com.portionspot.pos.data.AppNotification
import com.portionspot.pos.data.Expense
import com.portionspot.pos.data.StaffRequest
import com.portionspot.pos.sync.wire.ExpenseDto
import com.portionspot.pos.sync.wire.StaffRequestDto
import com.portionspot.pos.sync.wire.toPush
import com.portionspot.pos.sync.wire.toExpense
import com.portionspot.pos.sync.wire.toStaffRequest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The alert feed and the approval queue, on the wire for the first time.
 *
 * ══ WHY THESE TESTS EXIST AT ALL ══
 * Both features shipped months ago and neither has ever left a phone. `notifications` had a
 * complete pull that nothing called and a push DTO nothing used; `staff_requests` had its DTOs
 * DELETED, with a note saying the cloud table did not exist — true then, false now. Nothing
 * about either was wrong in a way a running app could show you, because no row was ever sent.
 *
 * So the risk here is not arithmetic, it is TYPES AND KEYS: a timestamp declared as the wrong
 * kind, or a conflict target that coalesces two rows that are not the same row. Neither shows
 * up until the first real push, and a wrong one fails the WHOLE batch.
 */
class AlertWireTest {

    // ─────────────────────── notifications: the four instants ───────────────────────

    @Test
    fun everyNotificationInstantIsAnIsoStringOnTheWire() {
        // ★ THE BUG THIS PINS. `event_at` and `read_at` were declared BIGINT epoch millis
        // while the live table has both as TIMESTAMPTZ. A pull would have THROWN decoding
        // "2026-08-18T17:40:49Z" into a Long — taking the whole notifications pass with it —
        // and a push would have been refused as invalid input for a timestamp.
        val json = syncJson.encodeToString(
            AppNotification(
                id = "n1", businessId = "biz", category = "inventory", severity = "warn",
                title = "Low stock", body = "Cooking oil is down to 2",
                dedupeKey = "lowstock:oil", eventAt = 1_755_540_049_000L,
                createdAt = 1_755_540_049_000L, updatedAt = 1_755_540_050_000L,
            ).toNotificationPush()
        )
        // Quoted => a string. An unquoted number here is the rejected push.
        assertTrue(json, json.contains("\"event_at\":\""))
        assertTrue(json, json.contains("\"created_at\":\""))
        assertTrue(json, json.contains("\"updated_at\":\""))
    }

    @Test
    fun aPulledNotificationReadsItsInstantsBackAsMillis() {
        val dto = syncJson.decodeFromString<NotificationDto>(
            """
            {"local_id":"n1","category":"inventory","severity":"warn","title":"Low stock",
             "body":"Cooking oil is down to 2","dedupe_key":"lowstock:oil","audience":"admin",
             "event_at":"2026-08-18T17:40:49Z","created_at":"2026-08-18T17:40:49Z",
             "updated_at":"2026-08-18T17:40:50Z","occurrence":3,"deleted":false}
            """.trimIndent()
        )
        val row = dto.toNotification("biz", null)
        assertEquals(1_787_074_849_000L, row.eventAt)
        assertEquals("lowstock:oil", row.dedupeKey)
        assertEquals("warn", row.severity)
        assertFalse(row.pendingSync)
    }

    @Test
    fun readStateNeitherTravelsNorIsOverwritten() {
        // `notifications.read_at` is ONE flag for the whole shop, so a shared read-state
        // means the owner clearing his feed clears the cashier's. Per-person read state has
        // its own cloud table (`notification_reads`) and no local table yet, so until it does
        // the flag stays on the device that did the reading — in BOTH directions.
        val local = AppNotification(
            id = "n1", businessId = "biz", category = "inventory", title = "Low stock",
            body = "", dedupeKey = "lowstock:oil", readAt = 1_755_540_099_000L,
        )
        val dto = syncJson.decodeFromString<NotificationDto>(
            """{"local_id":"n1","dedupe_key":"lowstock:oil","read_at":null,
                "updated_at":"2026-08-18T17:40:50Z"}""".trimIndent()
        )
        assertEquals(1_755_540_099_000L, dto.toNotification("biz", local).readAt)
        assertFalse(syncJson.encodeToString(local.toNotificationPush()).contains("read_at"))
    }

    @Test
    fun aPullKeepsThisDevicesOwnRowIdAndItsOwnPushedAt() {
        // pushedAt records whether THIS phone already raised a heads-up. It is device state,
        // not shop state: overwriting it makes one phone silent because another phone buzzed.
        val local = AppNotification(
            id = "local-uuid", businessId = "biz", category = "inventory", title = "Low stock",
            body = "", dedupeKey = "lowstock:oil", pushedAt = 1_755_540_060_000L,
        )
        val merged = syncJson.decodeFromString<NotificationDto>(
            """{"local_id":"other-phones-uuid","dedupe_key":"lowstock:oil",
                "title":"Low stock","body":"now 1 left","updated_at":"2026-08-18T17:41:00Z"}"""
        ).toNotification("biz", local)
        assertEquals("local-uuid", merged.id)
        assertEquals(1_755_540_060_000L, merged.pushedAt)
        assertEquals("now 1 left", merged.body)   // content IS shared
    }

    // ─────────────────────── staff_requests: one question, one row ───────────────────────

    @Test
    fun aRequestSurvivesTheRoundTrip() {
        val raised = StaffRequest(
            id = "11111111-2222-3333-4444-555555555555",
            businessId = "biz", type = "discount", targetType = "sale", targetId = "s1",
            targetName = "3 items · $42.00", amount = 5.0, note = "regular customer",
            requestedBy = "cashier-uuid", requestedByName = "Tino",
            createdAt = 1_755_540_049_000L, updatedAt = 1_755_540_049_000L,
        )
        val wire = syncJson.encodeToString(raised.toPush().copy(businessId = "cloud-biz"))
        val back = syncJson.decodeFromString<StaffRequestDto>(wire).toStaffRequest("biz", raised)
        assertEquals("discount", back.type)
        assertEquals(5.0, back.amount!!, 1e-9)
        assertEquals("Tino", back.requestedByName)
        assertEquals("pending", back.status)
        assertFalse(back.pendingSync)
    }

    @Test
    fun theOwnersDecisionComesDownToTheCashier() {
        // The entire point of the feature. Before this the answer never left the admin phone.
        val mine = StaffRequest(
            id = "11111111-2222-3333-4444-555555555555",
            businessId = "biz", type = "discount", updatedAt = 1_000L,
        )
        val decided = syncJson.decodeFromString<StaffRequestDto>(
            """{"id":"11111111-2222-3333-4444-555555555555","type":"discount",
                "status":"approved","decided_by":"admin-uuid","decided_by_name":"Ryan",
                "decided_at":"2026-08-18T17:45:00Z","updated_at":"2026-08-18T17:45:00Z"}"""
        ).toStaffRequest("biz", mine)
        assertEquals("approved", decided.status)
        assertEquals("Ryan", decided.decidedByName)
        assertEquals(1_787_075_100_000L, decided.decidedAt)
    }

    @Test
    fun anApprovalAlreadySpentIsNeverUnSpent() {
        // `applied` means the discount was taken or the void performed. The goods are out of
        // the door; a wire row arriving afterwards cannot put them back on the shelf.
        val consumed = StaffRequest(
            id = "11111111-2222-3333-4444-555555555555",
            businessId = "biz", type = "discount", status = "approved", applied = true,
            updatedAt = 1_000L,
        )
        val stale = syncJson.decodeFromString<StaffRequestDto>(
            """{"id":"11111111-2222-3333-4444-555555555555","type":"discount",
                "status":"approved","applied":false,"updated_at":"2026-08-18T17:45:00Z"}"""
        ).toStaffRequest("biz", consumed)
        assertTrue(stale.applied)
    }

    @Test
    fun aRequestWithNoTypeStillReachesTheOwner() {
        // `type` is NOT NULL on the cloud and free-form here, and a rejected batch fails
        // WHOLE — so one malformed row must not strand every other cashier's question.
        val out = StaffRequest(id = "id", businessId = "biz", type = "  ").toPush()
        assertEquals("other", out.type)
        assertEquals("pending", out.status)
    }

    @Test
    fun blanksGoUpAsNullsRatherThanEmptyStrings() {
        // An empty string is a value; null is the absence of one. Sending "" makes an unnamed
        // target read as a target named nothing on every screen that renders it.
        val out = StaffRequest(
            id = "id", businessId = "biz", type = "void",
            targetName = "", note = "  ", requestedByName = "",
        ).toPush()
        assertNull(out.targetName)
        assertNull(out.note)
        assertNull(out.requestedByName)
    }

    // ─────────────────── expenses: the approval that had nowhere to live ───────────────────

    private fun pending(id: String = "e1") = Expense(
        id = id, businessId = "biz", category = "Utilities", amount = 5.0,
        date = "2026-08-19", status = "pending",
        submittedBy = "cashier-uuid", submittedByName = "Tino",
        createdAt = 1_787_074_849_000L, updatedAt = 1_787_074_849_000L,
    )

    @Test
    fun aPendingExpenseArrivesPendingAndDoesNotApproveItself() {
        // ★ THE BUG, ON TWO REAL PHONES, 19 AUG. The cashier posted an expense; it reached
        // the owner's phone already APPROVED — synthesised, because the shared table had no
        // status column to read — and his net profit dropped for a cost he had not agreed
        // to, while her copy still said pending. Two phones, one shop, different profit.
        val wire = syncJson.encodeToString(pending().toPush().copy(businessId = "cloud-biz"))
        val landed = syncJson.decodeFromString<ExpenseDto>(wire).toExpense("biz", null)
        assertEquals("pending", landed.status)
        assertNull(landed.approvedAt)
        assertNull(landed.postedAt)
        assertEquals("Tino", landed.submittedByName)
    }

    @Test
    fun theOwnersApprovalReachesTheCashiersPhone() {
        val approved = pending().copy(
            status = "approved", approvedBy = "admin-uuid", approvedByName = "Ryan",
            approvedAt = 1_787_075_100_000L, postedAt = 1_787_075_100_000L,
            updatedAt = 1_787_075_100_000L,
        )
        val wire = syncJson.encodeToString(approved.toPush().copy(businessId = "cloud-biz"))
        val onHerPhone = syncJson.decodeFromString<ExpenseDto>(wire).toExpense("biz", pending())
        assertEquals("approved", onHerPhone.status)
        assertEquals("Ryan", onHerPhone.approvedByName)
        assertEquals(1_787_075_100_000L, onHerPhone.approvedAt)
    }

    @Test
    fun aRowWithNoStatusIsStillReadAsApprovedAndPosted() {
        // The web has no approval step and writes no status. Landing it as `pending` would
        // withdraw a cost somebody has already paid from the books and file it in a queue
        // for a decision nobody remembers making. This is the OLD behaviour, kept.
        val fromTheWeb = syncJson.decodeFromString<ExpenseDto>(
            """{"id":"e9","category":"Rent","amount":"120.0","date":"2026-08-19",
                "updated_at":"2026-08-18T17:45:00Z"}"""
        ).toExpense("biz", null)
        assertEquals("approved", fromTheWeb.status)
        assertEquals(1_787_075_100_000L, fromTheWeb.approvedAt)
        assertEquals(1_787_075_100_000L, fromTheWeb.postedAt)
    }

    @Test
    fun aReversalToPendingClearsTheApprovalInstantsRatherThanStrandingThem() {
        // Per-field fallback would leave a stale approvedAt on a row now marked pending —
        // an expense approved by nobody, at a time. Worse than either state on its own.
        val locallyApproved = pending().copy(
            status = "approved", approvedBy = "admin-uuid", approvedByName = "Ryan",
            approvedAt = 1_787_075_100_000L, postedAt = 1_787_075_100_000L,
        )
        val reverted = syncJson.decodeFromString<ExpenseDto>(
            """{"id":"e1","status":"pending","amount":"5.0","date":"2026-08-19",
                "updated_at":"2026-08-18T18:00:00Z"}"""
        ).toExpense("biz", locallyApproved)
        assertEquals("pending", reverted.status)
        assertNull(reverted.approvedAt)
        assertNull(reverted.postedAt)
        assertNull(reverted.approvedByName)
    }

    @Test
    fun theFundingSplitAndTheRecurrenceNeverTravel() {
        // A cash portion means "out of THIS drawer" and has no meaning on another device;
        // the money itself reaches the other phones as `cash_movements`. A pull must not
        // touch either, or the shop's cash-on-hand stops matching the cash in it.
        val local = pending().copy(
            cashPortion = 5.0, payablePortion = 0.0, capitalPortion = 0.0,
            recurring = true, recurrencePeriod = "monthly",
        )
        val pulled = syncJson.decodeFromString<ExpenseDto>(
            """{"id":"e1","status":"approved","amount":"5.0","date":"2026-08-19",
                "updated_at":"2026-08-18T18:00:00Z"}"""
        ).toExpense("biz", local)
        assertEquals(5.0, pulled.cashPortion, 1e-9)
        assertTrue(pulled.recurring)
        assertEquals("monthly", pulled.recurrencePeriod)
    }

    @Test
    fun thePushAlwaysNamesTheStatus() {
        // The cloud column defaults to `approved` for the web's benefit. A phone that
        // omitted the field would have every pending expense arrive already approved.
        assertTrue(syncJson.encodeToString(pending().toPush()).contains("\"status\":\"pending\""))
    }
}
