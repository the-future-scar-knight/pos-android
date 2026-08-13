package com.portionspot.pos.data

import com.portionspot.pos.payments.PaymentMethod
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * DEBT REPAYMENTS — the till moves for cash and for nothing else.
 *
 * ══ The bug this pins ══
 * The repayment chain used to write credit-ledger rows and NO cash movement at all. A
 * customer settling an account in notes put money in the drawer and the app's
 * cash-on-hand did not move, on any phone. At day close the drawer counted OVER by every
 * repayment ever collected, and [PosRepository.closeDay] writes that difference
 * permanently as a `variance` — which by the owner's rule is a hit to profit. A day that
 * balanced perfectly was recorded as a windfall.
 *
 * [PosRepository.planRepayment] is pure precisely so this can be asserted without Room:
 * it decides whether real money enters the drawer, and the two properties that matter are
 * that the CREDIT effect is identical whatever the tender, and that the CASH effect is
 * not.
 */
class RepaymentTest {

    private fun plan(amount: Double, debt: Double, method: String) =
        PosRepository.planRepayment(amount, debt, method)

    private val cash = PaymentMethod.CASH.code
    private val ecocash = PaymentMethod.ECOCASH.code
    private val card = PaymentMethod.CARD.code

    // ── Cash moves the till; nothing else does ───────────────────────────

    /** ★ Notes across the counter land in the drawer. */
    @Test
    fun `a cash repayment puts the money in the till`() {
        val p = plan(amount = 40.0, debt = 100.0, method = cash)
        assertEquals(40.0, p.cashIn, 0.0001)
    }

    /** ★ EcoCash settles the account without a note reaching the drawer. */
    @Test
    fun `a mobile-money repayment does not move the till`() {
        val p = plan(amount = 40.0, debt = 100.0, method = ecocash)
        assertEquals(0.0, p.cashIn, 0.0001)
    }

    /** ★ Neither does a card swipe. */
    @Test
    fun `a card repayment does not move the till`() {
        val p = plan(amount = 40.0, debt = 100.0, method = card)
        assertEquals(0.0, p.cashIn, 0.0001)
    }

    /** Every non-cash tender the shop can offer behaves the same way. */
    @Test
    fun `only cash of all the checkout tenders opens the drawer`() {
        for (m in PaymentMethod.entries) {
            val p = plan(amount = 25.0, debt = 50.0, method = m.code)
            val expected = if (m == PaymentMethod.CASH) 25.0 else 0.0
            assertEquals("tender ${m.code} moved the wrong amount of cash", expected, p.cashIn, 0.0001)
        }
    }

    /** An unrecognised code is not cash, so it must not invent a drawer movement. */
    @Test
    fun `an unknown tender code never moves the till`() {
        assertEquals(0.0, plan(25.0, 50.0, "voucher").cashIn, 0.0001)
        assertEquals(0.0, plan(25.0, 50.0, "").cashIn, 0.0001)
    }

    /** The code is compared the way the rest of the app writes it, not case-sensitively. */
    @Test
    fun `a differently-cased cash code is still cash`() {
        assertEquals(25.0, plan(25.0, 50.0, "Cash").cashIn, 0.0001)
        assertEquals(25.0, plan(25.0, 50.0, " cash ").cashIn, 0.0001)
    }

    // ── The credit ledger is blind to the tender ─────────────────────────

    /** ★ THE INVARIANT: what the debt does is decided by the money, never by how it
     *  arrived. If this ever diverges, an EcoCash payment stops clearing an account. */
    @Test
    fun `the credit effect is identical for every tender`() {
        val cases = listOf(
            40.0 to 100.0,      // part payment
            100.0 to 100.0,     // exact settlement
            150.0 to 100.0,     // overpayment
            30.0 to 0.0         // paying an account that owes nothing
        )
        for ((amount, debt) in cases) {
            val reference = plan(amount, debt, cash)
            for (m in PaymentMethod.entries) {
                val p = plan(amount, debt, m.code)
                assertEquals(
                    "tender ${m.code} settled a different amount of debt",
                    reference.paid, p.paid, 0.0001
                )
                assertEquals(
                    "tender ${m.code} booked a different overpayment",
                    reference.excess, p.excess, 0.0001
                )
            }
        }
    }

    // ── The split itself ─────────────────────────────────────────────────

    /** Debt never goes negative: the excess becomes money the shop owes back. */
    @Test
    fun `an overpayment settles the debt and books the rest as owed back`() {
        val p = plan(amount = 150.0, debt = 100.0, method = cash)
        assertEquals(100.0, p.paid, 0.0001)
        assertEquals(50.0, p.excess, 0.0001)
    }

    /**
     * ★ The WHOLE amount handed over reaches the till, overpayment included. The notes are
     * physically in the drawer; the `change_owed` row is what records that they go back out
     * again. Booking only the settling part would leave the drawer counting over by the
     * excess — the same class of error this whole fix exists to remove.
     */
    @Test
    fun `an overpayment reaches the till in full`() {
        val p = plan(amount = 150.0, debt = 100.0, method = cash)
        assertEquals(150.0, p.cashIn, 0.0001)
        assertEquals(150.0, p.paid + p.excess, 0.0001)
    }

    /** Money paid onto an account that owes nothing is entirely owed back. */
    @Test
    fun `paying a settled account books the whole amount as owed back`() {
        val p = plan(amount = 30.0, debt = 0.0, method = cash)
        assertEquals(0.0, p.paid, 0.0001)
        assertEquals(30.0, p.excess, 0.0001)
        assertEquals(30.0, p.cashIn, 0.0001)
    }

    /**
     * A negative balance means the SHOP owes the customer (change or an unpaid refund),
     * not that the customer owes less than nothing. It settles no debt.
     */
    @Test
    fun `a credit balance settles no debt`() {
        val p = plan(amount = 20.0, debt = -75.0, method = cash)
        assertEquals(0.0, p.paid, 0.0001)
        assertEquals(20.0, p.excess, 0.0001)
    }

    /** Nothing handed over does nothing at all — no ledger row, no drawer movement. */
    @Test
    fun `a zero or negative amount does nothing`() {
        for (amount in listOf(0.0, -10.0, 0.001)) {
            val p = plan(amount, 100.0, cash)
            assertEquals(0.0, p.paid, 0.0001)
            assertEquals(0.0, p.excess, 0.0001)
            assertEquals(0.0, p.cashIn, 0.0001)
        }
    }

    /** ★ The plan always accounts for exactly what was handed over. */
    @Test
    fun `every plan accounts for the full amount received`() {
        val amounts = listOf(0.5, 40.0, 100.0, 150.0, 999.99)
        val debts = listOf(-20.0, 0.0, 40.0, 100.0, 500.0)
        for (amount in amounts) {
            for (debt in debts) {
                for (m in listOf(cash, ecocash, card)) {
                    val p = plan(amount, debt, m)
                    assertEquals(
                        "amount=$amount debt=$debt method=$m did not add up",
                        amount, p.paid + p.excess, 0.0001
                    )
                }
            }
        }
    }
}
