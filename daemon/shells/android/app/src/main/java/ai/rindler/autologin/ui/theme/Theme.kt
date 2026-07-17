package ai.rindler.autologin.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Emerald600,
    onPrimary = Stone0,
    primaryContainer = Emerald50,
    onPrimaryContainer = Emerald900,
    secondary = Emerald700,
    onSecondary = Stone0,
    secondaryContainer = Emerald100,
    onSecondaryContainer = Emerald900,
    tertiary = Stone600,
    onTertiary = Stone0,
    background = Paper,
    onBackground = Ink,
    surface = Stone0,
    onSurface = Ink,
    surfaceVariant = Stone100,
    onSurfaceVariant = Stone600,
    surfaceContainerLowest = Stone0,
    surfaceContainerLow = Stone0,
    surfaceContainer = Stone0,
    surfaceContainerHigh = Stone100,
    surfaceContainerHighest = Stone200,
    outline = Stone300,
    outlineVariant = Stone200,
    error = Danger,
    onError = Stone0,
    scrim = Ink,
)

private val DarkColors = darkColorScheme(
    // The SAME brand emerald as light mode (Emerald600) — pinned across themes so the
    // accent never drifts. White text keeps it legible on near-black.
    primary = Emerald600,
    onPrimary = Stone0,
    primaryContainer = Emerald900,
    onPrimaryContainer = Emerald100,
    secondary = Emerald600,
    onSecondary = Stone0,
    secondaryContainer = Emerald900,
    onSecondaryContainer = Emerald100,
    tertiary = MistDim,
    onTertiary = Night,
    background = Night,
    onBackground = Mist,
    surface = NightSurface,
    onSurface = Mist,
    surfaceVariant = NightSurface2,
    onSurfaceVariant = MistDim,
    surfaceContainerLowest = Night,
    surfaceContainerLow = NightSurface,
    surfaceContainer = NightSurface,
    surfaceContainerHigh = NightSurface2,
    surfaceContainerHighest = NightSurface2,
    outline = NightOutline,
    outlineVariant = NightHair,
    error = DangerDark,
    onError = Night,
    scrim = Night,
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun AutoLoginTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = AppType,
        shapes = AppShapes,
        content = content,
    )
}
