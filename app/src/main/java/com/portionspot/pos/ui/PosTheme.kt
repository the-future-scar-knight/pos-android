package com.portionspot.pos.ui

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.core.view.WindowCompat

/**
 * Spot POS theme engine — a faithful Compose port of the web app's runtime
 * theme system (src/lib/theme.js). A single signature accent hex drives a full
 * 50→800 "brand ramp"; backgrounds and the side-drawer style are independently
 * pickable. Everything is light-mode (the POS is used in bright workshops).
 *
 * The chosen theme is persisted in the settings store and re-applied on boot,
 * so it follows the shop. Default is Signature Red (#ff3830) — identical to the
 * web's DEFAULT_THEME — but the cashier can switch it under Settings →
 * Appearance, exactly like the web.
 */

// ── Color math (mirrors theme.js exactly) ───────────────────────────────────

private fun clamp255(v: Double): Int = v.coerceIn(0.0, 255.0).toInt()

private fun parseHexTriple(hex: String): Triple<Int, Int, Int> {
    var h = hex.trim().removePrefix("#")
    if (h.length == 3) h = h.map { "$it$it" }.joinToString("")
    if (h.length != 6) h = "ff3830" // graceful fallback to signature red
    val n = h.toLong(16)
    return Triple(((n shr 16) and 255).toInt(), ((n shr 8) and 255).toInt(), (n and 255).toInt())
}

fun hexToColor(hex: String): Color {
    val (r, g, b) = parseHexTriple(hex)
    return Color(r, g, b)
}

private fun rgbToHex(r: Int, g: Int, b: Int): String =
    "#%02x%02x%02x".format(clamp255(r.toDouble()), clamp255(g.toDouble()), clamp255(b.toDouble()))

/** Mix a base color toward white (toWhite) or near-black (!toWhite) by 0..1. */
private fun mix(hex: String, toWhite: Boolean, amt: Double): String {
    val (ar, ag, ab) = parseHexTriple(hex)
    val (br, bg, bb) = if (toWhite) Triple(255, 255, 255) else Triple(17, 18, 20)
    return rgbToHex(
        clamp255(ar + (br - ar) * amt),
        clamp255(ag + (bg - ag) * amt),
        clamp255(ab + (bb - ab) * amt),
    )
}

/** Relative luminance → readable ink color on an arbitrary background. */
fun readableInk(hex: String): Color {
    val (r, g, b) = parseHexTriple(hex)
    val lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
    return if (lum > 0.62) Color(0xFF111827) else Color(0xFFF9FAFB)
}

/** Full brand ramp generated from one signature hex (anchored at 500). */
data class BrandRamp(
    val s50: Color, val s100: Color, val s200: Color, val s400: Color,
    val s500: Color, val s600: Color, val s700: Color, val s800: Color,
)

fun buildRamp(hex: String): BrandRamp = BrandRamp(
    s50  = hexToColor(mix(hex, true, 0.92)),
    s100 = hexToColor(mix(hex, true, 0.84)),
    s200 = hexToColor(mix(hex, true, 0.68)),
    s400 = hexToColor(mix(hex, true, 0.26)),
    s500 = hexToColor(hex),
    s600 = hexToColor(mix(hex, false, 0.14)),
    s700 = hexToColor(mix(hex, false, 0.30)),
    s800 = hexToColor(mix(hex, false, 0.42)),
)

// ── Presets (mirror theme.js) ───────────────────────────────────────────────

data class AccentPreset(val id: String, val name: String, val hex: String)

// A coordinated wheel of accents. Every hex is a mid-tone "500" (roughly equal
// perceived lightness/chroma) so the generated 50→800 ramp lands in the same
// tonal family across themes — the picker reads as one designed set, not a
// random rainbow. Ordered around the colour wheel (green → blue → violet →
// red → orange) with the new default (Pine) first. Every original id is kept so
// a persisted selection never breaks; a few hexes were re-tuned for harmony and
// to sit clearly apart from the FIXED wholesale blue (#1E5BFF) and danger red.
val ACCENT_PRESETS = listOf(
    AccentPreset("pine",    "Pine",          "#0F766E"), // NEW default — deep teal-green
    AccentPreset("emerald", "Emerald",       "#059669"),
    AccentPreset("teal",    "Teal",          "#0D9488"),
    AccentPreset("blue",    "Ocean",         "#0E7490"), // cyan-teal — distinct from the fixed wholesale blue
    AccentPreset("navy",    "Navy",          "#1E3A8A"),
    AccentPreset("indigo",  "Indigo",        "#4F46E5"),
    AccentPreset("violet",  "Violet",        "#7C3AED"),
    AccentPreset("rose",    "Rose",          "#E11D48"),
    AccentPreset("crimson", "Crimson",       "#DC2626"),
    AccentPreset("red",     "Signature Red", "#FF3830"), // web-parity anchor — kept exact
    AccentPreset("orange",  "Sunset",        "#EA580C"),
    AccentPreset("amber",   "Amber",         "#D97706"),
    AccentPreset("gold",    "Gold",          "#B8860B"),
    AccentPreset("slate",   "Graphite",      "#475569"),
)

data class BackgroundPreset(
    val id: String,
    val name: String,
    val surface0: Color,      // solid fallback / Material background
    val gradient: List<Color>? = null, // when set, the canvas is this gradient
)

// Canvases are kept high-key (the POS is read in bright workshops) but softened:
// pure-grey/pure-white were fatiguing and made the white cards vanish into the
// background. Each now carries a whisper of warmth or hue so cards read as raised
// surfaces, and the gradients use gentle same-family stops (never muddy). Default
// is "Cloud" — a soft cool off-white that lets the Pine accent do the talking.
val BACKGROUND_PRESETS = listOf(
    BackgroundPreset("cloud", "Cloud", Color(0xFFF2F4F7)),                       // soft cool off-white (default)
    BackgroundPreset("paper", "Paper", Color(0xFFFBFAF7)),                       // warm near-white
    BackgroundPreset("sand",  "Sand",  Color(0xFFF3EEE4)),                       // warm neutral
    BackgroundPreset("mist",  "Mist",  Color(0xFFEBEFF4)),                       // cool grey-blue
    BackgroundPreset("sky",   "Sky",   Color(0xFFEAF2FB), listOf(Color(0xFFF1F7FF), Color(0xFFDFEAF8))),
    BackgroundPreset("mint",  "Mint",  Color(0xFFE9F4EE), listOf(Color(0xFFF0F9F3), Color(0xFFDCEDE4))), // pairs with Pine
    BackgroundPreset("dawn",  "Dawn",  Color(0xFFFBF0EC), listOf(Color(0xFFFDF4F0), Color(0xFFF6E6EA))),
    BackgroundPreset("dusk",  "Dusk",  Color(0xFFEFEFF7), listOf(Color(0xFFF4F3FB), Color(0xFFE6E5F2))),
)

data class SidebarPreset(val id: String, val name: String, val desc: String)

val SIDEBAR_PRESETS = listOf(
    SidebarPreset("dark",   "Graphite", "Dark neutral slate"),
    SidebarPreset("accent", "Accent",   "Tinted with your color"),
)

/** The user's persisted theme selection. The out-of-box default is now "Pine"
 *  (a deep teal-green) — calmer over long retail shifts than the old fire-engine
 *  red, and cleanly distinct from the fixed wholesale blue and the danger red so
 *  the three never blur together. Signature Red (#ff3830, the web's old default)
 *  is still one tap away under Settings → Appearance. A cashier who had already
 *  chosen a theme keeps it; only fresh installs get Pine. */
data class ThemeChoice(
    val accent: String = "pine",
    val accentHex: String = "#0F766E",
    val background: String = "cloud",
    val sidebar: String = "dark",
)

val DEFAULT_THEME = ThemeChoice()

fun ThemeChoice.effectiveHex(): String =
    if (accent == "custom") accentHex
    else ACCENT_PRESETS.firstOrNull { it.id == accent }?.hex ?: accentHex

// ── Design tokens consumed across the UI ────────────────────────────────────

/**
 * The full set of resolved colors the screens read. Built once per theme change
 * and provided via [LocalPosTokens]. [brand] is the themeable ramp; [accentBlue]
 * is a FIXED blue used only for wholesale prices / links (never re-themed), to
 * match the web's `accent-600`.
 */
data class PosTokens(
    val brand: BrandRamp,
    val inkOnBrand: Color,     // readable text on a brand-600 fill
    val brandShadow: Color,    // tinted glow under brand badges/buttons
    val canvas: Color,         // solid app canvas (behind cards)
    val canvasBrush: Brush,    // canvas as a brush (gradient-aware)
    val surface1: Color,       // cards
    val surface2: Color,
    val surface3: Color,
    val surface4: Color,
    val surfaceBorder: Color,
    val inkPrimary: Color,
    val inkSecondary: Color,
    val inkTertiary: Color,
    val navBg: Color,          // side drawer surface
    val navInk: Color,         // side drawer text
    val accentBlue: Color,     // fixed wholesale/link blue
    val danger: Color,
    val warning: Color,
    val success: Color,
    val onlinePill: Color,
    val offlinePill: Color,
)

fun buildTokens(choice: ThemeChoice): PosTokens {
    val hex = choice.effectiveHex()
    val ramp = buildRamp(hex)
    val bg = BACKGROUND_PRESETS.firstOrNull { it.id == choice.background } ?: BACKGROUND_PRESETS[0]
    val accentSidebar = choice.sidebar == "accent"
    val navHex = if (accentSidebar) mix(hex, false, 0.62) else "#111827"
    val brush = bg.gradient?.let { Brush.verticalGradient(it) } ?: Brush.verticalGradient(listOf(bg.surface0, bg.surface0))
    return PosTokens(
        brand = ramp,
        inkOnBrand = readableInk(mix(hex, false, 0.14)),
        brandShadow = hexToColor(hex).copy(alpha = 0.22f),
        canvas = bg.surface0,
        canvasBrush = brush,
        surface1 = Color(0xFFFFFFFF),
        surface2 = Color(0xFFF9FAFB),
        surface3 = Color(0xFFF3F4F6),
        surface4 = Color(0xFFE5E7EB),
        surfaceBorder = Color(0xFFE5E7EB),
        inkPrimary = Color(0xFF111827),
        inkSecondary = Color(0xFF4B5563),
        // gray-500 (not gray-400): the muted small-label ink is used widely at 10–11sp,
        // and gray-400 on the white/near-white surfaces is only ~2.5:1 — below the WCAG
        // AA 4.5:1 floor (prompt §1.1). gray-500 (#6B7280) clears it (~4.8:1) while
        // staying visibly muted below inkSecondary.
        inkTertiary = Color(0xFF6B7280),
        navBg = hexToColor(navHex),
        navInk = if (accentSidebar) readableInk(navHex) else Color(0xFFF9FAFB),
        accentBlue = Color(0xFF1E5BFF),
        danger = Color(0xFFEF4444),
        warning = Color(0xFFF59E0B),
        success = Color(0xFF10B981),
        onlinePill = Color(0xFF34D399),
        offlinePill = Color(0xFFFBBF24),
    )
}

val LocalPosTokens = staticCompositionLocalOf { buildTokens(DEFAULT_THEME) }

// ── App font ─────────────────────────────────────────────────────────────────
// The web app uses Poppins, which we previously pulled via *downloadable* Google
// Fonts. That path needs Google Play Services (the `com.google.android.gms` font
// provider) to be installed — but the target POS hardware (Sunmi / generic AOSP
// handhelds) often ships WITHOUT Play Services, and there the Compose font
// resolver throws on the very first frame, so the app "blinks and closes".
// Falling back to the always-present system font (Roboto) makes startup
// crash-proof and faster. To restore Poppins later WITHOUT depending on Play
// Services, drop the .ttf weights under res/font and build a FontFamily from them.
val Poppins: FontFamily = FontFamily.Default

private fun posTypography(font: FontFamily): Typography {
    val b = Typography()
    return b.copy(
        displayLarge = b.displayLarge.copy(fontFamily = font),
        displayMedium = b.displayMedium.copy(fontFamily = font),
        displaySmall = b.displaySmall.copy(fontFamily = font),
        headlineLarge = b.headlineLarge.copy(fontFamily = font),
        headlineMedium = b.headlineMedium.copy(fontFamily = font),
        headlineSmall = b.headlineSmall.copy(fontFamily = font),
        titleLarge = b.titleLarge.copy(fontFamily = font),
        titleMedium = b.titleMedium.copy(fontFamily = font),
        titleSmall = b.titleSmall.copy(fontFamily = font),
        bodyLarge = b.bodyLarge.copy(fontFamily = font),
        bodyMedium = b.bodyMedium.copy(fontFamily = font),
        bodySmall = b.bodySmall.copy(fontFamily = font),
        labelLarge = b.labelLarge.copy(fontFamily = font),
        labelMedium = b.labelMedium.copy(fontFamily = font),
        labelSmall = b.labelSmall.copy(fontFamily = font),
    )
}

@Composable
fun PosTheme(
    theme: ThemeChoice = DEFAULT_THEME,
    content: @Composable () -> Unit
) {
    val tokens = buildTokens(theme)
    val ramp = tokens.brand
    val colors = lightColorScheme(
        primary = ramp.s600,
        onPrimary = tokens.inkOnBrand,
        primaryContainer = ramp.s50,
        onPrimaryContainer = ramp.s700,
        secondary = tokens.accentBlue,
        onSecondary = Color.White,
        background = tokens.canvas,
        onBackground = tokens.inkPrimary,
        surface = tokens.surface1,
        onSurface = tokens.inkPrimary,
        surfaceVariant = tokens.surface3,
        onSurfaceVariant = tokens.inkSecondary,
        outline = tokens.surfaceBorder,
        error = tokens.danger,
        onError = Color.White,
        // Material 3 tints every ELEVATED surface (dialogs, menus, elevated cards)
        // with `surfaceTint` — which defaults to `primary` (the brand red). At a
        // dialog's 6dp tonal elevation that paints a muddy pink wash behind the
        // white content, so the accent, the wash, and the fixed blue/greys clash.
        // Killing the tint (transparent) keeps elevated surfaces clean white across
        // EVERY theme; the brand still shows on buttons, chips and accents where we
        // set it explicitly. (prompt §2 — popup colours don't fit together.)
        surfaceTint = Color.Transparent,
    )

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = ramp.s700.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    CompositionLocalProvider(LocalPosTokens provides tokens) {
        MaterialTheme(
            colorScheme = colors,
            typography = posTypography(Poppins),
            content = content
        )
    }
}
