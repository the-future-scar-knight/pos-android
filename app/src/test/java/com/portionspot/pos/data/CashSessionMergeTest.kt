package com.portionspot.pos.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The merge rule, pinned.
 *
 * These tests are about INTEROP, not arithmetic: the rule has to produce the same
 * survivor as the web POS does, from the same rows, without the two clients talking to
 * each other. So the assertions are mostly of the form "order of discovery must not
 * change the answer" — because the one thing two offline tills are guaranteed not to
 * agree on is the order they see rows in.
 */
class CashSessionMergeTest {

    private data class S(
        override val id: String,
        override val openedAt: Long,
    ) : MergeableSession

    // ────────────────────────────── the winner rule ──────────────────────────────

    @Test
    fun oldestOpenedAtWins() {
        val early = S("zzz", 1_000L)   // deliberately the LAST id, to prove time leads
        val late = S("aaa", 2_000L)
        assertEquals(early, pickSurvivingSession(listOf(late, early)))
    }

    @Test
    fun tiesAreBrokenByIdAscending() {
        // Same instant: the id decides, and it must decide the same way on both clients.
        val a = S("aaa", 1_000L)
        val b = S("bbb", 1_000L)
        assertEquals(a, pickSurvivingSession(listOf(b, a)))
        assertEquals(a, pickSurvivingSession(listOf(a, b)))
    }

    @Test
    fun theAnswerDoesNotDependOnTheOrderTheRowsArrive() {
        // The whole point: two tills pull in different orders and must still converge.
        val sessions = listOf(
            S("d4", 5_000L), S("a1", 3_000L), S("c3", 3_000L), S("b2", 9_000L)
        )
        val expected = pickSurvivingSession(sessions)
        val r = Random(4321)
        repeat(200) {
            assertEquals(expected, pickSurvivingSession(sessions.shuffled(r)))
        }
    }

    @Test
    fun emptyInputHasNoWinner() {
        assertNull(pickSurvivingSession(emptyList<S>()))
    }

    // ────────────────────────────── the merge plan ──────────────────────────────

    @Test
    fun noConflictWhenTheShopHasOneOpenShift() {
        // The normal state of a trading shop. Must be a no-op, not a merge that moves
        // rows around for nothing.
        assertNull(planSessionMerge(listOf(S("a", 1L))))
        assertNull(planSessionMerge(emptyList<S>()))
    }

    @Test
    fun duplicateRowsOfTheSameSessionAreNotAConflict() {
        // The same session seen twice (a re-pull, a cursor reset) is one shift, not two.
        val same = S("a", 1L)
        assertNull(planSessionMerge(listOf(same, same.copy())))
    }

    @Test
    fun everyLoserIsCarriedAndNoneIsTheWinner() {
        val plan = planSessionMerge(
            listOf(S("c", 3_000L), S("a", 1_000L), S("b", 2_000L))
        )!!
        assertEquals("a", plan.winner.id)
        assertEquals(listOf("c", "b"), plan.loserIds)
        assertTrue("the winner must never be in its own loser set", "a" !in plan.loserIds)
    }

    @Test
    fun threeWayConflictCollapsesToOneSurvivor() {
        // Three tills, all offline from each other. Whatever order any of them sees the
        // rows in, all three must close the same two shifts.
        val sessions = listOf(S("t1", 700L), S("t2", 500L), S("t3", 900L))
        val r = Random(99)
        repeat(100) {
            val plan = planSessionMerge(sessions.shuffled(r))!!
            assertEquals("t2", plan.winner.id)
            assertEquals(setOf("t1", "t3"), plan.loserIds.toSet())
        }
    }

    @Test
    fun theClosingNoteSaysWhatHappenedToTheShift() {
        // A closed shift with no explanation is indistinguishable from a lost one.
        val plan = planSessionMerge(listOf(S("aaaaaaaa-1", 1L), S("bbbbbbbb-2", 2L)))!!
        val note = plan.closingNote(plan.losers.first())
        assertTrue(note.contains(plan.winner.id.take(8)))
        assertTrue(note.isNotBlank())
    }

    // ─────────────────────────── movement type mapping ───────────────────────────

    @Test
    fun knownMovementTypesMapStraightThrough() {
        assertEquals("drop", cashMovementTypeToWire("drop", CashLocation.TILL, -50.0))
        assertEquals("petty", cashMovementTypeToWire("petty", CashLocation.TILL, -5.0))
        assertEquals("float_topup", cashMovementTypeToWire("float_topup", CashLocation.TILL, 20.0))
        assertEquals("safe_in", cashMovementTypeToWire("safe_in", CashLocation.SAFE, 100.0))
        assertEquals("bank_deposit", cashMovementTypeToWire("bank_deposit", CashLocation.SAFE, -200.0))
    }

    @Test
    fun anUnknownTypeStillResolvesToALegalValue() {
        // A CHECK violation fails the WHOLE batch, not the offending row, so "pass it
        // through and hope" would take every other movement in the push down with it.
        val legal = setOf(
            "pay_in", "pay_out", "drop", "petty", "float_topup", "safe_in", "bank_deposit"
        )
        val cases = listOf(
            Triple("something_new", CashLocation.TILL, 10.0),
            Triple("", CashLocation.TILL, -10.0),
            Triple("owner_capital", CashLocation.SAFE, 500.0),
            Triple("mystery", CashLocation.SAFE, -500.0),
        )
        for ((type, loc, amt) in cases) {
            assertTrue(
                "$type/$loc/$amt produced an illegal movement type",
                cashMovementTypeToWire(type, loc, amt) in legal
            )
        }
    }

    @Test
    fun unknownTypesKeepTheDirectionTheMoneyActuallyMoved() {
        // The category may be lost; the direction must not be.
        assertEquals("pay_in", cashMovementTypeToWire("mystery", CashLocation.TILL, 25.0))
        assertEquals("pay_out", cashMovementTypeToWire("mystery", CashLocation.TILL, -25.0))
        // Money going INTO the safe has a truer name than "pay_in", so it gets it.
        assertEquals("safe_in", cashMovementTypeToWire("mystery", CashLocation.SAFE, 25.0))
    }

    @Test
    fun typeMatchingIsForgivingAboutCaseAndSpacing() {
        assertEquals("drop", cashMovementTypeToWire("  DROP ", CashLocation.TILL, -50.0))
        assertEquals("bank_deposit", cashMovementTypeToWire("Deposit", CashLocation.SAFE, -200.0))
    }

    // ──────────────────────── coming back the other way ────────────────────────
    //
    // `cash_movements` was pushed and never pulled, so a day closed on one phone — a
    // variance true-up plus a till→safe transfer — and an owner drawing taken on it
    // never reached the second phone, and two tills over one drawer disagreed about
    // what was in it. These pin the inverse: the wire carries a MAGNITUDE with the
    // direction hidden in `type` and no location column at all, so both the sign and
    // the pocket have to be rebuilt, and getting either backwards moves real money the
    // wrong way on a device that was only reading.

    @Test
    fun theWireAmountIsAMagnitude_soTheDirectionIsRebuiltFromTheType() {
        // The push's own comment: send a signed figure and a $5 payout arrives as −5,
        // is negated again, and ADDS $5 to the drawer. Coming down, the same mistake
        // credits the till for money that was handed out.
        assertEquals(-5.0, cashMovementTypeFromWire("pay_out", 5.0).amount, 1e-9)
        assertEquals(60.0, cashMovementTypeFromWire("pay_in", 60.0).amount, 1e-9)
        // A client that sent a signed figure anyway must not flip the direction twice.
        assertEquals(-5.0, cashMovementTypeFromWire("pay_out", -5.0).amount, 1e-9)
    }

    @Test
    fun moneyIntoTheSafeLandsInTheSafeAndNotTheDrawer() {
        val row = cashMovementTypeFromWire("safe_in", 250.0)
        assertEquals(CashLocation.SAFE, row.location)
        assertEquals(250.0, row.amount, 1e-9)
    }

    @Test
    fun everyOtherTypeMovesTheTill_inTheDirectionItsNameMeans() {
        // A drop and a bank deposit take cash OUT of the drawer; a float top-up and a
        // pay-in put it in. Reversing any one of these is a phone that shows the till
        // fuller than it is, which is a shortage nobody discovers until the count.
        for (out in listOf("drop", "petty", "bank_deposit", "pay_out")) {
            val row = cashMovementTypeFromWire(out, 40.0)
            assertEquals("$out must leave the till", CashLocation.TILL, row.location)
            assertEquals("$out must be money going out", -40.0, row.amount, 1e-9)
        }
        for (into in listOf("float_topup", "pay_in")) {
            val row = cashMovementTypeFromWire(into, 40.0)
            assertEquals("$into must land in the till", CashLocation.TILL, row.location)
            assertEquals("$into must be money coming in", 40.0, row.amount, 1e-9)
        }
    }

    @Test
    fun everyLegalWireTypeSurvivesTheRoundTripUnchanged() {
        // ★ THE INTEROP TEST. A movement that leaves one phone and lands on another has
        // to be the SAME row on both, and a row re-pushed later has to go up under the
        // word it went up under the first time — otherwise two devices sharing one
        // drawer keep rewriting each other's history with slightly different words.
        val legal = listOf(
            "pay_in", "pay_out", "drop", "petty", "float_topup", "safe_in", "bank_deposit"
        )
        for (wire in legal) {
            val local = cashMovementTypeFromWire(wire, 75.0)
            assertEquals(
                "$wire did not survive the round trip",
                wire,
                cashMovementTypeToWire(local.type, local.location, local.amount)
            )
        }
    }

    @Test
    fun anUnrecognisedTypeIsBookedRatherThanDropped() {
        // The CHECK constraint says this cannot arrive; the day it does is the day the
        // other side relaxed it without telling this one. A row silently discarded is
        // cash that vanishes from one phone's drawer and stays in another's, with
        // nothing anywhere saying why — so it is booked as an adjustment at the till,
        // keeping the direction the row was actually written in.
        val out = cashMovementTypeFromWire("something_the_web_invented", -30.0)
        assertEquals("adjust", out.type)
        assertEquals(CashLocation.TILL, out.location)
        assertEquals(-30.0, out.amount, 1e-9)
        val into = cashMovementTypeFromWire("something_the_web_invented", 30.0)
        assertEquals(30.0, into.amount, 1e-9)
    }

    @Test
    fun theInverseIsAlsoForgivingAboutCaseAndSpacing() {
        assertEquals(CashLocation.SAFE, cashMovementTypeFromWire("  SAFE_IN ", 10.0).location)
        assertEquals(-10.0, cashMovementTypeFromWire("Pay_Out", 10.0).amount, 1e-9)
    }
}
