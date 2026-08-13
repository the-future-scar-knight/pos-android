package com.portionspot.pos.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * One shared drawer, two phones — pinned.
 *
 * The bug these tests stand against is not a wrong number on a screen, it is a wrong number
 * written PERMANENTLY: a phone whose cash ledger only knows its own sales measures the real
 * drawer against half a figure at the day close, and the difference lands as a `variance`,
 * which is a hit to profit. So the assertions here are almost all of the form "running it
 * twice writes it once" and "the phone that rang the sale up and the phone that pulled it
 * end up with the same drawer".
 */
class CashMirrorTest {

    private val eps = 1e-9

    private fun expect(
        id: String,
        total: Double,
        at: Long = 1_000L,
        label: String? = null,
        by: String? = null,
    ) = ExpectedCashRow(
        refId = id, total = total, at = at, label = label,
        createdBy = by, createdByName = by?.let { "Name of $it" },
    )

    private fun held(vararg pairs: Pair<String, Double>) =
        pairs.map { CashRefSum(it.first, it.second) }

    /** What the ledger holds after a plan is applied — the next pass's input. */
    private fun apply(existing: List<CashRefSum>, plan: List<CashMirrorRow>): List<CashRefSum> {
        val sums = existing.associate { it.refId to it.total }.toMutableMap()
        for (row in plan) sums[row.refId] = (sums[row.refId] ?: 0.0) + row.amount
        return sums.map { CashRefSum(it.key, it.value) }
    }

    // ────────────────────────── the arithmetic the till uses ──────────────────────────

    @Test
    fun netCashIsTendersLessTheChangeActuallyHandedBack() {
        // $100 handed over on an $80 sale, $20 given back: $80 stays in the drawer.
        assertEquals(80.0, netCashForSale(cashTendered = 100.0, changeGiven = 20.0), eps)
        // Nothing given back yet (change owed) — the whole tender is still in the drawer.
        assertEquals(100.0, netCashForSale(cashTendered = 100.0, changeGiven = 0.0), eps)
    }

    // ────────────────────────── a pulled sale becomes cash ──────────────────────────

    @Test
    fun aPulledSaleWithNoLedgerRowGetsOne() {
        val plan = planCashMirror("sale", listOf(expect("s1", 80.0)), emptyList(), false)
        assertEquals(1, plan.size)
        assertEquals("sale", plan[0].refType)
        assertEquals("s1", plan[0].refId)
        assertEquals(80.0, plan[0].amount, eps)
    }

    /**
     * THE IDEMPOTENCY TEST, and the reason the rule is stated as a balance rather than as an
     * event. A second pass must find nothing — otherwise every sync adds another copy of the
     * same takings and the drawer grows on its own.
     */
    @Test
    fun runningItTwiceWritesTheRowOnce() {
        val expected = listOf(expect("s1", 80.0))
        val first = planCashMirror("sale", expected, emptyList(), false)
        assertEquals(1, first.size)
        val second = planCashMirror("sale", expected, apply(emptyList(), first), false)
        assertTrue(second.isEmpty())
        // And a third, and a hundredth.
        assertTrue(planCashMirror("sale", expected, apply(emptyList(), first), false).isEmpty())
    }

    /**
     * The phone that RANG THE SALE UP already wrote its row at checkout. The same rule runs
     * there too, and must do nothing — this is what makes it safe to run everywhere rather
     * than only on the pulling side.
     */
    @Test
    fun theOriginatingTillIsLeftAlone() {
        val plan = planCashMirror("sale", listOf(expect("s1", 80.0)), held("s1" to 80.0), false)
        assertTrue(plan.isEmpty())
    }

    /** The whole point: both phones end up describing the same physical drawer. */
    @Test
    fun aPulledSaleAndALocallyRungSaleConvergeOnTheSameDrawer() {
        val expected = listOf(expect("s1", 80.0))
        // Phone A rang it up: the row is already there, the rule adds nothing.
        val phoneA = apply(held("s1" to 80.0), planCashMirror("sale", expected, held("s1" to 80.0), false))
        // Phone B only ever pulled it: the rule supplies the row.
        val phoneB = apply(emptyList(), planCashMirror("sale", expected, emptyList(), false))
        assertEquals(phoneA.sumOf { it.total }, phoneB.sumOf { it.total }, eps)
        assertEquals(80.0, phoneB.sumOf { it.total }, eps)
    }

    @Test
    fun aCardOnlySaleMovesNoCash() {
        // The query reports 0 for a sale with no cash tender; nothing should be written.
        assertTrue(planCashMirror("sale", listOf(expect("s1", 0.0)), emptyList(), false).isEmpty())
    }

    @Test
    fun changeHandedBackReducesWhatIsBooked() {
        val plan = planCashMirror("sale", listOf(expect("s1", netCashForSale(100.0, 20.0))), emptyList(), false)
        assertEquals(80.0, plan.single().amount, eps)
    }

    /** Doubles do not compare exactly. Two clients a fraction of a cent apart must not
     *  write each other correction rows for the rest of time. */
    @Test
    fun aSubCentDisagreementIsNotWorthARow() {
        assertTrue(planCashMirror("sale", listOf(expect("s1", 80.0)), held("s1" to 80.004), false).isEmpty())
        assertEquals(1, planCashMirror("sale", listOf(expect("s1", 80.0)), held("s1" to 79.98), false).size)
    }

    @Test
    fun aPartiallyBookedSaleIsToppedUpByTheDifferenceOnly() {
        // e.g. the tenders arrived across two pulls. Only the shortfall is written.
        val plan = planCashMirror("sale", listOf(expect("s1", 80.0)), held("s1" to 30.0), false)
        assertEquals(50.0, plan.single().amount, eps)
    }

    // ────────────────────────── refunds leave the drawer ──────────────────────────

    @Test
    fun aPulledRefundTakesCashOut() {
        val plan = planCashMirror("refund", listOf(expect("r1", -25.0)), emptyList(), true)
        assertEquals(-25.0, plan.single().amount, eps)
        assertEquals("refund", plan.single().refType)
    }

    @Test
    fun aRefundPaidOutInInstalmentsAccumulatesRatherThanDuplicating() {
        // Local phone wrote -10 at creation; a second payout of -15 has since been pulled.
        val plan = planCashMirror("refund", listOf(expect("r1", -25.0)), held("r1" to -10.0), true)
        assertEquals(-15.0, plan.single().amount, eps)
        // Applied, the ledger holds exactly the refund's cash — once.
        assertEquals(-25.0, apply(held("r1" to -10.0), plan).single().total, eps)
    }

    /**
     * VOIDING. The phone that voids soft-deletes the refund and writes its own reversing
     * row, so the id drops out of the expected set. A phone that had already mirrored the
     * payout has to write the same reversal or that cash stays out of its drawer forever.
     */
    @Test
    fun aVoidedRefundIsReversedOnTheOtherPhoneToo() {
        val plan = planCashMirror("refund", emptyList(), held("r1" to -25.0), true)
        assertEquals(25.0, plan.single().amount, eps)
        assertEquals(0.0, apply(held("r1" to -25.0), plan).single().total, eps)
    }

    @Test
    fun theVoidingPhoneItselfHasNothingLeftToDo() {
        // It wrote -25 then +25 of its own accord; the running total is already zero.
        assertTrue(planCashMirror("refund", emptyList(), held("r1" to 0.0), true).isEmpty())
    }

    /**
     * ...and the same reversal must NOT happen for sales. "Absent from the expected set"
     * covers a parked sale, a quote and a sale mid-edit as well as a deleted one, and
     * reversing real takings on the strength of an absence loses money invisibly.
     */
    @Test
    fun anUnrecognisedSaleIdIsNeverReversed() {
        assertTrue(planCashMirror("sale", emptyList(), held("s1" to 80.0), false).isEmpty())
    }

    // ────────────────────────── what the row carries ──────────────────────────

    @Test
    fun theRowKeepsTheSalesOwnTimestampAndAuthor() {
        // Stamped with the sync time instead, a phone coming back online after midnight
        // would file yesterday's takings in today's cash-up.
        val plan = planCashMirror("sale", listOf(expect("s1", 80.0, at = 777L, label = "R-9", by = "u1")), emptyList(), false)
        val row = plan.single()
        assertEquals(777L, row.at)
        assertEquals("R-9", row.label)
        assertEquals("u1", row.createdBy)
    }

    @Test
    fun aReversalCarriesNoBorrowedTimestamp() {
        // There is no source row left to read one off; the caller fills in the clock.
        val row = planCashMirror("refund", emptyList(), held("r1" to -25.0), true).single()
        assertEquals(0L, row.at)
        assertEquals(null, row.createdBy)
    }

    /**
     * These rows must never be pushed: the shared cash-up already derives a sale's cash from
     * the tenders and a refund's from the payouts, so uploading them doubles the drawer on
     * the other side. Both refTypes used here are covered by that exclusion — a new one
     * would have to be added there in the same change.
     */
    @Test
    fun everyRefTypeThisFileEmitsIsExcludedFromThePush() {
        assertTrue(cashMovementCountedElsewhere("sale"))
        assertTrue(cashMovementCountedElsewhere("refund"))
    }

    // ────────────────────────── determinism ──────────────────────────

    @Test
    fun thePlanDoesNotDependOnTheOrderTheRowsArrive() {
        val expected = listOf(expect("s3", 10.0), expect("s1", 80.0), expect("s2", 0.0), expect("s4", 5.0))
        val existing = held("s1" to 30.0, "s4" to 5.0)
        val baseline = planCashMirror("sale", expected, existing, false)
        val r = Random(7)
        repeat(200) {
            assertEquals(baseline, planCashMirror("sale", expected.shuffled(r), existing.shuffled(r), false))
        }
        // s2 nets zero and s4 already agrees, so only s1 and s3 need a row.
        assertEquals(listOf("s1", "s3"), baseline.map { it.refId })
    }

    @Test
    fun aShopWithNothingToReconcilePlansNothing() {
        assertTrue(planCashMirror("sale", emptyList(), emptyList(), false).isEmpty())
        assertTrue(planCashMirror("refund", emptyList(), emptyList(), true).isEmpty())
    }
}
