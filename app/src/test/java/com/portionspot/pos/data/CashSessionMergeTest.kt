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
            "pay_in", "pay_out", "drop", "petty", "float_topup", "safe_in", "safe_out", "bank_deposit"
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
        // `bank_deposit` is deliberately NOT in this list any more. It used to be, and that
        // assertion was pinning the bug: the web banks money OUT OF THE SAFE, so reading it
        // against the till made a browser deposit show up as the phone's drawer being short
        // by the whole amount. Its real behaviour is pinned in
        // aWebBankDepositComesOutOfTheSafe_andLeavesTheTillAlone.
        for (out in listOf("drop", "petty", "pay_out")) {
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
            "pay_in", "pay_out", "drop", "petty", "float_topup", "safe_in", "safe_out", "bank_deposit"
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

    @Test
    fun moneyOutOfTheSafeLeavesTheSafeAndNotTheDrawer() {
        // ★ THE C3 BUG. Money leaving the safe had no word on the wire, so it went up as a
        // bare `pay_out` and came back down against the TILL. A float top-up made on one
        // phone left the other phone's till short and its safe over by the same amount,
        // and because cash-on-hand still added up to the cent, nothing ever flagged it.
        val row = cashMovementTypeFromWire("safe_out", 250.0)
        assertEquals(CashLocation.SAFE, row.location)
        assertEquals(-250.0, row.amount, 1e-9)
    }

    @Test
    fun aSafeToTillTransferMovesBOTHBalancesOnTheOtherPhone() {
        // A float top-up is a PAIR of local rows, and the whole pair has to survive the
        // trip or the two phones stop agreeing about where the money is. Walk it out and
        // back exactly as the sync does.
        val safeSide = cashMovementTypeToWire("transfer_out", CashLocation.SAFE, -60.0)
        val tillSide = cashMovementTypeToWire("transfer_in", CashLocation.TILL, 60.0)
        assertEquals("safe_out", safeSide)
        assertEquals("pay_in", tillSide)

        // The push sends magnitudes; the receiving phone rebuilds the signs.
        val safeBack = cashMovementTypeFromWire(safeSide, 60.0)
        val tillBack = cashMovementTypeFromWire(tillSide, 60.0)
        assertEquals(CashLocation.SAFE, safeBack.location)
        assertEquals(-60.0, safeBack.amount, 1e-9)
        assertEquals(CashLocation.TILL, tillBack.location)
        assertEquals(60.0, tillBack.amount, 1e-9)

        // The shop is no richer or poorer, and the money is in the other pocket. Before
        // `safe_out` the safe side landed on the till too, so both halves cancelled: the
        // receiving phone's till never moved and its safe never came down.
        assertEquals(0.0, safeBack.amount + tillBack.amount, 1e-9)
    }

    @Test
    fun aTillToSafeTransferStillTravelsAsThePairItAlreadyWas() {
        // The direction that already worked — pinned so `safe_out` cannot regress it.
        assertEquals("pay_out", cashMovementTypeToWire("transfer_out", CashLocation.TILL, -60.0))
        assertEquals("safe_in", cashMovementTypeToWire("transfer_in", CashLocation.SAFE, 60.0))
    }

    @Test
    fun aSafeFundedExpenseComesOutOfTheSafe_notTheDrawer() {
        // The case the rejected `float_topup` reconstruction would NOT have fixed: money
        // paid straight out of the safe to a payee has no till half to pair with.
        assertEquals("safe_out", cashMovementTypeToWire("expense", CashLocation.SAFE, -75.0))
        val back = cashMovementTypeFromWire("safe_out", 75.0)
        assertEquals(CashLocation.SAFE, back.location)
        assertEquals(-75.0, back.amount, 1e-9)
    }

    // -- The web's own words, which move money between TWO pockets ------------
    //
    // The web keeps a full safe and writes each of these as ONE row meaning both halves.
    // Android reads one row per location, so importing only the half the type names makes
    // the other half vanish -- and unlike the safe_out bug, that breaks TOTAL cash on hand
    // and not merely the split. Android never emits these words itself, which is what
    // makes adopting the web's meaning safe.

    @Test
    fun aWebBankDepositComesOutOfTheSafe_andLeavesTheTillAlone() {
        // The sharpest of the three: this used to debit the phone's TILL, so banking money
        // in the browser made the phone's expected drawer read short by the whole deposit
        // while the web's till was untouched.
        val row = cashMovementTypeFromWire("bank_deposit", 400.0)
        assertEquals(CashLocation.SAFE, row.location)
        assertEquals(-400.0, row.amount, 1e-9)
        assertNull("a bank deposit leaves the business; there is no second pocket", row.counterpart)
    }

    @Test
    fun aWebDropMovesTheTillIntoTheSafe_bothHalves() {
        val row = cashMovementTypeFromWire("drop", 250.0)
        assertEquals(CashLocation.TILL, row.location)
        assertEquals(-250.0, row.amount, 1e-9)
        val other = row.counterpart!!
        assertEquals(CashLocation.SAFE, other.location)
        assertEquals(250.0, other.amount, 1e-9)
        // A transfer moves money; it does not create or destroy any.
        assertEquals(0.0, row.amount + other.amount, 1e-9)
    }

    @Test
    fun aWebFloatTopUpMovesTheSafeIntoTheTill_bothHalves() {
        val row = cashMovementTypeFromWire("float_topup", 60.0)
        assertEquals(CashLocation.TILL, row.location)
        assertEquals(60.0, row.amount, 1e-9)
        val other = row.counterpart!!
        assertEquals(CashLocation.SAFE, other.location)
        assertEquals(-60.0, other.amount, 1e-9)
        assertEquals(0.0, row.amount + other.amount, 1e-9)
    }

    @Test
    fun theOneWordMovementsHaveNoCounterpart() {
        // Only the two genuine transfers produce a second row. If any of these grew one,
        // a pull would start inventing money that never moved.
        for (w in listOf("pay_in", "pay_out", "petty", "safe_in", "safe_out", "bank_deposit")) {
            assertNull("$w must not produce a second row", cashMovementTypeFromWire(w, 10.0).counterpart)
        }
    }

    @Test
    fun androidNeverEmitsTheTwoPocketWords_soAdoptingTheWebsMeaningIsSafe() {
        // The premise the whole change rests on, pinned. Android's own transfers travel as
        // a PAIR of one-pocket rows, so it can never receive back a two-pocket word of its
        // own making and double-count it.
        assertEquals("pay_out", cashMovementTypeToWire("transfer_out", CashLocation.TILL, -60.0))
        assertEquals("safe_in", cashMovementTypeToWire("transfer_in", CashLocation.SAFE, 60.0))
        assertEquals("safe_out", cashMovementTypeToWire("transfer_out", CashLocation.SAFE, -60.0))
        assertEquals("pay_in", cashMovementTypeToWire("transfer_in", CashLocation.TILL, 60.0))
        // And nothing this app writes maps onto them.
        for (local in listOf("drawing", "expense", "variance", "adjust", "loan", "purchase",
                             "safe_withdrawal", "credit_payment", "change_payout", "refund")) {
            val wire = cashMovementTypeToWire(local, CashLocation.TILL, -10.0)
            assertTrue(
                "$local must not travel as a two-pocket word",
                wire !in listOf("drop", "float_topup")
            )
        }
    }
}
