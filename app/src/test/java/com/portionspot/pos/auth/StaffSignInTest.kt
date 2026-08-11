package com.portionspot.pos.auth

import com.portionspot.pos.data.StaffMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Signing in at the counter: a name (or a username) and a PIN, checked against the roster
 * this device already holds.
 *
 * Every case below runs with NO network and NO Android — which is the point rather than a
 * testing convenience. The credential is `staff.pin_hash`, mirrored onto the phone by the
 * roster pull, so a shop trading through a power cut can still open its till. A sign-in
 * that needed a round trip would fail on exactly the days it is needed most.
 *
 * The hashes here are produced by [StaffPin], whose compatibility with the web's `pin.js`
 * is pinned separately by [StaffPinCompatTest]. So "this PIN verifies" here means the same
 * thing it means on the web POS, not merely that this file agrees with itself.
 */
class StaffSignInTest {

    /** The CLOUD business id. The salt is derived from it — see [rosterPullBusinessId]. */
    private val shop = "7a1f2c40-0b1e-4c3a-9d2b-5e6f70819a2b"

    private fun member(
        id: String = "s1",
        name: String = "Tendai",
        username: String = "tendai",
        role: String = "cashier",
        active: Boolean = true,
        deleted: Boolean = false,
        pin: String? = "1234",
        businessId: String = shop,
    ) = StaffMember(
        id = id,
        businessId = businessId,
        name = name,
        username = username,
        role = role,
        active = active,
        deleted = deleted,
        pinHash = pin?.let { StaffPin.hash(it, businessId) },
    )

    // ── the happy path ────────────────────────────────────────────────────

    @Test
    fun `a correct PIN signs the cashier in and carries their row`() {
        val roster = listOf(member())
        val result = StaffSignIn.attempt(roster, "tendai", "1234", shop)
        assertTrue(result is StaffSignInResult.Ok)
        assertEquals("s1", (result as StaffSignInResult.Ok).member.id)
    }

    @Test
    fun `a keyboard that capitalises has not produced a different person`() {
        // A phone keyboard auto-capitalises the first letter and a fat thumb adds a space.
        // Refusing over either is indistinguishable, to the cashier, from a broken app.
        val roster = listOf(member())
        assertTrue(StaffSignIn.attempt(roster, " Tendai ", "1234", shop) is StaffSignInResult.Ok)
    }

    @Test
    fun `tapping a name on the roster takes the same path as typing it`() {
        val row = member()
        assertTrue(StaffSignIn.verify(row, "1234", shop) is StaffSignInResult.Ok)
    }

    // ── the four refusals, each of which must be its own answer ────────────

    @Test
    fun `a wrong PIN is refused and says so`() {
        val roster = listOf(member())
        assertEquals(
            StaffSignInResult.WrongPin,
            StaffSignIn.attempt(roster, "tendai", "9999", shop)
        )
    }

    @Test
    fun `a deactivated member is told they were switched off, not that the PIN is wrong`() {
        // ★ Reported WITHOUT the PIN being correct, on purpose. This is a shop counter,
        // not a public login form: everyone here already knows who works there, so
        // withholding it protects nothing and costs a phone call to the owner to learn
        // what a clear message would have said.
        val roster = listOf(member(active = false))
        assertEquals(
            StaffSignInResult.Deactivated,
            StaffSignIn.attempt(roster, "tendai", "1234", shop)
        )
        assertEquals(
            StaffSignInResult.Deactivated,
            StaffSignIn.attempt(roster, "tendai", "9999", shop)
        )
    }

    @Test
    fun `a tombstoned member is refused just as a deactivated one is`() {
        val roster = listOf(member(deleted = true))
        assertEquals(
            StaffSignInResult.Deactivated,
            StaffSignIn.attempt(roster, "tendai", "1234", shop)
        )
    }

    @Test
    fun `a member the owner never gave a PIN cannot sign in`() {
        val roster = listOf(member(pin = null))
        assertEquals(
            StaffSignInResult.NoPinSet,
            StaffSignIn.attempt(roster, "tendai", "1234", shop)
        )
    }

    @Test
    fun `an unknown username is not confused with an empty till`() {
        // Different fixes: "check your spelling" versus "connect this till once".
        assertEquals(
            StaffSignInResult.UnknownUser,
            StaffSignIn.attempt(listOf(member()), "chipo", "1234", shop)
        )
        assertEquals(
            StaffSignInResult.NoRoster,
            StaffSignIn.attempt(emptyList(), "chipo", "1234", shop)
        )
    }

    @Test
    fun `every refusal carries a message that names the real problem`() {
        val messages = listOf(
            StaffSignInResult.UnknownUser,
            StaffSignInResult.Deactivated,
            StaffSignInResult.NoPinSet,
            StaffSignInResult.WrongPin,
            StaffSignInResult.NoRoster,
        ).map { StaffSignIn.message(it) }
        assertEquals(messages.size, messages.distinct().size)
        assertTrue(messages.none { it.isBlank() })
    }

    // ── the salt is the shop's, and getting it wrong is silent ─────────────

    /**
     * ★★ THE ONE THAT CANNOT BE CAUGHT BY LOOKING AT THE APP ★★
     *
     * `staff.pin_hash` is PBKDF2 over a salt derived as
     * `SHA-256("PortionSpot POS pin v1:" + business_id)`. The web salts with the CLOUD id,
     * so a row stamped with the device's own locally-invented business uuid derives a
     * completely different digest — and the failure is a correct PIN reported as "Wrong
     * PIN", with no error, no log line, and nothing to distinguish it from the cashier
     * mistyping. That is why the roster pull stamps `staff.businessId` with the cloud id
     * and why [StaffSignIn.verify] prefers the member's OWN id over the caller's.
     */
    @Test
    fun `a hash written for one shop cannot verify under another`() {
        val otherShop = "11111111-2222-3333-4444-555555555555"
        val row = member(businessId = shop)
        assertTrue(StaffSignIn.verify(row, "1234", shop) is StaffSignInResult.Ok)
        // The row still carries its own shop id, so passing the wrong one is survivable...
        assertTrue(StaffSignIn.verify(row, "1234", otherShop) is StaffSignInResult.Ok)
        // ...but a row RE-STAMPED with the wrong id is not, and this is what it looks like.
        val misfiled = row.copy(businessId = otherShop)
        assertEquals(StaffSignInResult.WrongPin, StaffSignIn.verify(misfiled, "1234", shop))
    }

    // ── the PIN-only pad ──────────────────────────────────────────────────

    @Test
    fun `a PIN-only pad finds the right person and refuses a deactivated one`() {
        val tendai = member(id = "s1", username = "tendai", pin = "1234")
        val chipo = member(id = "s2", name = "Chipo", username = "chipo", pin = "5678")
        val sacked = member(id = "s3", name = "Farai", username = "farai", pin = "4321", active = false)
        val roster = listOf(tendai, chipo, sacked)

        assertEquals("s2", (StaffSignIn.byPinOnly(roster, "5678", shop) as StaffSignInResult.Ok).member.id)
        // ★ A pad with no username is the one place a deactivated person's hash still on
        // the device could let them back in, so those rows are not even candidates.
        assertEquals(StaffSignInResult.WrongPin, StaffSignIn.byPinOnly(roster, "4321", shop))
    }
}
