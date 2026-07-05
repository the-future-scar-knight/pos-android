package com.portionspot.pos.data

/**
 * Dual-currency helpers for a Zimbabwe-style shop that keeps its books in ONE
 * base currency (typically USD) but also accepts a SECOND currency (e.g. ZiG) as
 * tender at a daily exchange [rate].
 *
 * Design (mirrors how the shops actually run, and keeps the change contained):
 *  - Prices, sale totals and every stored `amount` stay in the BASE currency, so
 *    the core money math ([computeSaleTotals]) and all historical reports are
 *    left completely untouched.
 *  - The second currency exists only at the TENDER / CHANGE / PRINT layer: a
 *    cashier may pay or receive change in it, converted at [rate].
 *
 * [rate] is the number of SECOND-currency units per 1 BASE unit — e.g.
 * `rate = 30.0` means "Z$30 = $1". A rate of 0 (or less) means the shop has not
 * configured a second currency, and every helper degrades to the single-currency
 * behaviour (no conversion, no divide-by-zero).
 *
 * Kept as pure top-level functions (no Room, no Android) so they unit-test on the
 * JVM exactly like [computeSaleTotals] — see CurrencyMathTest.
 */

/** True only when a usable second currency is configured (has a code AND a positive rate). */
fun secondCurrencyActive(code: String?, rate: Double): Boolean =
    !code.isNullOrBlank() && rate > 0.0

/**
 * Convert a BASE amount into the second currency at [rate].
 * Returns 0 when no rate is set, so callers can show the result unconditionally.
 */
fun baseToSecond(base: Double, rate: Double): Double =
    if (rate > 0.0) base * rate else 0.0

/**
 * Convert a SECOND-currency amount back into the base currency at [rate].
 * Guards against divide-by-zero: an unset rate yields 0 base value.
 */
fun secondToBase(second: Double, rate: Double): Double =
    if (rate > 0.0) second / rate else 0.0
