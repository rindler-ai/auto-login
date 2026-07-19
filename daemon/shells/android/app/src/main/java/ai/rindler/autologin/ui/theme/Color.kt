package ai.rindler.autologin.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.staticCompositionLocalOf

// Auto Login palette (redesign §1.1) — two hand-tuned schemes, no dynamic color.
// Identical role semantics both modes; components never branch on theme. Every
// ratio WCAG-verified. One accent (primary), used only as interactive affordance.

// ── Dark scheme (green-tinted neutrals, M3 tone ladder) ─────────────────────
val DarkSurface = Color(0xFF101413) // canvas — tone ~6, never near-#000
val DarkSurfaceContainerLowest = Color(0xFF0B0F0E)
val DarkSurfaceContainerLow = Color(0xFF171C1A) // sanctioned transient-panel fill
val DarkSurfaceContainer = Color(0xFF1B211F) // scrolled-bar tint
val DarkSurfaceContainerHigh = Color(0xFF232A27) // dialogs/menus
val DarkSurfaceContainerHighest = Color(0xFF2C3431) // text-field fill
val DarkOnSurface = Color(0xFFE0E4E0) // tone 90, never white (halation)
val DarkOnSurfaceVariant = Color(0xFFAFB8B2)
val DarkOutline = Color(0xFF89938D) // field borders
val DarkOutlineVariant = Color(0xFF3F4844) // hairline dividers
val DarkPrimary = Color(0xFF3DBE93) // desaturated for dark; Emerald600 banned (fails AA)
val DarkOnPrimary = Color(0xFF00382B)
val DarkPrimaryContainer = Color(0xFF00513F)
val DarkOnPrimaryContainer = Color(0xFFA9EFD6)
val DarkError = Color(0xFFF08078)

// ── Light scheme (warm-stone) ───────────────────────────────────────────────
val LightSurface = Color(0xFFF6F6F2) // Paper — tinted neutral, never pure white
val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
val LightSurfaceContainerLow = Color(0xFFEFF0EA)
val LightSurfaceContainer = Color(0xFFE9EAE4)
val LightSurfaceContainerHigh = Color(0xFFE3E5DE)
val LightSurfaceContainerHighest = Color(0xFFDDDFD8)
val LightOnSurface = Color(0xFF171A17) // Ink
val LightOnSurfaceVariant = Color(0xFF565E59)
val LightOutline = Color(0xFF6E7873) // field borders
val LightOutlineVariant = Color(0xFFDEDED6) // Stone200 (existing)
val LightPrimary = Color(0xFF0B735A) // passes as text AND as fill under white label
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFBEEAD9)
val LightOnPrimaryContainer = Color(0xFF06382B)
val LightError = Color(0xFFB93B33)

// ── Extended (non-M3) semantic tokens ───────────────────────────────────────
private val DarkWarning = Color(0xFFE3B341) // 9.5:1 as text on dark
private val LightWarning = Color(0xFF8A6116) // ochre — amber-as-text always fails on light

// Warning badge — the "!" overlay on an unsupported site tile. Both the badge fill (vs
// its surface ring / page ground) AND the glyph on the fill must clear the ≥3:1 non-text
// floor, in BOTH themes. A single shared amber fill with a white glyph failed both in
// light (fill #E3B341 on Paper = 1.80:1; white glyph on amber = 1.95:1), so the fill and
// glyph are tuned per theme (WCAG ratios verified in WarningBadgeContrastTest):
//   Light: dark-ochre fill #8A6116 (5.10:1 on Paper) with a WHITE glyph (5.52:1 on fill).
//   Dark:  amber fill #E3B341 (9.54:1 on the dark canvas) with a DARK glyph — the canvas
//          colour DarkSurface punched through the amber (9.54:1), never white (1.95:1).
private val LightWarningBadge = Color(0xFF8A6116)
private val LightOnWarningBadge = Color(0xFFFFFFFF)
private val DarkWarningBadge = Color(0xFFE3B341)
private val DarkOnWarningBadge = DarkSurface // #101413 — amber punched to the canvas

/**
 * Colors that Material's [androidx.compose.material3.ColorScheme] has no slot for.
 * Provided by [AutoLoginTheme] via [LocalExtendedColors]; identical role semantics
 * both modes so components never branch on theme.
 */
data class ExtendedColors(
    val warning: Color, // dark #E3B341 · light TEXT #8A6116
    val warningBadge: Color, // badge FILL: light #8A6116 · dark #E3B341 (each ≥3:1 vs ground)
    val onWarningBadge: Color, // badge GLYPH: light WHITE · dark #101413 (each ≥3:1 on fill)
    val statusConnected: Color, // aliases primary in both modes
)

val DarkExtendedColors = ExtendedColors(
    warning = DarkWarning,
    warningBadge = DarkWarningBadge,
    onWarningBadge = DarkOnWarningBadge,
    statusConnected = DarkPrimary,
)

val LightExtendedColors = ExtendedColors(
    warning = LightWarning,
    warningBadge = LightWarningBadge,
    onWarningBadge = LightOnWarningBadge,
    statusConnected = LightPrimary,
)

val LocalExtendedColors = staticCompositionLocalOf<ExtendedColors> { error("no theme") }
