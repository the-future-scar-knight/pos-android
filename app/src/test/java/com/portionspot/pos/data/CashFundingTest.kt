package com.portionspot.pos.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The §4 funding waterfall — **TILL → SAFE → OUTSIDE FUNDS → abort** — and the money
 * invariants of the two-location model.
 *
 * [PosRepository.planFunding] is pure precisely so it can be tested like this: it decides
 * where every dollar of a payment comes from, and a plan that does not add up would
 * silently create or destroy money in the ledger.
 */
class CashFundingTest {

    private fun plan(amount: Double, mode: String, till: Double, safe: Double) =
        PosRepository.planFunding(amount, mode, till, safe)

    // ── The plan always adds up ──────────────────────────────────────────

    /** ★ Whatever the mode, the four parts must sum to the amount being paid. */
    @Test
    fun `every plan accounts for exactly the amount being paid`() {
        val modes = listOf("till", "safe", "waterfall", "available", "capital", "loan", "none", "cash")
        val balances = listOf(0.0 to 0.0, 30.0 to 0.0, 0.0 to 30.0, 40.0 to 500.0, 500.0 to 500.0)
        for (mode in modes) {
            for ((till, safe) in balances) {
                val p = plan(100.0, mode, till, safe)
                assertEquals(
                    "mode=$mode till=$till safe=$safe did not account for the full amount",
                    100.0, p.total, 0.0001
                )
            }
        }
    }

    // ── The order money is reached for ───────────────────────────────────

    @Test
    fun `the waterfall empties the till before it opens the safe`() {
        val p = plan(100.0, "waterfall", till = 40.0, safe = 500.0)
        assertEquals(40.0, p.fromTill, 0.0001)
        assertEquals(60.0, p.fromSafe, 0.0001)
        assertEquals(0.0, p.payable, 0.0001)
    }

    @Test
    fun `the till alone pays when it can, leaving the safe shut`() {
        val p = plan(100.0, "waterfall", till = 250.0, safe = 500.0)
        assertEquals(100.0, p.fromTill, 0.0001)
        assertEquals(0.0, p.fromSafe, 0.0001)
        assertFalse("the safe must stay shut when the till covers it", p.needsSafe)
    }

    /** "Take what's available" still works, now across BOTH locations. */
    @Test
    fun `take what is available spans both locations and owes the rest`() {
        val p = plan(100.0, "waterfall", till = 30.0, safe = 20.0)
        assertEquals(30.0, p.fromTill, 0.0001)
        assertEquals(20.0, p.fromSafe, 0.0001)
        assertEquals(50.0, p.payable, 0.0001)
        assertEquals(50.0, p.cash, 0.0001)
    }

    @Test
    fun `the legacy available mode behaves exactly like the waterfall`() {
        val a = plan(100.0, "available", till = 30.0, safe = 20.0)
        val w = plan(100.0, "waterfall", till = 30.0, safe = 20.0)
        assertEquals(w.fromTill, a.fromTill, 0.0001)
        assertEquals(w.fromSafe, a.fromSafe, 0.0001)
        assertEquals(w.payable, a.payable, 0.0001)
    }

    /**
     * The legacy "cash" mode meant "pay the whole thing from shop cash, never create a
     * payable". It must keep meaning that, now spread across the two locations.
     */
    @Test
    fun `legacy cash mode pays the whole amount from shop cash, drawer first`() {
        val p = plan(100.0, "cash", till = 40.0, safe = 500.0)
        assertEquals(40.0, p.fromTill, 0.0001)
        assertEquals(60.0, p.fromSafe, 0.0001)
        assertEquals("legacy cash never creates a payable", 0.0, p.payable, 0.0001)
    }

    // ── The safe gate ────────────────────────────────────────────────────

    /** [PosRepository.FundingPlan.needsSafe] is what the admin gate keys off. */
    @Test
    fun `needsSafe flags exactly the plans that open the safe`() {
        assertTrue(plan(100.0, "safe", till = 0.0, safe = 500.0).needsSafe)
        assertTrue(plan(100.0, "waterfall", till = 40.0, safe = 500.0).needsSafe)
        assertFalse(plan(100.0, "till", till = 500.0, safe = 500.0).needsSafe)
        assertFalse(plan(100.0, "capital", till = 0.0, safe = 500.0).needsSafe)
        assertFalse(plan(100.0, "loan", till = 0.0, safe = 500.0).needsSafe)
        assertFalse(plan(100.0, "none", till = 0.0, safe = 500.0).needsSafe)
        // Nothing to take from the safe => no gate to trip.
        assertFalse(plan(100.0, "waterfall", till = 20.0, safe = 0.0).needsSafe)
    }

    // ── Outside funds ────────────────────────────────────────────────────

    /** Outside money pays the payee directly: no shop cash moves at all. */
    @Test
    fun `outside funds move no shop cash and record which kind they were`() {
        val owner = plan(100.0, "capital", till = 500.0, safe = 500.0)
        assertEquals(0.0, owner.cash, 0.0001)
        assertEquals(100.0, owner.outside, 0.0001)
        assertEquals("capital", owner.outsideKind)

        val borrowed = plan(100.0, "loan", till = 500.0, safe = 500.0)
        assertEquals(0.0, borrowed.cash, 0.0001)
        assertEquals(100.0, borrowed.outside, 0.0001)
        assertEquals("loan", borrowed.outsideKind)
    }

    @Test
    fun `paying nothing now owes the whole amount`() {
        val p = plan(100.0, "none", till = 500.0, safe = 500.0)
        assertEquals(0.0, p.cash, 0.0001)
        assertEquals(0.0, p.outside, 0.0001)
        assertEquals(100.0, p.payable, 0.0001)
    }

    // ── Degenerate inputs ────────────────────────────────────────────────

    @Test
    fun `a zero or negative payment plans nothing`() {
        assertEquals(0.0, plan(0.0, "waterfall", 100.0, 100.0).total, 0.0001)
        assertEquals(0.0, plan(-50.0, "waterfall", 100.0, 100.0).total, 0.0001)
    }

    /** A negative balance (an over-drawn drawer) must never be treated as spendable. */
    @Test
    fun `a negative balance supplies nothing`() {
        val p = plan(100.0, "waterfall", till = -20.0, safe = -5.0)
        assertEquals(0.0, p.fromTill, 0.0001)
        assertEquals(0.0, p.fromSafe, 0.0001)
        assertEquals(100.0, p.payable, 0.0001)
    }

    // ── The transfer invariant ───────────────────────────────────────────

    /**
     * ★ A move between the shop's own two pockets is neither income nor expense. The
     * ledger writes it as a matching pair, so the COMBINED balance must be unchanged
     * while the two locations both move. This is the arithmetic that guarantees it.
     */
    @Test
    fun `a transfer moves both locations and leaves the combined total untouched`() {
        var till = 40.0
        var safe = 260.0
        val before = till + safe

        // The pair written by writeTransfer: -amount at the source, +amount at the target.
        val amount = 60.0
        till += amount
        safe -= amount

        assertEquals(100.0, till, 0.0001)
        assertEquals(200.0, safe, 0.0001)
        assertEquals("moving your own money must not change how much you have", before, till + safe, 0.0001)
    }

    /** Closing the day: the true-up changes the total, the move to the safe does not. */
    @Test
    fun `a day close trues up the till and then only relocates the excess`() {
        val expected = 320.0
        val counted = 315.0          // five dollars short
        val floatTarget = 100.0

        val variance = counted - expected
        assertEquals(-5.0, variance, 0.0001)

        // 1. The variance corrects the till to what was physically counted.
        val tillAfterTrueUp = expected + variance
        assertEquals("the count wins", counted, tillAfterTrueUp, 0.0001)

        // 2. The excess over the float target relocates; the combined total holds.
        val moved = (counted - floatTarget).coerceAtLeast(0.0)
        val till = tillAfterTrueUp - moved
        val safe = 0.0 + moved
        assertEquals(215.0, moved, 0.0001)
        assertEquals(floatTarget, till, 0.0001)
        assertEquals(counted, till + safe, 0.0001)
    }
}
