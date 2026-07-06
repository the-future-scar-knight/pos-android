package com.portionspot.pos.ui

/**
 * Device-local shop preferences that aren't part of the synced [Business] record:
 * receipt-template look, tax/price rounding, margin formula, and which printer
 * the shop uses. They live in the local key/value `settings` store (like the
 * theme), so they never touch the cloud schema and survive app restarts.
 *
 * Faithful to the web app's local "shop settings": the same knobs the cashier
 * tweaks under Settings on portionspot-v11.5.
 */
data class ShopPrefs(
    // ── Receipt template ──
    val receiptFontScale: Float = 1.0f,      // 0.85 small · 1.0 normal · 1.2 large
    val receiptFeedLines: Int = 3,           // blank lines fed after printing (cut clearance)
    val receiptBoldName: Boolean = true,     // shop name printed bold
    val receiptShowLogo: Boolean = true,
    val receiptShowTagline: Boolean = true,
    val receiptShowAddress: Boolean = true,
    val receiptShowVat: Boolean = true,      // show the VAT line + VAT number
    val receiptShowFooter: Boolean = true,
    // ── Tax / price rounding ──
    // Rounding step applied to a computed price. 0.0 = off; 0.01/0.05/0.10/0.50/1.0.
    val wholesaleRounding: Double = 0.0,     // applied to wholesale unit prices
    val checkoutRounding: Double = 0.0,      // applied to the checkout grand total
    // ── Margins ──
    val marginFormula: String = "markup",    // "markup" (over cost) or "gross" (of price)
    val autoConvertUnitsToBoxes: Boolean = false, // show stock as N boxes + loose units
    // ── Printer ──
    val printerType: String = "bluetooth",   // "bluetooth" (ESC/POS) · "sunmi" (internal) · "rawbt" (RawBT service)
    // ── Receipt style preset ──
    // A quick look applied over the individual receipt toggles below (Phase 6, §10):
    // "standard" keeps the toggles as-is; "compact"/"detailed" override a few for a
    // tighter or fuller ticket. Presets win only on the knobs they touch.
    val receiptPreset: String = "standard",  // "compact" | "standard" | "detailed"
    // ── Second currency (Zimbabwe dual-currency) ──
    // The shop keeps its books in Business.currency (the BASE, e.g. USD) but may
    // also accept a SECOND currency (e.g. ZiG) at [secondCurrencyRate] units per 1
    // base unit. A blank code OR a rate <= 0 disables the whole feature, so a
    // single-currency shop behaves exactly as before.
    val secondCurrencyCode: String = "",     // e.g. "ZWG"; blank = off
    val secondCurrencyRate: Double = 0.0,    // second-currency units per 1 base unit
    // ── Admin notification thresholds (Phase 7, §8) ──
    val adminLargeSale: Double = 500.0,      // flag sales at/above this amount
    val escalateHours: Int = 4,              // unverified payment / owed refund → escalate after N hours
    val unsyncedHours: Int = 6,              // device unsynced (with pending records) → alert after N hours
)

val DEFAULT_SHOP_PREFS = ShopPrefs()

/** Receipt font-scale presets surfaced as chips in Settings. */
val RECEIPT_FONT_SCALES = listOf(
    0.85f to "Small",
    1.0f to "Normal",
    1.2f to "Large",
)

/** Printer targets surfaced as chips in Settings (Phase 6, §10). */
val PRINTER_TYPES = listOf(
    "bluetooth" to "Bluetooth",
    "sunmi" to "Sunmi",
    "rawbt" to "RawBT",
)

/** Receipt style presets surfaced as chips in Settings (Phase 6, §10). */
val RECEIPT_PRESETS = listOf(
    "compact" to "Compact",
    "standard" to "Standard",
    "detailed" to "Detailed",
)

/** Thermal paper widths surfaced as chips in Settings (drives ESC/POS column count). */
val PAPER_WIDTHS = listOf(
    "58mm" to "58 mm",
    "80mm" to "80 mm",
)

/**
 * Fold a receipt style [preset] over an already-built [base] style. Presets only
 * override the knobs they own, so the individual toggles and the second-currency
 * settings survive. "standard" is a no-op.
 */
fun applyReceiptPreset(base: ReceiptStyleFlags, preset: String): ReceiptStyleFlags = when (preset) {
    "compact" -> base.copy(largeText = false, feedLines = minOf(base.feedLines, 1), showTagline = false, showFooter = false)
    "detailed" -> base.copy(largeText = true, feedLines = maxOf(base.feedLines, 3), showTagline = true, showAddress = true, showFooter = true)
    else -> base
}

/**
 * The subset of [com.portionspot.pos.print.ReceiptStyle] knobs a preset can flip.
 * Kept as a small local record so ShopPrefs doesn't depend on the print module.
 */
data class ReceiptStyleFlags(
    val largeText: Boolean,
    val feedLines: Int,
    val showTagline: Boolean,
    val showAddress: Boolean,
    val showFooter: Boolean,
)

/** Rounding-step presets surfaced as chips for the tax/price rounding pickers. */
val ROUNDING_STEPS = listOf(
    0.0 to "Off",
    0.01 to "1c",
    0.05 to "5c",
    0.10 to "10c",
    0.50 to "50c",
    1.0 to "1.00",
)

/** Apply a rounding [step] to [value] (nearest). step 0 leaves it untouched. */
fun applyRounding(value: Double, step: Double): Double =
    if (step <= 0.0) value else Math.round(value / step) * step
