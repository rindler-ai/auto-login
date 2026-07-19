package ai.rindler.autologin.ui

import ai.rindler.autologin.ui.theme.DarkExtendedColors
import ai.rindler.autologin.ui.theme.DarkSurface
import ai.rindler.autologin.ui.theme.LightExtendedColors
import ai.rindler.autologin.ui.theme.LightSurface
import androidx.compose.ui.graphics.Color
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

/*
 * Testing strategy for the "not supported yet" warning badge contrast (§6.4).
 *
 * The badge is a non-text graphic (a "!" glyph on a coloured circle overlaid on a site
 * tile), so WCAG's ≥3:1 non-text contrast floor applies to TWO independent pairs, in BOTH
 * themes:
 *   1. badge FILL vs the page ground  — so the badge is visible at all;
 *   2. GLYPH vs the badge fill        — so the "!" is visible on the badge.
 * The shipped bug used one shared amber fill (#E3B341) + a white glyph, which failed BOTH
 * in light mode (fill-on-Paper 1.80:1, white-glyph-on-amber 1.95:1). The oracle is the WCAG
 * ratio itself, computed here from the ACTUAL theme tokens (not hard-coded copies), so the
 * assertions fail if a future edit regresses a token — see the mutation in the class doc of
 * [contrastRatio]: revert LightWarningBadge to 0xFFE3B341 and lightBadgeVisibleOnGround goes
 * red (1.80 < 3.0); restore and it is green.
 *
 * `contrastRatio` is validated against a known value by [oldWhiteGlyphOnAmberWasInvisible]
 * (the ~1.95:1 the bug report cites), so a broken helper cannot make the fix look passing.
 */
class WarningBadgeContrastTest {
    /** WCAG 2.x relative luminance of an sRGB colour. */
    private fun luminance(c: Color): Double {
        fun channel(v: Float): Double {
            val d = v.toDouble()
            return if (d <= 0.03928) d / 12.92 else Math.pow((d + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    }

    /** WCAG contrast ratio between two colours (order-independent), in [1.0, 21.0]. */
    private fun contrastRatio(fg: Color, bg: Color): Double {
        val a = luminance(fg)
        val b = luminance(bg)
        val hi = maxOf(a, b)
        val lo = minOf(a, b)
        return (hi + 0.05) / (lo + 0.05)
    }

    private val minNonText = 3.0 // WCAG 1.4.11 non-text contrast floor

    /** Light: the badge fill must be visible against the page ground (Paper). */
    @Test
    fun lightBadgeVisibleOnGround() {
        val r = contrastRatio(LightExtendedColors.warningBadge, LightSurface)
        assertTrue(r >= minNonText, "light badge fill vs ground must be ≥3:1, was $r")
    }

    /** Light: the "!" glyph must be visible on the badge fill. */
    @Test
    fun lightGlyphVisibleOnBadge() {
        val r = contrastRatio(LightExtendedColors.onWarningBadge, LightExtendedColors.warningBadge)
        assertTrue(r >= minNonText, "light glyph vs badge fill must be ≥3:1, was $r")
    }

    /** Dark: the badge fill must be visible against the dark canvas. */
    @Test
    fun darkBadgeVisibleOnGround() {
        val r = contrastRatio(DarkExtendedColors.warningBadge, DarkSurface)
        assertTrue(r >= minNonText, "dark badge fill vs ground must be ≥3:1, was $r")
    }

    /** Dark: the "!" glyph must be visible on the amber badge fill (the regressed case). */
    @Test
    fun darkGlyphVisibleOnBadge() {
        val r = contrastRatio(DarkExtendedColors.onWarningBadge, DarkExtendedColors.warningBadge)
        assertTrue(r >= minNonText, "dark glyph vs badge fill must be ≥3:1, was $r")
    }

    /**
     * Anchors the fix AND validates the helper: the old white-glyph-on-amber the bug report
     * measured at ~1.95:1 must indeed compute sub-3:1 here. If this drifts, the helper — not
     * the theme — is wrong, and the four assertions above cannot be trusted.
     */
    @Test
    fun oldWhiteGlyphOnAmberWasInvisible() {
        val r = contrastRatio(Color.White, Color(0xFFE3B341))
        assertTrue(r < minNonText, "sanity: old white-on-amber glyph should be sub-3:1, was $r")
        assertTrue(r in 1.90..2.00, "helper self-check: expected ~1.95:1, got $r")
    }
}
