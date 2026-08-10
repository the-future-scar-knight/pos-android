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
}
