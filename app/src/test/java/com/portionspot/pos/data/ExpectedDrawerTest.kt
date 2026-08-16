package com.portionspot.pos.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ★ THE CASH-UP, PINNED.
 *
 * An expected drawer is not a report — it is the number a physical count is measured
 * against, and the difference is written down permanently as a till short/over with a
 * cashier's name on it. Every defect below therefore ends the same way: someone is
 * accused of taking money that was never missing, or a real shortage is hidden.
 *
 * The Z-report's old derivation was wrong three ways at once (see `ExpectedDrawer.kt`),
 * and each has a test here that fails if it comes back:
 *
 *  1. it read the sale HEADER's payment method, so a SPLIT sale contributed no cash;
 *  2. it was capped at the dashboard's 100-sale list, so a busy day truncated;
 *  3. it ignored refund payouts, cash-paid expenses and till↔safe transfers.
 *
 * Plus the two quieter ones: a `refunded` sale was dropped although its cash is still in
 * the drawer, and a second-currency tender must contribute its BASE amount, never the
 * face value of the notes.
 */
class ExpectedDrawerTest {

    /** The money tolerance the whole codebase compares with: half a cent. */
    private val CENT = 0.005

    private fun receipt(
        id: String,
        total: Double,
        change: Double = 0.0,
        status: String = "completed",
    ) = DrawerReceipt(saleId = id, status = status, total = total, changeGiven = change)

    private fun tender(
        saleId: String,
        method: String,
        amount: Double,
        currency: String? = null,
        face: Double? = null,
    ) = DrawerTender(saleId, method, amount, currency, face)

    private fun payout(method: String, amount: Double) = DrawerPayout(method, amount)

    private fun movement(type: String, amount: Double) = DrawerMovement(type, amount)

    private fun drawer(
        opening: Double = 0.0,
        receipts: List<DrawerReceipt> = emptyList(),
        tenders: List<DrawerTender> = emptyList(),
        payouts: List<DrawerPayout> = emptyList(),
        movements: List<DrawerMovement> = emptyList(),
    ) = expectedDrawer(opening, receipts, tenders, payouts, movements)

    // ───────────────────────── 1. the split-tender bug ─────────────────────────

    /**
     * THE BUG THAT MOTIVATED ALL OF THIS. A $50-cash + $30-EcoCash sale has
     * `SaleEntity.paymentMethod == "split"`, so the old filter `paymentMethod == "cash"`
     * matched nothing and the sale put ZERO into the expected drawer — while $50 of real
     * notes sat in it. Count the drawer and you are $50 "over", every single time.
     */
    @Test
    fun aSplitSaleContributesOnlyItsCashPart() {
        val d = drawer(
            receipts = listOf(receipt("s1", total = 80.0)),
            tenders = listOf(
                tender("s1", "cash", 50.0),
                tender("s1", "ecocash", 30.0),
            ),
        )
        assertEquals(50.0, d.cashIn, CENT)
        assertEquals(30.0, d.nonCashIn, CENT)
        assertEquals(50.0, d.expected, CENT)
        // The receipt is still worth $80 — the drawer just does not hold all of it.
        assertEquals(80.0, d.salesValue, CENT)
        assertEquals(1, d.receipts)
    }

    /** Card-only: real revenue, nothing in the drawer. The figure that used to be
     *  right by accident must stay right on purpose. */
    @Test
    fun aCardOnlySaleAddsNothingToTheDrawer() {
        val d = drawer(
            receipts = listOf(receipt("s1", total = 80.0)),
            tenders = listOf(tender("s1", "card", 80.0)),
        )
        assertEquals(0.0, d.cashIn, CENT)
        assertEquals(0.0, d.expected, CENT)
    }

    // ───────────────────────── 2. the 100-receipt cap ─────────────────────────

    /**
     * The old figure came off `observeRecent(100)`. Receipt 101 onward vanished, so a busy
     * day's count came up "short" by everything after the hundredth sale — the busier the
     * shop, the bigger the phantom shortage. Nothing in this path is bounded by count.
     */
    @Test
    fun everyReceiptOfABusyDayCounts() {
        val receipts = (1..150).map { receipt("s$it", total = 10.0) }
        val tenders = (1..150).map { tender("s$it", "cash", 10.0) }
        val d = drawer(receipts = receipts, tenders = tenders)
        assertEquals(150, d.receipts)
        assertEquals(1500.0, d.cashIn, CENT)
        assertEquals(1500.0, d.expected, CENT)
    }

    // ───────────────────── 3. money that left or moved ─────────────────────

    /** Cash handed back on a refund physically leaves the drawer. It was invisible. */
    @Test
    fun aCashRefundPayoutLowersTheExpectedDrawer() {
        val base = drawer(
            receipts = listOf(receipt("s1", 100.0)),
            tenders = listOf(tender("s1", "cash", 100.0)),
        )
        val withRefund = drawer(
            receipts = listOf(receipt("s1", 100.0)),
            tenders = listOf(tender("s1", "cash", 100.0)),
            payouts = listOf(payout("cash", 25.0)),
        )
        assertEquals(100.0, base.expected, CENT)
        assertEquals(75.0, withRefund.expected, CENT)
        assertEquals(25.0, withRefund.cashRefunded, CENT)
    }

    /** A reversal to a card or wallet never opened the drawer, so it must not move the
     *  count. Subtracting it would manufacture an "over" the size of the refund. */
    @Test
    fun aNonCashRefundPayoutDoesNotMoveTheDrawer() {
        val d = drawer(
            receipts = listOf(receipt("s1", 100.0)),
            tenders = listOf(tender("s1", "cash", 100.0)),
            payouts = listOf(payout("ecocash", 25.0), payout("card", 10.0)),
        )
        assertEquals(0.0, d.cashRefunded, CENT)
        assertEquals(35.0, d.nonCashRefunded, CENT)
        assertEquals(100.0, d.expected, CENT)
    }

    /** An expense paid out of the till empties it; that money is spent, not missing. */
    @Test
    fun aCashFundedExpenseLowersTheExpectedDrawer() {
        val d = drawer(
            receipts = listOf(receipt("s1", 100.0)),
            tenders = listOf(tender("s1", "cash", 100.0)),
            movements = listOf(movement("expense", -40.0)),
        )
        assertEquals(-40.0, d.otherMovements, CENT)
        assertEquals(60.0, d.expected, CENT)
    }

    /** The waterfall in both directions: takings moved to the safe leave the drawer, a
     *  float top-up brought back from the safe puts money into it. */
    @Test
    fun tillToSafeAndBackMoveTheDrawerOppositeWays() {
        val out = drawer(
            opening = 200.0,
            movements = listOf(movement("transfer_out", -150.0)),
        )
        assertEquals(50.0, out.expected, CENT)

        val back = drawer(
            opening = 200.0,
            movements = listOf(movement("transfer_in", 60.0)),
        )
        assertEquals(260.0, back.expected, CENT)

        // A same-day close that moved takings and was then topped back up nets out.
        val both = drawer(
            opening = 200.0,
            movements = listOf(movement("transfer_out", -150.0), movement("transfer_in", 60.0)),
        )
        assertEquals(110.0, both.expected, CENT)
    }

    /** Every component in one story, so the arithmetic on screen is the arithmetic here. */
    @Test
    fun theWholeDayReconciles() {
        val d = drawer(
            opening = 120.0,
            receipts = listOf(
                receipt("s1", total = 80.0, change = 20.0),   // $100 handed over
                receipt("s2", total = 50.0),
            ),
            tenders = listOf(
                tender("s1", "cash", 100.0),
                tender("s2", "cash", 20.0),
                tender("s2", "card", 30.0),
            ),
            payouts = listOf(payout("cash", 15.0)),
            movements = listOf(movement("expense", -40.0), movement("transfer_out", -25.0)),
        )
        // 120 opening + (120 cash in − 20 change) − 15 refunded − 65 moved out = 140
        assertEquals(120.0, d.cashIn, CENT)
        assertEquals(20.0, d.changeGiven, CENT)
        assertEquals(100.0, d.netCashFromSales, CENT)
        assertEquals(140.0, d.expected, CENT)
        assertEquals(130.0, d.salesValue, CENT)
    }

    // ─────────────────── the dropped `refunded` receipt ───────────────────

    /**
     * A sale that has been partly returned is `status = 'refunded'`, and the old filter
     * `status == "completed"` threw it away — cash and all. The receipt still happened, the
     * notes are still in the drawer, and the refund is subtracted separately through its
     * own payout row. Dropping it double-counts the reversal.
     */
    @Test
    fun aRefundedSaleIsStillAReceipt() {
        val d = drawer(
            receipts = listOf(receipt("s1", 100.0, status = "refunded")),
            tenders = listOf(tender("s1", "cash", 100.0)),
            payouts = listOf(payout("cash", 40.0)),
        )
        assertEquals(1, d.receipts)
        assertEquals(100.0, d.cashIn, CENT)
        assertEquals(60.0, d.expected, CENT)
    }

    /** Both members of [RECEIPT_STATUSES] count, and nothing else does. A quote was
     *  priced, never sold; a parked cart is still on the counter; a void is reversed. */
    @Test
    fun onlyRealReceiptsCountAndTheirTendersWithThem() {
        val d = drawer(
            receipts = listOf(
                receipt("done", 10.0, status = "completed"),
                receipt("part", 10.0, status = "refunded"),
                receipt("quote", 999.0, status = "quote"),
                receipt("park", 999.0, status = "parked"),
                receipt("void", 999.0, status = "void"),
            ),
            tenders = listOf(
                tender("done", "cash", 10.0),
                tender("part", "cash", 10.0),
                tender("quote", "cash", 999.0),
                tender("park", "cash", 999.0),
                tender("void", "cash", 999.0),
            ),
        )
        assertEquals(2, d.receipts)
        assertEquals(20.0, d.cashIn, CENT)
        assertEquals(20.0, d.salesValue, CENT)
        assertTrue("statuses drifted from RECEIPT_STATUSES", RECEIPT_STATUSES.containsAll(listOf("completed", "refunded")))
    }

    /** A tender whose receipt is outside the window cannot reach this drawer either. */
    @Test
    fun aTenderWithNoReceiptInTheWindowIsIgnored() {
        val d = drawer(
            receipts = listOf(receipt("s1", 10.0)),
            tenders = listOf(tender("s1", "cash", 10.0), tender("yesterday", "cash", 500.0)),
        )
        assertEquals(10.0, d.cashIn, CENT)
    }

    // ─────────────────── dual currency: base amount only ───────────────────

    /**
     * ★ A ZiG tender of 340 ZWG worth $10 puts TEN DOLLARS of value through the books.
     * `amount` is base currency and is the only figure any sum may read; the trio travels
     * with the row for the cash-up's currency split. Summing 340 into a USD drawer would
     * report a thirty-fold "over" and send someone looking for money that never existed.
     */
    @Test
    fun aDualCurrencyTenderContributesItsBaseAmountNotItsFaceValue() {
        val d = drawer(
            receipts = listOf(receipt("s1", 10.0)),
            tenders = listOf(tender("s1", "cash", amount = 10.0, currency = "ZWG", face = 340.0)),
        )
        assertEquals(10.0, d.cashIn, CENT)
        assertEquals(10.0, d.expected, CENT)
        // The face value is kept, but only as its own display figure.
        assertEquals(340.0, d.foreignTenders["ZWG"] ?: 0.0, CENT)
    }

    /** Several ZiG tenders accumulate per currency, and a base-currency tender adds none. */
    @Test
    fun theCurrencySplitIsPerCurrencyAndNeverTouchesTheBaseTotal() {
        val d = drawer(
            receipts = listOf(receipt("s1", 30.0), receipt("s2", 10.0)),
            tenders = listOf(
                tender("s1", "cash", 10.0, "ZWG", 340.0),
                tender("s1", "cash", 20.0, "ZWG", 680.0),
                tender("s2", "cash", 10.0),
            ),
        )
        assertEquals(40.0, d.cashIn, CENT)
        assertEquals(1020.0, d.foreignTenders["ZWG"] ?: 0.0, CENT)
        assertEquals(1, d.foreignTenders.size)
    }

    // ───────────────────────── opening, change, variance ─────────────────────────

    /** The float the drawer started with is part of what should be in it — and it comes
     *  from the ledger, not from whatever a cashier types. */
    @Test
    fun theOpeningBalanceIsPartOfTheExpectedFigure() {
        val d = drawer(opening = 75.0, receipts = listOf(receipt("s1", 20.0)),
            tenders = listOf(tender("s1", "cash", 20.0)))
        assertEquals(95.0, d.expected, CENT)
    }

    /**
     * Change ACTUALLY handed back comes off; change the shop could not give back does not.
     * The latter is a liability living in the credit ledger, and the notes are still in the
     * drawer — subtracting it would invent a shortage the size of the change owed.
     */
    @Test
    fun onlyChangeActuallyHandedBackComesOff() {
        val handed = drawer(
            receipts = listOf(receipt("s1", total = 80.0, change = 20.0)),
            tenders = listOf(tender("s1", "cash", 100.0)),
        )
        assertEquals(80.0, handed.expected, CENT)

        val owed = drawer(
            receipts = listOf(receipt("s1", total = 80.0, change = 0.0)),
            tenders = listOf(tender("s1", "cash", 100.0)),
        )
        assertEquals(100.0, owed.expected, CENT)
    }

    /** Short is negative, over is positive — the same sign convention `closeDay` writes
     *  its permanent `variance` row with. */
    @Test
    fun varianceIsCountedMinusExpected() {
        val d = drawer(opening = 100.0)
        assertEquals(0.0, d.variance(100.0), CENT)
        assertEquals(-15.0, d.variance(85.0), CENT)
        assertEquals(15.0, d.variance(115.0), CENT)
    }

    /** An empty day is not an error: nothing sold, nothing moved, the float is the answer. */
    @Test
    fun anEmptyDayExpectsTheFloatBack() {
        val d = drawer(opening = 60.0)
        assertEquals(60.0, d.expected, CENT)
        assertEquals(0, d.receipts)
        assertTrue(d.movements.isEmpty())
    }
}
