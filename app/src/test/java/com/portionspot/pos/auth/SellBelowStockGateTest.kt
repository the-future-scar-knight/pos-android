package com.portionspot.pos.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Capability.SELL_BELOW_STOCK] — the grant behind the oversell warning.
 *
 * The behaviour it has to keep is unusual for a permission and easy to "tidy" into the
 * wrong shape, so it is pinned here rather than left to the general gate test:
 *
 *  - it is ON for an unconfigured cashier, because it is a WARNING by default. The shop
 *    genuinely sells stock the system has not caught up with, and a shop that never opens
 *    the staff console must still be able to serve those customers;
 *  - the owner can turn it OFF per cashier, and then it is a real refusal — that is the
 *    whole reason it is a capability and not a hardcoded confirm dialog;
 *  - the admin (and therefore every local/phone-only session, which runs as one) is never
 *    gated by it.
 */
class SellBelowStockGateTest {

    private val cap = Capability.SELL_BELOW_STOCK

    @Test
    fun `an unconfigured cashier may sell past the on-hand — it is a warning, not a wall`() {
        assertTrue(
            "a shop that never touched the staff console could not sell an un-booked-in delivery",
            isCapabilityAllowed(isAdmin = false, permissions = Permissions.EMPTY, cap = cap)
        )
        assertEquals(true, Permissions.CASHIER_DEFAULTS[cap])
    }

    @Test
    fun `the owner can revoke it for one cashier`() {
        assertFalse(
            isCapabilityAllowed(false, Permissions(mapOf(cap to false)), cap)
        )
        // …without touching anyone else's, or any other grant this cashier holds.
        assertTrue(isCapabilityAllowed(false, Permissions(mapOf(cap to false)), Capability.MANAGE_INVENTORY))
    }

    /** Local mode signs in as an admin against the same database (MainActivity), so this is
     *  also the assertion that a phone-only till never gates its own owner. */
    @Test
    fun `an admin is never gated by it, even revoked and shop-locked`() {
        assertTrue(isCapabilityAllowed(true, Permissions(mapOf(cap to false)), cap))
        assertTrue(
            isCapabilityAllowed(true, Permissions(mapOf(cap to false)), cap, shopLocks = setOf(cap))
        )
    }

    /**
     * It is deliberately NOT [Capability.MANAGE_INVENTORY] wearing a second hat: counting a
     * shelf and selling past the count are different discretions and an owner may want
     * either without the other. If someone ever collapses them, these two go together and
     * this fails.
     */
    @Test
    fun `it is independent of manage_inventory`() {
        val countsButMayNotOversell =
            Permissions(mapOf(Capability.MANAGE_INVENTORY to true, cap to false))
        assertTrue(isCapabilityAllowed(false, countsButMayNotOversell, Capability.MANAGE_INVENTORY))
        assertFalse(isCapabilityAllowed(false, countsButMayNotOversell, cap))

        val sellsButMayNotCount =
            Permissions(mapOf(Capability.MANAGE_INVENTORY to false, cap to true))
        assertFalse(isCapabilityAllowed(false, sellsButMayNotCount, Capability.MANAGE_INVENTORY))
        assertTrue(isCapabilityAllowed(false, sellsButMayNotCount, cap))
    }

    /**
     * The web gates seven capabilities and this is not one of them, so it carries no
     * [Capability.wireKey] — there is nothing on that side for it to round-trip with, and
     * inventing a spelling the web does not read would put a key in the shared column that
     * only ever confuses it. It still goes up under its own name (see [Permissions.toWireMap])
     * so a revocation made here survives a round trip through the cloud.
     */
    @Test
    fun `it has no web spelling, and round-trips under its own`() {
        assertNull(cap.wireKey)
        assertNull("the web has no shop-wide lock for it", cap.shopLockColumn)
        assertEquals(cap, Capability.fromKey("sell_below_stock"))

        val revoked = Permissions(mapOf(cap to false))
        assertEquals(false, revoked.toWireMap()["sell_below_stock"])
        assertFalse(Permissions.fromKeyMap(revoked.toWireMap()).allows(cap))
    }
}
