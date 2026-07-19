package ai.rindler.autologin.ui.theme

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

// Redesign §1.7 — durations on M3 standard easing; exits ≈ 2/3 of enters; nothing
// >400ms; max one attention-seeking animation per screen.
object MotionTokens {
    const val fast = 100 // ms
    const val short = 200
    const val medium = 300
    const val long = 400
}

/**
 * True when the OS animator scale is off — reduced-motion collapses everything to
 * ~100ms fades. Provided by [AutoLoginTheme]; default false until provided.
 */
val LocalReducedMotion = staticCompositionLocalOf { false }

/** Reads [Settings.Global.ANIMATOR_DURATION_SCALE]; scale == 0f ⇒ reduced motion. */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        val scale = Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
        scale == 0f
    }
}
