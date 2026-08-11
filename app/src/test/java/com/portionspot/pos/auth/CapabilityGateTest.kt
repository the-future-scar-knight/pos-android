package com.portionspot.pos.auth

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate itself. [PermissionRefreshTest] pins how a revocation REACHES the phone; this
 * pins what the phone then does with it — the single rule every `can(...)` in the app
 * funnels through:
 *
 *     allowed = isAdmin OR (NOT shopLocked AND staffPermitted)
 *
 * Three properties matter and each is a real failure mode:
 *
 *  - ADMIN BYPASS. The owner must never be gated out of their own shop. Every action gate
 *    added to the ViewModel leans on this, and local (phone-only) mode runs as an admin
 *    against the same database, so a regression here bricks the till for its owner.
 *  - LOCK BEATS GRANT. A per-staff `true` must not out-vote a shop-wide lock. A permission
 *    that can be widened from the row it is supposed to be constrained by is not a gate.
 *  - FAIL CLOSED. Anything the map does not say must resolve to a DENY (or to the stated
 *    cashier default), never to an allow, and never to an exception thrown mid-sale.
 */
class CapabilityGateTest {

    private fun perms(vararg pairs: Pair<Capability, Boolean>) = Permissions(pairs.toMap())

    // ── Admin bypass ──────────────────────────────────────────────────────

    @Test
    fun `admin holds every capability with an empty permission map`() {
        for (cap in Capability.entries) {
            assertTrue(
                "admin denied ${cap.key}",
                isCapabilityAllowed(isAdmin = true, permissions = Permissions.EMPTY, cap = cap)
            )
        }
    }

    @Test
    fun `admin bypasses an explicit per-staff denial`() {
        for (cap in Capability.entries) {
            assertTrue(
                "admin denied ${cap.key} by its own row",
                isCapabilityAllowed(true, perms(cap to false), cap)
            )
        }
    }

    /** ★ The owner-lockout guard. A shop-wide lock is for everyone BUT the admin — an owner
     *  who locks refunds shop-wide must still be able to refund. */
    @Test
    fun `admin bypasses every shop-wide lock`() {
        val allLocks = Capability.entries.toSet()
        for (cap in Capability.entries) {
            assertTrue(
                "admin locked out of ${cap.key}",
                isCapabilityAllowed(true, perms(cap to false), cap, shopLocks = allLocks)
            )
        }
    }

    // ── Lock beats grant ──────────────────────────────────────────────────

    @Test
    fun `a shop lock overrides a granted capability for a cashier`() {
        val locked = Capability.entries.filter { it.shopLockColumn != null }
        assertTrue("no lockable capabilities to test", locked.isNotEmpty())
        for (cap in locked) {
            assertTrue(
                "${cap.key} should be allowed when unlocked",
                isCapabilityAllowed(false, perms(cap to true), cap)
            )
            assertFalse(
                "${cap.key} granted past its shop lock",
                isCapabilityAllowed(false, perms(cap to true), cap, shopLocks = setOf(cap))
            )
        }
    }

    @Test
    fun `a lock on one capability does not leak onto another`() {
        assertFalse(
            isCapabilityAllowed(
                false, perms(Capability.PROCESS_REFUNDS to true),
                Capability.PROCESS_REFUNDS, setOf(Capability.PROCESS_REFUNDS)
            )
        )
        assertTrue(
            isCapabilityAllowed(
                false, perms(Capability.GIVE_DISCOUNTS to true),
                Capability.GIVE_DISCOUNTS, setOf(Capability.PROCESS_REFUNDS)
            )
        )
    }

    // ── Fail closed ───────────────────────────────────────────────────────

    /** The `?: false` tail of [Permissions.allows] exists so a capability added to the enum
     *  but forgotten in the defaults DENIES instead of throwing in the middle of a sale.
     *  This asserts the invariant that keeps that tail unreachable in the first place. */
    @Test
    fun `every capability has a stated cashier default`() {
        for (cap in Capability.entries) {
            assertTrue(
                "${cap.key} is missing from CASHIER_DEFAULTS — it would silently fail closed",
                Permissions.CASHIER_DEFAULTS.containsKey(cap)
            )
        }
    }

    @Test
    fun `a silent map falls back to the cashier default, never to allow`() {
        for (cap in Capability.entries) {
            val expected = Permissions.CASHIER_DEFAULTS.getValue(cap)
            val actual = isCapabilityAllowed(false, Permissions.EMPTY, cap)
            assertTrue("${cap.key} resolved against the wrong default", expected == actual)
        }
    }

    /** The money-sensitive grants are OFF for a cashier nobody has configured. */
    @Test
    fun `money-sensitive capabilities are denied by default`() {
        val mustBeOff = listOf(
            Capability.VOID_SALES,
            Capability.PROCESS_REFUNDS,
            Capability.EDIT_RECEIPTS,
            Capability.GIVE_DISCOUNTS,
            Capability.PRICE_OVERRIDE,
            Capability.MANAGE_EXPENSES_ORDERS,
            Capability.MANAGE_STAFF,
            Capability.VIEW_REPORTS,
        )
        for (cap in mustBeOff) {
            assertFalse(
                "${cap.key} is granted to an unconfigured cashier",
                isCapabilityAllowed(false, Permissions.EMPTY, cap)
            )
        }
    }

    @Test
    fun `an unknown key grants nothing`() {
        val p = Permissions.fromKeyMap(mapOf("become_admin" to true, "refunds_maybe" to true))
        assertTrue(p.granted.isEmpty())
        assertFalse(isCapabilityAllowed(false, p, Capability.PROCESS_REFUNDS))
        assertNull(Capability.fromKey("become_admin"))
    }

    // ── Permissions.fromJson — the restrictive merge ──────────────────────

    /**
     * One row can carry the same capability under BOTH spellings: this app writes
     * `process_refunds` AND `refunds`, the web writes only `refunds`. When they disagree —
     * an older web write saying true beside a newer snake_case false, or the reverse — the
     * RESTRICTIVE value has to win. Guessing permissively hands a cashier a capability the
     * owner revoked; guessing the other way just makes them ask.
     */
    @Test
    fun `disagreeing spellings resolve to the restrictive value`() {
        val webTrueAppFalse = buildJsonObject {
            put("refunds", true)
            put("process_refunds", false)
        }
        assertFalse(Permissions.fromJson(webTrueAppFalse).allows(Capability.PROCESS_REFUNDS))

        // Order must not matter: the merge is an AND, not a last-writer-wins.
        val appFalseWebTrue = buildJsonObject {
            put("process_refunds", false)
            put("refunds", true)
        }
        assertFalse(Permissions.fromJson(appFalseWebTrue).allows(Capability.PROCESS_REFUNDS))
    }

    @Test
    fun `agreeing spellings keep their value`() {
        val bothTrue = buildJsonObject {
            put("discounts", true)
            put("give_discounts", true)
        }
        assertTrue(Permissions.fromJson(bothTrue).allows(Capability.GIVE_DISCOUNTS))

        val bothFalse = buildJsonObject {
            put("discounts", false)
            put("give_discounts", false)
        }
        assertFalse(Permissions.fromJson(bothFalse).allows(Capability.GIVE_DISCOUNTS))
    }

    @Test
    fun `either spelling alone is understood`() {
        // The web's vocabulary…
        assertTrue(Permissions.fromJson(buildJsonObject { put("credit", true) })
            .allows(Capability.SELL_ON_CREDIT))
        // …and this app's, for the same capability.
        assertTrue(Permissions.fromJson(buildJsonObject { put("sell_on_credit", true) })
            .allows(Capability.SELL_ON_CREDIT))
        assertFalse(Permissions.fromJson(buildJsonObject { put("priceOverride", false) })
            .allows(Capability.PRICE_OVERRIDE))
    }

    @Test
    fun `a null or empty object grants nothing beyond the defaults`() {
        assertEquals(Permissions.EMPTY, Permissions.fromJson(null))
        assertTrue(Permissions.fromJson(JsonObject(emptyMap())).granted.isEmpty())
        assertFalse(Permissions.fromJson(null).allows(Capability.PROCESS_REFUNDS))
    }

    @Test
    fun `a non-boolean value is ignored rather than read as true`() {
        val junk = buildJsonObject {
            put("process_refunds", 1)          // not a boolean
            put("void_sales", "yes")           // not a boolean
        }
        val p = Permissions.fromJson(junk)
        assertTrue(p.granted.isEmpty())
        assertFalse(p.allows(Capability.PROCESS_REFUNDS))
        assertFalse(p.allows(Capability.VOID_SALES))
    }

    @Test
    fun `unknown keys survive a round trip without granting anything`() {
        val mixed = buildJsonObject {
            put("refunds", true)
            put("is_owner", true)
            put("admin", true)
        }
        val p = Permissions.fromJson(mixed)
        assertEquals(mapOf(Capability.PROCESS_REFUNDS to true), p.granted)
    }

    /** Nothing is left to be inferred from absence: the wire map states every capability
     *  explicitly, under both spellings where the web has one of its own. */
    @Test
    fun `toWireMap states every capability under both spellings`() {
        val wire = perms(Capability.PROCESS_REFUNDS to true).toWireMap()
        for (cap in Capability.entries) {
            assertTrue("missing ${cap.key}", wire.containsKey(cap.key))
            cap.wireKey?.let { assertTrue("missing $it", wire.containsKey(it)) }
            cap.wireKey?.let { assertTrue(wire[cap.key] == wire[it]) }
        }
        assertTrue(wire["process_refunds"] == true)
        assertTrue(wire["refunds"] == true)
    }

    @Test
    fun `a wire map round-trips back through fromKeyMap unchanged`() {
        val original = Permissions(Capability.entries.associateWith { it.ordinal % 2 == 0 })
        val restored = Permissions.fromKeyMap(original.asKeyMap())
        for (cap in Capability.entries) {
            assertTrue(
                "${cap.key} changed across the round trip",
                original.allows(cap) == restored.allows(cap)
            )
        }
    }
}
