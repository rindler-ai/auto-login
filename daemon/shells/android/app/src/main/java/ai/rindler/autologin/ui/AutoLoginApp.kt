package ai.rindler.autologin.ui

import ai.rindler.autologin.BuildConfig
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private enum class Dest { Onboarding, Pair, Completing, Home, Enroll, Settings, ManualCode, Advanced }

@Composable
fun AutoLoginApp(
    store: KeystoreSecretSource,
    pendingEnroll: EnrollRequest? = null,
    onEnrollConsumed: () -> Unit = {},
) {
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

    // Sign-in enrollment: an autologin://paired deep link arrives via MainActivity as
    // `pendingEnroll`. Complete pairing with the token against the hub the link named
    // (or, if it omitted one, the hub already stored / the compiled-in default), then
    // land on Home; on failure show the error on the Completing screen with a retry.
    var enrollError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pendingEnroll) {
        val req = pendingEnroll ?: return@LaunchedEffect
        enrollError = null
        if (!store.isOnboarded()) store.setOnboarded()
        go(Dest.Completing)
        val hub = req.hub ?: store.hubUrl() ?: BuildConfig.HUB_URL
        val result = completeEnroll(store, req.token, hub)
        onEnrollConsumed()
        result.fold(
            onSuccess = {
                RelayService.ensureRunning(ctx)
                go(Dest.Home)
            },
            onFailure = { enrollError = friendlyPairError(it.message) },
        )
    }

    // Hardware / gesture back navigates one screen up. Enroll and Settings return to
    // Home; a re-pair (device already paired) returns to Home too. On the roots
    // (Home, initial Pair), Onboarding (handles its own slide-back), and Completing
    // (an uninterruptible step) the handler is disabled so the system default applies.
    val backTarget: Dest? = when (dest) {
        Dest.Enroll, Dest.Settings, Dest.ManualCode -> Dest.Home
        Dest.Advanced -> if (store.deviceToken() != null) Dest.Settings else Dest.Pair
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
                Dest.Pair -> PairScreen(onAdvanced = { go(Dest.Advanced) })
                Dest.Completing -> CompletingScreen(
                    error = enrollError,
                    onRetry = { enrollError = null; go(Dest.Pair, isForward = false) },
                )
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
                    // A self-hoster (a non-default hub is stored) re-pairs against their
                    // own server via Advanced, not the Rindler sign-in screen (P3).
                    onRepair = { go(if (store.hubUrl() != null) Dest.Advanced else Dest.Pair) },
                    onReset = { go(Dest.Onboarding, isForward = false) },
                    onAdvanced = { go(Dest.Advanced) },
                )
                Dest.Advanced -> AdvancedScreen(
                    store = store,
                    onPaired = {
                        RelayService.ensureRunning(ctx)
                        go(Dest.Home)
                    },
                    onBack = {
                        go(
                            if (store.deviceToken() != null) Dest.Settings else Dest.Pair,
                            isForward = false,
                        )
                    },
                )
            }
        }
      }
    }
}

// Shown while a sign-in enrollment finishes pairing (or, on failure, with a retry).
@Composable
private fun CompletingScreen(error: String?, onRetry: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        if (error == null) {
            CircularProgressIndicator(color = cs.primary)
            Spacer(Modifier.height(24.dp))
            Text(
                "Signing you in and setting up this device…",
                style = MaterialTheme.typography.bodyLarge,
                color = cs.onBackground,
                textAlign = TextAlign.Center,
            )
        } else {
            Text(
                error,
                style = MaterialTheme.typography.bodyLarge,
                color = cs.error,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            PrimaryButton(text = "Back to setup", onClick = onRetry)
        }
        Spacer(Modifier.weight(1f))
        TrustFooter()
    }
}
