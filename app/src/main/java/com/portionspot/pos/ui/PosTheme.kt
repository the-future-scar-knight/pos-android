package com.portionspot.pos.ui

import android.app.Activity
import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

// ── Motion ───────────────────────────────────────────────────────────────────
// Motion values live here for the same reason colours do: literals scattered
// across screens drift. Unlike [PosTokens] these are NOT themeable — motion is a
// property of the app's character, not of the accent the shop picked, so this is
// a plain object rather than another CompositionLocal.
//
// The durations are deliberately short. A cashier repeats a handful of actions
// hundreds of times a shift on cheap hardware while a customer waits, so motion
// here buys tactile confirmation and nothing else. Anything that reads as
// decoration is a tax paid on every sale.

object PosMotion {
    /** Entrances. Compose's default [FastOutSlowInEasing] is an ease-IN-out — it
     *  front-loads a slow start, which is exactly what makes UI feel sluggish. */
    val EnterEasing: Easing = LinearOutSlowInEasing

    /** On-screen movement between two known positions (slides, reorders). */
    val MoveEasing: Easing = FastOutSlowInEasing

    /** Exits. Accelerating out is correct; the user has already moved on. */
    val ExitEasing: Easing = FastOutLinearInEasing

    /** iOS-like drawer curve (from Ionic). For sheets and the side drawer. */
    val DrawerEasing: Easing = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)

    /** Press feedback. Below ~100ms reads as a glitch; above ~160ms as lag. */
    const val Press = 120

    /** Controls: tabs, toggles, chips, pills. */
    const val Control = 180

    /** Surfaces: dialogs, sheets, the drawer. */
    const val Surface = 240

    /** Exits run shorter than their entrance — see [ExitEasing]. */
    const val SurfaceExit = 160

    /** Settle for surfaces. No bounce: this app counts money, and a springy
     *  payment sheet reads as unserious. */
    fun <T> surfaceSpring() = spring<T>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )
}

/**
 * True when the user has turned system animations off (Developer Options, or the
 * accessibility "Remove animations" toggle). Android has no `prefers-reduced-motion`;
 * the animator duration scale is the signal, and a scale of 0 means the platform is
 * already skipping its own animations, so ours should follow.
 *
 * Read it once — it needs an app restart to change, and polling it per-frame would
 * cost more than the animations it disables.
 */
val LocalReduceMotion = staticCompositionLocalOf { false }

@Composable
fun rememberReduceMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember {
        runCatching {
            Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
}

// ── Responsive sizing ────────────────────────────────────────────────────────
// The layout used to be frozen: a fixed 2-column grid and hard-coded card/text
// sizes, so a big tablet till showed two giant cards while a small handheld
// crammed the same two. This is genuine *screen-size* responsiveness (driven by
// the window's width in dp) and is orthogonal to the fontScale=1f pin in
// MainActivity — that pin only stops the device's ACCESSIBILITY font setting from
// blowing the UI up; it does not adapt to the actual screen. Here we do.
//
// Buckets (screenWidthDp): Compact <360 (small phones / the Sunmi V1s-G handheld),
// Medium 360–599 (typical phones), Expanded 600–839 (large phones landscape /
// small tablets), Large ≥840 (tablet tills). The Sunmi stays at 2 columns and
// slightly tightened sizes so the tight low-DPI screen never clips.

enum class PosWidthClass { Compact, Medium, Expanded, Large }

/** Screen-size–derived dimensions read across the POS surfaces via [LocalPosDimens]. */
data class PosDimens(
    val widthClass: PosWidthClass,
    val productColumns: Int,
    val gridPadding: Dp,
    val gridSpacing: Dp,
    val cardHeight: Dp,
    val cardPadding: Dp,
    val cardCorner: Dp,
    val cardImageHeight: Dp,
    val cardPriceSize: TextUnit,
    val cardNameSize: TextUnit,
    val cardMetaSize: TextUnit,
    // Dense single-row (LIST) presentation of the same product — a compact
    // alternative to the grid card, used by the POS list-view toggle.
    val listRowMinHeight: Dp,
    val listRowPadding: Dp,
    val listThumb: Dp,
)

fun posDimensFor(widthDp: Int): PosDimens {
    val cls = when {
        widthDp < 360 -> PosWidthClass.Compact
        widthDp < 600 -> PosWidthClass.Medium
        widthDp < 840 -> PosWidthClass.Expanded
        else -> PosWidthClass.Large
    }
    return when (cls) {
        // Compact = the Sunmi handheld: a low-DPI, physically SHORT screen. The old
        // 140dp card meant that with the soft keyboard open you could barely see a
        // full row of two. Tightened here (shorter card, smaller hero band, trimmed
        // padding/type) so at least a full row of 2 stays comfortably visible while
        // typing a search. Medium+ are left roomy for real phones/tablets.
        PosWidthClass.Compact -> PosDimens(
            widthClass = cls, productColumns = 2,
            gridPadding = 8.dp, gridSpacing = 8.dp,
            cardHeight = 116.dp, cardPadding = 8.dp, cardCorner = 12.dp, cardImageHeight = 42.dp,
            cardPriceSize = 14.sp, cardNameSize = 11.sp, cardMetaSize = 9.sp,
            listRowMinHeight = 52.dp, listRowPadding = 10.dp, listThumb = 38.dp,
        )
        PosWidthClass.Medium -> PosDimens(
            widthClass = cls, productColumns = 2,
            gridPadding = 12.dp, gridSpacing = 10.dp,
            cardHeight = 152.dp, cardPadding = 12.dp, cardCorner = 16.dp, cardImageHeight = 56.dp,
            cardPriceSize = 16.sp, cardNameSize = 12.sp, cardMetaSize = 10.sp,
            listRowMinHeight = 58.dp, listRowPadding = 12.dp, listThumb = 42.dp,
        )
        PosWidthClass.Expanded -> PosDimens(
            widthClass = cls, productColumns = 3,
            gridPadding = 16.dp, gridSpacing = 12.dp,
            cardHeight = 168.dp, cardPadding = 14.dp, cardCorner = 18.dp, cardImageHeight = 66.dp,
            cardPriceSize = 18.sp, cardNameSize = 13.sp, cardMetaSize = 11.sp,
            listRowMinHeight = 62.dp, listRowPadding = 14.dp, listThumb = 46.dp,
        )
        PosWidthClass.Large -> PosDimens(
            widthClass = cls, productColumns = 4,
            gridPadding = 20.dp, gridSpacing = 14.dp,
            cardHeight = 184.dp, cardPadding = 16.dp, cardCorner = 20.dp, cardImageHeight = 78.dp,
            cardPriceSize = 20.sp, cardNameSize = 14.sp, cardMetaSize = 11.sp,
            listRowMinHeight = 66.dp, listRowPadding = 16.dp, listThumb = 50.dp,
        )
    }
}

val LocalPosDimens = staticCompositionLocalOf { posDimensFor(400) }

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

    val widthDp = LocalConfiguration.current.screenWidthDp
    val dimens = remember(widthDp) { posDimensFor(widthDp) }

    CompositionLocalProvider(
        LocalPosTokens provides tokens,
        LocalPosDimens provides dimens,
        LocalReduceMotion provides rememberReduceMotion(),
    ) {
        MaterialTheme(
            colorScheme = colors,
            typography = posTypography(Poppins),
            content = content
        )
    }
}
