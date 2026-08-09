package com.portionspot.pos.auth

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the remote-revocation contract: when the shop owner takes a capability away in
 * the console, the cashier's phone must lose it — and when the phone simply cannot
 * reach the server, it must NOT.
 *
 * The bug these guard against failed OPEN in three ways at once. The refresh copied
 * role/name/permissions but ignored `active`, so a deactivated cashier kept working; a
 * missing row and a dropped signal were the same nullable answer, so a staff member
 * deleted in the console kept their grants forever; and nothing narrowed a nonsense
 * role. Every assertion below is the safe direction: a revocation may arrive late, but
 * a stale cache must never widen anyone's powers.
 */
class PermissionRefreshTest {

    private val cachedName = "Tendai"

    private fun profile(
        role: String = "cashier",
        displayName: String = cachedName,
        active: Boolean = true,
        permissions: JsonObject? = null,
    ) = StaffProfileDto(role, displayName, active, permissions)

    private fun cached(
        role: String = "cashier",
        displayName: String = cachedName,
        permissions: Map<String, Boolean> = emptyMap(),
    ) = CachedAuth(
        userId = "u1",
        email = "tendai@shop.co.zw",
        role = role,
        displayName = displayName,
        accessToken = "jwt-abc",
        refreshToken = "refresh-abc",
        expiresAt = 1_800_000_000L,
        permissions = permissions,
    )

    // ── the deleted-vs-offline decision ───────────────────────────────────

    @Test
    fun unreachableServer_keepsCachedGrants() {
        // Offline-first: a shop on a dead cell keeps selling with the grants it last knew.
        assertEquals(
            PermissionVerdict.KeepCached,
            PermissionRefresh.verdict(StaffProfileFetch.Unreachable, cachedName)
        )
    }

    @Test
    fun missingRow_isNotActedOnUntilASecondSighting() {
        // ★ The shop-wide-wipe guard. One empty read can mean pos_staff was just rebuilt
        // or an RLS policy tightened — conditions that answer "no rows" for EVERY cashier
        // at once. Revoking on the first sighting would strip every till in the shop
        // simultaneously, with no way back until someone is online with a password.
        val first = PermissionRefresh.verdict(StaffProfileFetch.Missing, cachedName, missingStrikes = 0)
        assertEquals(PermissionVerdict.AwaitConfirmation, first)

        // Still a verdict, though — it must never resolve the same way as no answer at all.
        val offline = PermissionRefresh.verdict(StaffProfileFetch.Unreachable, cachedName, missingStrikes = 0)
        assertNotEquals(first, offline)
    }

    @Test
    fun missingRow_revokesOnTheSecondConsecutiveSighting() {
        // A genuinely deleted row is still gone on the next pass, so a real revocation
        // lands within one sync cycle rather than never.
        assertEquals(
            PermissionVerdict.RevokeAccount,
            PermissionRefresh.verdict(StaffProfileFetch.Missing, cachedName, missingStrikes = 1)
        )
    }

    @Test
    fun bankedStrikes_doNotRevokeWhenTheServerGoesQuiet() {
        // Going offline AFTER a missing sighting must not tip the account over the edge:
        // an unreachable pass is not evidence and cannot supply the second strike.
        assertEquals(
            PermissionVerdict.KeepCached,
            PermissionRefresh.verdict(StaffProfileFetch.Unreachable, cachedName, missingStrikes = 1)
        )
    }

    @Test
    fun deactivatedStaff_revokesTheAccount_evenWithGenerousPermissions() {
        // active=false outranks whatever the permissions map still says.
        val fetch = StaffProfileFetch.Found(
            profile(active = false, permissions = buildJsonObject { put("void_sales", true) })
        )
        assertEquals(PermissionVerdict.RevokeAccount, PermissionRefresh.verdict(fetch, cachedName))
    }

    // ── adopting the server's view ────────────────────────────────────────

    @Test
    fun revokedCapability_isAdopted_andActuallyDenies() {
        val fetch = StaffProfileFetch.Found(
            profile(permissions = buildJsonObject {
                put("give_discounts", false)
                put("manage_inventory", true)
            })
        )
        val verdict = PermissionRefresh.verdict(fetch, cachedName) as PermissionVerdict.Adopt
        val perms = Permissions.fromKeyMap(verdict.permissions)
        assertFalse(perms.allows(Capability.GIVE_DISCOUNTS))
        assertTrue(perms.allows(Capability.MANAGE_INVENTORY))
    }

    @Test
    fun grantDroppedFromTheServerMap_fallsBackToDefaults_notToTheStaleCache() {
        // The owner cleared the map entirely. The write-through REPLACES the cached
        // grants, so a capability the cashier used to hold is gone rather than inherited.
        val stale = cached(permissions = mapOf("void_sales" to true))
        val fetch = StaffProfileFetch.Found(profile(permissions = buildJsonObject { }))
        val verdict = PermissionRefresh.verdict(fetch, cachedName) as PermissionVerdict.Adopt
        val updated = PermissionRefresh.applyTo(verdict, stale)
        assertTrue(updated.permissions.isEmpty())
        assertFalse(Permissions.fromKeyMap(updated.permissions).allows(Capability.VOID_SALES))
    }

    @Test
    fun demotionOutOfAdmin_isCarried_andRegatesTheUser() {
        val fetch = StaffProfileFetch.Found(profile(role = "cashier"))
        val verdict = PermissionRefresh.verdict(fetch, cachedName) as PermissionVerdict.Adopt
        val updated = PermissionRefresh.applyTo(verdict, cached(role = "admin"))
        assertEquals("cashier", updated.role)
        // The single gate the whole app funnels through: no longer ungated.
        val user = PosUser("u1", "tendai@shop.co.zw", updated.role, updated.displayName,
            Permissions.fromKeyMap(updated.permissions))
        assertFalse(user.isAdmin)
        assertFalse(user.can(Capability.VOID_SALES))
    }

    @Test
    fun promotionToAdmin_isCarried() {
        val fetch = StaffProfileFetch.Found(profile(role = "admin"))
        val verdict = PermissionRefresh.verdict(fetch, cachedName) as PermissionVerdict.Adopt
        val updated = PermissionRefresh.applyTo(verdict, cached())
        val user = PosUser("u1", "tendai@shop.co.zw", updated.role, updated.displayName,
            Permissions.fromKeyMap(updated.permissions))
        assertTrue(user.isAdmin)
        assertTrue(user.can(Capability.VOID_SALES))   // an admin is never gated
    }

    @Test
    fun blankRole_narrowsToCashier_ratherThanInheritingAdmin() {
        val fetch = StaffProfileFetch.Found(profile(role = "  "))
        val verdict = PermissionRefresh.verdict(fetch, cachedName) as PermissionVerdict.Adopt
        assertEquals(PermissionRefresh.FALLBACK_ROLE, verdict.role)
        assertFalse(PermissionRefresh.applyTo(verdict, cached(role = "admin")).role == "admin")
    }

    @Test
    fun blankDisplayName_keepsTheNameAlreadyOnTheDevice() {
        // A legacy row with no display_name is not a rename to nothing; receipts and
        // attribution keep the name the device already has.
        val fetch = StaffProfileFetch.Found(profile(displayName = ""))
        val verdict = PermissionRefresh.verdict(fetch, cachedName) as PermissionVerdict.Adopt
        assertEquals(cachedName, verdict.displayName)
    }

    // ── the write-through ─────────────────────────────────────────────────

    @Test
    fun applyTo_writesThroughGrants_andLeavesTheSessionAlone() {
        val before = cached(role = "cashier", permissions = mapOf("give_discounts" to true))
        val fetch = StaffProfileFetch.Found(
            profile(role = "admin", displayName = "Tendai M",
                permissions = buildJsonObject { put("give_discounts", false) })
        )
        val verdict = PermissionRefresh.verdict(fetch, cachedName) as PermissionVerdict.Adopt
        val after = PermissionRefresh.applyTo(verdict, before)
        assertEquals("admin", after.role)
        assertEquals("Tendai M", after.displayName)
        assertEquals(mapOf("give_discounts" to false), after.permissions)
        // Tokens/identity are the sync layer's business, not this path's.
        assertEquals(before.userId, after.userId)
        assertEquals(before.email, after.email)
        assertEquals(before.accessToken, after.accessToken)
        assertEquals(before.refreshToken, after.refreshToken)
        assertEquals(before.expiresAt, after.expiresAt)
    }

    @Test
    fun applyTo_nothingChanged_isEqualToTheCachedSession() {
        // Equality is what lets the caller skip a vault re-encrypt on every ~45s pass.
        val before = cached(permissions = mapOf("manage_inventory" to true))
        val fetch = StaffProfileFetch.Found(
            profile(permissions = buildJsonObject { put("manage_inventory", true) })
        )
        val verdict = PermissionRefresh.verdict(fetch, cachedName) as PermissionVerdict.Adopt
        assertEquals(before, PermissionRefresh.applyTo(verdict, before))
    }
}
