package ai.rindler.autologin.ui

import androidx.compose.ui.unit.dp
import org.testng.Assert.assertEquals
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

/*
 * cappedContentWidth(available, cap) is the width the scaffold hands its content column so
 * a wide screen (tablet / unfolded foldable) does not stretch the rows edge-to-edge.
 *
 * Partition of `available` relative to `cap`:
 *   available <  cap  -> narrow phone: use the full available width.
 *   available == cap  -> boundary: use the cap (which equals available).
 *   available >  cap  -> tablet / foldable: clamp down to the cap.
 * `cap` is held at a representative 560dp; the function is symmetric in it.
 *
 * Oracle: the result is exactly min(available, cap) and NEVER exceeds cap — the property
 * that keeps the content from stretching — asserted directly, not re-derived from the impl.
 * This is the only pure logic the §5 responsive-layout fixes introduce; the remaining fixes
 * are Compose measurement (scroll / min-height / weight / heightIn) that a JVM unit test
 * cannot observe, and are verified on the emulator across font scales and screen widths.
 */
class ContentWidthTest {
    private val cap = 560.dp

    /** Narrow screen (e.g. a 360dp phone): the content takes the whole width. */
    @Test
    fun narrowScreenUsesFullWidth() {
        assertEquals(cappedContentWidth(available = 360.dp, cap = cap), 360.dp)
    }

    /** Wide screen (e.g. an 840dp unfolded foldable): the content is clamped to the cap. */
    @Test
    fun wideScreenClampsToCap() {
        assertEquals(cappedContentWidth(available = 840.dp, cap = cap), cap)
    }

    /** At the boundary the cap is used (and never exceeded). */
    @Test
    fun atBoundaryUsesCap() {
        assertEquals(cappedContentWidth(available = cap, cap = cap), cap)
    }

    /** Across a sweep the result is min(available, cap) and is never wider than the cap. */
    @Test
    fun neverExceedsCap() {
        for (w in listOf(0.dp, 200.dp, 559.dp, 560.dp, 561.dp, 1200.dp)) {
            val got = cappedContentWidth(w, cap)
            val expected = if (w < cap) w else cap
            assertEquals(got, expected, "cappedContentWidth($w) should be min(available, cap)")
            assertTrue(got <= cap, "content width $got must never exceed the cap $cap for available=$w")
        }
    }
}
