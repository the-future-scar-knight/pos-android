package com.portionspot.pos.sync

import com.portionspot.pos.auth.StaffPin
import com.portionspot.pos.auth.StaffSignIn
import com.portionspot.pos.auth.StaffSignInResult
import com.portionspot.pos.data.StaffMember
import com.portionspot.pos.sync.wire.StaffDto
import com.portionspot.pos.sync.wire.clampStaffRole
import com.portionspot.pos.sync.wire.toStaffMember
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The roster pull — and the one decision in it that no amount of looking at the app will
 * reveal is wrong.
 *
 * ★★ WHICH BUSINESS ID A STAFF ROW IS STAMPED WITH ★★
 *
 * A device has two ids for one shop: the `businesses` row uuid it generated on first run,
 * and the CLOUD `business_id` it adopts on first connect. Every other pull mapper in the
 * wire package is handed the LOCAL one, because local rows are keyed by it and nothing
 * about their data depends on which id it is.
 *
 * A staff row is the exception. `pin_hash` is PBKDF2 over a salt derived as
 * `SHA-256("PortionSpot POS pin v1:" + business_id)` — copied from the web's `pin.js` so
 * that ONE hash works on both clients — and the web salts with the shop's cloud id. Stamp
 * these rows with the device's local uuid instead and every hash this app derives disagrees
 * with every hash the web wrote. There is no exception, no log line, no failed request:
 * just a correct PIN refused forever on one side, looking exactly like the cashier
 * mistyping it.
 *
 * So the tests below do not assert on a string equality alone. They hash a PIN the way the
 * WEB would (under the cloud id), map the row both ways, and put the result through the
 * real sign-in path — because "the field holds the right value" and "the cashier can
 * actually open the till" are the same claim only if you check the second one.
 */
class StaffRosterWireTest {

    /** What the shared database calls this shop. The web salts every PIN with it. */
    private val cloudBid = "7a1f2c40-0b1e-4c3a-9d2b-5e6f70819a2b"

    /** What this phone called itself on first run, before it had ever seen a database. */
    private val localBid = "local-9f31c8e2-4d55-4d0a-b7aa-1c2d3e4f5a6b"

    /** A `pin_hash` exactly as the web would have written it for this shop. */
    private val webHash = StaffPin.hash("4821", cloudBid)

    private fun dto(
        id: String = "s1",
        name: String = "Tendai",
        username: String = "tendai",
        role: String = "cashier",
        active: Boolean = true,
        pinHash: String? = webHash,
        deleted: Boolean = false,
    ) = StaffDto(
        id = id,
        name = name,
        username = username,
        role = role,
        active = active,
        pinHash = pinHash,
        permissions = buildJsonObject { put("give_discounts", false) },
        updatedAt = "2026-08-11T09:15:00.000Z",
        deleted = deleted,
    )

    // ── the business id ───────────────────────────────────────────────────

    @Test
    fun `a pulled staff row is stamped with the CLOUD business id`() {
        val row = dto().toStaffMember(cloudBid)
        assertEquals(cloudBid, row.businessId)
    }

    @Test
    fun `a PIN the web wrote verifies after the pull`() {
        // The whole point of sharing the column: the owner creates a cashier on the web,
        // the cashier walks up to the phone, and it just works.
        val row = dto().toStaffMember(cloudBid)
        assertTrue(StaffSignIn.verify(row, "4821", cloudBid) is StaffSignInResult.Ok)
        assertTrue(StaffSignIn.attempt(listOf(row), "tendai", "4821", cloudBid) is StaffSignInResult.Ok)
    }

    /**
     * The regression this file exists for. Nothing about the mis-stamped row LOOKS wrong —
     * the name, username, role, grants and hash are all present and correct — and the only
     * symptom is the cashier being told their PIN is wrong.
     */
    @Test
    fun `stamping the LOCAL id instead refuses a correct PIN with no other sign of trouble`() {
        val wrong = dto().toStaffMember(localBid)

        // Every field a person would think to check is fine.
        assertEquals("Tendai", wrong.name)
        assertEquals("tendai", wrong.username)
        assertEquals(webHash, wrong.pinHash)
        assertTrue(wrong.active)

        // And the till still refuses them.
        assertEquals(StaffSignInResult.WrongPin, StaffSignIn.verify(wrong, "4821", cloudBid))
        assertEquals(StaffSignInResult.WrongPin, StaffSignIn.verify(wrong, "4821", localBid))
    }

    @Test
    fun `the two ids really do derive different digests`() {
        // Belt and braces on the claim above: if these were ever equal the test would pass
        // for the wrong reason and the guard would be worthless.
        assertFalse(StaffPin.hash("4821", cloudBid) == StaffPin.hash("4821", localBid))
    }

    @Test
    fun `re-stamping an existing local row moves it to the cloud id`() {
        // A device upgraded from a build that filed staff under the local id must be healed
        // by the next pull, not left holding rows nobody can sign in with.
        val stale = StaffMember(id = "s1", businessId = localBid, name = "Tendai", pinHash = webHash)
        val healed = dto().toStaffMember(cloudBid, stale)
        assertEquals(cloudBid, healed.businessId)
        assertTrue(StaffSignIn.verify(healed, "4821", cloudBid) is StaffSignInResult.Ok)
    }

    // ── the rest of the mapping ───────────────────────────────────────────

    @Test
    fun `a row that arrived from the cloud has nothing to send back`() {
        assertFalse(dto().toStaffMember(cloudBid).pendingSync)
    }

    @Test
    fun `permissions survive as raw text and parse back to the same grants`() {
        val row = dto().toStaffMember(cloudBid)
        val perms = com.portionspot.pos.auth.Permissions.fromJsonString(row.permissions)
        assertFalse(perms.allows(com.portionspot.pos.auth.Capability.GIVE_DISCOUNTS))
    }

    @Test
    fun `a blank PIN column reads as no PIN rather than an empty one`() {
        assertNull(dto(pinHash = "").toStaffMember(cloudBid).pinHash)
        assertNull(dto(pinHash = null).toStaffMember(cloudBid).pinHash)
    }

    @Test
    fun `an unrecognised role narrows to cashier`() {
        // A mistake here must never hand someone the admin's till.
        assertEquals("cashier", clampStaffRole("supervisor"))
        assertEquals("cashier", clampStaffRole(""))
        assertEquals("cashier", clampStaffRole(null))
        assertEquals("admin", clampStaffRole(" Admin "))
        assertEquals("manager", clampStaffRole("manager"))
    }

    @Test
    fun `a manager is not an admin on this side`() {
        // Android's model is admin-or-not, so `manager` lands on the cashier side and is
        // shaped by their grants instead. That is the safe direction: an unexpected role
        // can only ever mean fewer powers.
        val row = dto(role = "manager").toStaffMember(cloudBid)
        val user = com.portionspot.pos.auth.PosUser(row.id, row.username, row.role, row.name)
        assertFalse(user.isAdmin)
    }

    @Test
    fun `the cursor advances to the row's updated_at`() {
        assertEquals("2026-08-11T09:15:00.000Z", dto().cursorStamp())
        // A row written before the server trigger existed must not drag the cursor FORWARD
        // past rows this device has not seen.
        assertEquals(IsoTime.EPOCH, dto().copy(updatedAt = null).cursorStamp())
    }

    @Test
    fun `the wire shape decodes the columns the shared table actually has`() {
        val json = Json { ignoreUnknownKeys = true }
        val decoded = json.decodeFromString<List<StaffDto>>(
            """[{"id":"s9","business_id":"$cloudBid","name":"Chipo","username":"chipo",
                 "role":"admin","active":true,"pin_hash":"pbkdf2${'$'}210000${'$'}abc",
                 "permissions":{"refunds":true},"updated_at":"2026-08-11T09:15:00.000Z",
                 "deleted":false,"client_updated_at":"2026-08-11T09:14:00.000Z"}]"""
        )
        val row = decoded.single().toStaffMember(cloudBid)
        assertEquals("s9", row.id)
        assertEquals("chipo", row.username)
        assertEquals("admin", row.role)
        assertEquals("pbkdf2\$210000\$abc", row.pinHash)
    }
}
