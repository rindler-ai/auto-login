package ai.rindler.autologin.ui.theme

import androidx.compose.ui.unit.dp

// Redesign §1.3 — the 4dp spacing grid. Screen horizontal margin = 16dp (row)
// everywhere; centered hero copy gets +16 (total 32). Between-section space is
// 2–3× within-section space. Cut content before compressing gaps.
object Dimens {
    // Spacing grid.
    val hair = 4.dp
    val icon = 8.dp
    val gap = 12.dp // C1: the 12dp step (hero-copy top gap, trailing gaps)
    val row = 16.dp
    val section = 24.dp
    val block = 32.dp
    val hero = 48.dp

    // Component metrics (C1: the 40/56/72 steps as named tokens, not magic numbers).
    val tile = 40.dp // media tile / avatar
    val rowMin = 56.dp // one-line row minHeight
    val rowTwoLine = 72.dp // two-line row minHeight

    // Text keylines (where the title starts, measured from the row's leading edge).
    val keylineIcon = 56.dp // after a 24dp icon (16 pad + 24 + 16 gap)
    val keylineTile = 72.dp // after a 40dp tile
    val keylineCompact = 48.dp // G2: after a 32dp compact logo (Enroll panel)
}
