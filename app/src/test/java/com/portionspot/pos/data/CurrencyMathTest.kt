package com.portionspot.pos.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the dual-currency conversion — the base<->second maths a cashier relies on
 * when a customer pays USD-priced goods in ZiG (or takes change in the other
 * currency). The base value must always survive a round-trip so the books balance.
 */
class CurrencyMathTest {
    private val eps = 1e-9

    @Test
    fun active_requiresCodeAndPositiveRate() {
        assertTrue(secondCurrencyActive("ZWG", 30.0))
        assertFalse("null code is off", secondCurrencyActive(null, 30.0))
        assertFalse("blank code is off", secondCurrencyActive("", 30.0))
        assertFalse("zero rate is off", secondCurrencyActive("ZWG", 0.0))
        assertFalse("negative rate is off", secondCurrencyActive("ZWG", -5.0))
    }

    @Test
    fun baseToSecond_multipliesByRate() {
        // $100 of goods at Z$30 = $1 shows as Z$3000 on the tender line.
        assertEquals(3000.0, baseToSecond(100.0, 30.0), eps)
    }

    @Test
    fun baseToSecond_zeroRate_yieldsZero_notNaN() {
        // No second currency configured => 0, so the UI can show it unconditionally.
        assertEquals(0.0, baseToSecond(100.0, 0.0), eps)
    }

    @Test
    fun secondToBase_dividesByRate() {
        // A customer hands over Z$1500 at rate 30 => that covers $50 of the bill.
        assertEquals(50.0, secondToBase(1500.0, 30.0), eps)
    }

    @Test
    fun secondToBase_zeroRate_guardsDivideByZero() {
        assertEquals(0.0, secondToBase(1500.0, 0.0), eps)
    }

    @Test
    fun roundTrip_preservesBaseValue() {
        // Converting base -> second -> base must return the original to the cent,
        // even at an ugly rate, or the ledger would drift.
        val rate = 27.5
        val base = 42.37
        assertEquals(base, secondToBase(baseToSecond(base, rate), rate), 1e-9)
    }

    @Test
    fun fractionalRate_isApplied() {
        // Non-integer daily rates are common and must not be rounded away.
        assertEquals(137.5, baseToSecond(5.0, 27.5), eps)
    }
}
