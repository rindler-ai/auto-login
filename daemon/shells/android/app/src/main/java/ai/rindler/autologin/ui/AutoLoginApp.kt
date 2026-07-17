package ai.rindler.autologin.ui

import ai.rindler.autologin.KeystoreSecretSource
import ai.rindler.autologin.RelayService
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

private enum class Dest { Onboarding, Pair, Home, Enroll, Settings, ManualCode }

@Composable
fun AutoLoginApp(store: KeystoreSecretSource) {
    val ctx = LocalContext.current
    var dest by remember {
        mutableStateOf(
            when {
                !store.isOnboarded() -> Dest.Onboarding
                store.deviceToken() == null -> Dest.Pair
                else -> Dest.Home
            },
        )
    }
    // A forward move slides in from the right; a back move (to a lower-ranked dest)
    // slides in from the left. Purely presentational.
    var forward by remember { mutableStateOf(true) }
    fun go(next: Dest, isForward: Boolean = true) { forward = isForward; dest = next }

    // Hardware / gesture back navigates one screen up. Enroll and Settings return to
    // Home; a re-pair (device already paired) returns to Home too. On the roots
    // (Home, initial Pair) and Onboarding — which handles its own slide-back — the
    // handler is disabled so the system default (exit) applies.
    val backTarget: Dest? = when (dest) {
        Dest.Enroll, Dest.Settings, Dest.ManualCode -> Dest.Home
        Dest.Pair -> if (store.deviceToken() != null) Dest.Home else null
        else -> null
    }
    BackHandler(enabled = backTarget != null) {
        backTarget?.let { go(it, isForward = false) }
    }

    // Surface fills edge-to-edge (background bleeds behind the system bars);
    // the inner Box insets the CONTENT to the safe area so nothing sits under the
    // status bar or gesture nav.
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
      Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        AnimatedContent(
            targetState = dest,
            transitionSpec = {
                val dir = if (forward) 1 else -1
                (slideInHorizontally(tween(320)) { w -> dir * w / 6 } + fadeIn(tween(260))) togetherWith
                    (slideOutHorizontally(tween(320)) { w -> -dir * w / 6 } + fadeOut(tween(200))) using
                    SizeTransform(clip = false)
            },
            label = "nav",
            modifier = Modifier.background(MaterialTheme.colorScheme.background),
        ) { screen ->
            when (screen) {
                Dest.Onboarding -> OnboardingScreen(onDone = {
                    store.setOnboarded()
                    go(if (store.deviceToken() == null) Dest.Pair else Dest.Home)
                })
                Dest.Pair -> PairScreen(store = store, onPaired = {
                    RelayService.ensureRunning(ctx)
                    go(Dest.Home)
                })
                Dest.Home -> HomeScreen(
                    store = store,
                    onAddLogin = { go(Dest.Enroll) },
                    onSettings = { go(Dest.Settings) },
                    onEnterCode = { go(Dest.ManualCode) },
                )
                Dest.Enroll -> EnrollScreen(store = store, onDone = { go(Dest.Home, isForward = false) })
                Dest.ManualCode -> ManualCodeScreen(store = store, onDone = { go(Dest.Home, isForward = false) })
                Dest.Settings -> SettingsScreen(
                    store = store,
                    onBack = { go(Dest.Home, isForward = false) },
                    onRepair = { go(Dest.Pair) },
                    onReset = { go(Dest.Onboarding, isForward = false) },
                )
            }
        }
      }
    }
}
