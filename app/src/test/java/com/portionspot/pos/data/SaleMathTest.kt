package com.portionspot.pos.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the sale money math — the single most correctness-critical calculation in
 * the app. The same function backs [PosRepository.checkout].
 */
class SaleMathTest {
    private val eps = 1e-9

    @Test
    fun noVat_noDiscount_totalEqualsSubtotal() {
        val t = computeSaleTotals(subtotal = 100.0, discount = 0.0, vatEnabled = false, vatPercent = 15.0)
        assertEquals(100.0, t.subtotal, eps)
        assertEquals(0.0, t.discount, eps)
        assertEquals(100.0, t.taxableBase, eps)
        assertEquals(0.0, t.taxTotal, eps)
        assertEquals(100.0, t.total, eps)
    }

    @Test
    fun vat_appliedOnFullSubtotal_whenNoDiscount() {
        val t = computeSaleTotals(200.0, 0.0, vatEnabled = true, vatPercent = 15.0)
        assertEquals(30.0, t.taxTotal, eps)   // 15% of 200
        assertEquals(230.0, t.total, eps)
    }

    @Test
    fun vat_chargedOnDiscountedBase_notGross() {
        // 100 goods − 20 discount => taxable 80; 15% VAT = 12; total 92.
        val t = computeSaleTotals(100.0, 20.0, vatEnabled = true, vatPercent = 15.0)
        assertEquals(20.0, t.discount, eps)
        assertEquals(80.0, t.taxableBase, eps)
        assertEquals(12.0, t.taxTotal, eps)
        assertEquals(92.0, t.total, eps)
    }

    @Test
    fun discount_clampedToSubtotal() {
        // A discount larger than the goods can't drive the total below zero.
        val t = computeSaleTotals(50.0, 80.0, vatEnabled = false, vatPercent = 15.0)
        assertEquals(50.0, t.discount, eps)   // clamped down to subtotal
        assertEquals(0.0, t.taxableBase, eps)
        assertEquals(0.0, t.total, eps)
    }

    @Test
    fun negativeDiscount_clampedToZero() {
        val t = computeSaleTotals(50.0, -10.0, vatEnabled = false, vatPercent = 15.0)
        assertEquals(0.0, t.discount, eps)
        assertEquals(50.0, t.total, eps)
    }

    @Test
    fun emptyCart_isAllZero() {
        val t = computeSaleTotals(0.0, 0.0, vatEnabled = true, vatPercent = 15.0)
        assertEquals(0.0, t.taxTotal, eps)
        assertEquals(0.0, t.total, eps)
    }

    @Test
    fun vatDisabled_ignoresPercent() {
        val t = computeSaleTotals(100.0, 0.0, vatEnabled = false, vatPercent = 15.0)
        assertEquals(0.0, t.taxTotal, eps)
        assertEquals(100.0, t.total, eps)
    }

    @Test
    fun fractionalVatPercent_isApplied() {
        // Non-integer VAT rates must not be rounded away.
        val t = computeSaleTotals(100.0, 0.0, vatEnabled = true, vatPercent = 14.5)
        assertEquals(14.5, t.taxTotal, eps)
        assertEquals(114.5, t.total, eps)
    }

    @Test
    fun fullDiscountEqualToSubtotal_zeroesTaxAndTotal() {
        // Comp'd sale: discount == subtotal => nothing taxable, nothing owed.
        val t = computeSaleTotals(100.0, 100.0, vatEnabled = true, vatPercent = 15.0)
        assertEquals(100.0, t.discount, eps)
        assertEquals(0.0, t.taxableBase, eps)
        assertEquals(0.0, t.taxTotal, eps)
        assertEquals(0.0, t.total, eps)
    }

    @Test
    fun vatOnDiscountedBase_withNonRoundNumbers() {
        // 250 goods − 50 discount => 200 taxable; 15% => 30 tax; 230 total.
        val t = computeSaleTotals(250.0, 50.0, vatEnabled = true, vatPercent = 15.0)
        assertEquals(200.0, t.taxableBase, eps)
        assertEquals(30.0, t.taxTotal, eps)
        assertEquals(230.0, t.total, eps)
    }

    @Test
    fun largeSubtotal_keepsCentPrecision() {
        val t = computeSaleTotals(9999.99, 0.0, vatEnabled = true, vatPercent = 15.0)
        assertEquals(1499.9985, t.taxTotal, eps)
        assertEquals(11499.9885, t.total, eps)
    }
}
