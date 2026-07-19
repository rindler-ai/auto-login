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

private enum class Dest { Onboarding, Pair, Completing, Setup, Home, Enroll, Settings, ManualCode, Advanced }

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
                // Paired but never primed — the checklist survives process death mid-flow
                // (it is flag-gated, not event-gated), so a kill here resumes it.
                !store.isSetupSeen() -> Dest.Setup
                else -> Dest.Home
            },
        )
    }
    // A forward move slides in from the right; a back move (to a lower-ranked dest)
    // slides in from the left. Purely presentational.
    var forward by remember { mutableStateOf(true) }
    // Which chrome the checklist wears: the pinned-CTA interstitial (post-pairing) or the
    // pushed screen with a back arrow + "Don't remind me" (reached from the Home nudge).
    var setupFromHome by remember { mutableStateOf(false) }
    fun go(next: Dest, isForward: Boolean = true) { forward = isForward; dest = next }
    // Every post-pairing route to Home passes through here: a device that has never seen
    // the reliability checklist gets it once, and only once.
    fun goPostPair() {
        setupFromHome = false // the interstitial chrome, whatever the last visit used
        go(if (!store.isSetupSeen()) Dest.Setup else Dest.Home)
    }

    // Sign-in enrollment: an autologin://paired deep link arrives via MainActivity as
    // `pendingEnroll`. Complete pairing with the token against the hub the link named
    // (or, if it omitted one, the hub already stored / the compiled-in default), then
    // land on Home; on failure show the error on the Completing screen with a retry.
    var enrollError by remember { mutableStateOf<String?>(null) }
    // The account email the deep link carried, shown as "Linking as {email}…" on the
    // Completing screen before it is persisted (§3.3 / §4).
    var linkingEmail by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pendingEnroll) {
        val req = pendingEnroll ?: return@LaunchedEffect
        enrollError = null
        linkingEmail = req.email
        if (!store.isOnboarded()) store.setOnboarded()
        go(Dest.Completing)
        val hub = req.hub ?: store.hubUrl() ?: BuildConfig.HUB_URL
        val result = completeEnroll(store, req.token, hub)
        onEnrollConsumed()
        result.fold(
            onSuccess = {
                // Persist the account identity ONCE at sign-in (never re-fetched per
                // render); AccountHeader reads it from the store. A null degrades to the
                // "Signed in" + Shield fallback.
                store.setAccountEmail(req.email)
                store.setAvatarUrl(req.avatar)
                RelayService.ensureRunning(ctx)
                goPostPair()
            },
            onFailure = { enrollError = friendlyPairError(it.message) },
        )
    }

    // Hardware / gesture back navigates one screen up. Enroll and Settings return to
    // Home; a re-pair (device already paired) returns to Home too. On the roots
    // (Home, initial Pair), Onboarding (handles its own slide-back), and Completing
    // (an uninterruptible step) the handler is disabled so the system default applies.
    val backTarget: Dest? = when (dest) {
        // Back out of the checklist counts as seeing it — same as "Not now".
        Dest.Setup -> Dest.Home
        Dest.Enroll, Dest.Settings, Dest.ManualCode -> Dest.Home
        Dest.Advanced -> if (store.deviceToken() != null) Dest.Settings else Dest.Pair
        Dest.Pair -> if (store.deviceToken() != null) Dest.Home else null
        else -> null
    }
    BackHandler(enabled = backTarget != null) {
        if (dest == Dest.Setup) store.setSetupSeen()
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
                    email = linkingEmail,
                    onRetry = { enrollError = null; go(Dest.Pair, isForward = false) },
                )
                Dest.Setup -> SetupScreen(
                    store = store,
                    fromHome = setupFromHome,
                    onDone = { go(Dest.Home, isForward = false) },
                )
                Dest.Home -> HomeScreen(
                    store = store,
                    onAddLogin = { go(Dest.Enroll) },
                    onSettings = { go(Dest.Settings) },
                    onEnterCode = { go(Dest.ManualCode) },
                    onFinishSetup = { setupFromHome = true; go(Dest.Setup) },
                )
                Dest.Enroll -> EnrollScreen(store = store, onDone = { go(Dest.Home, isForward = false) })
                Dest.ManualCode -> ManualCodeScreen(store = store, onDone = { go(Dest.Home, isForward = false) })
                Dest.Settings -> SettingsScreen(
                    store = store,
                    onBack = { go(Dest.Home, isForward = false) },
                    // A self-hoster (a non-default hub is stored) re-pairs against their
                    // own server via Advanced, not the Rindler sign-in screen (P3).
                    onRepair = { go(if (store.hubUrl() != null) Dest.Advanced else Dest.Pair) },
                    // Sign out wiped local identity + logins + account (store.signOut());
                    // land on the Sign-in screen, not the intro.
                    onSignOut = { go(Dest.Pair, isForward = false) },
                    onAdvanced = { go(Dest.Advanced) },
                )
                Dest.Advanced -> AdvancedScreen(
                    store = store,
                    onPaired = {
                        // Self-host pairing earns the same reliability settings as the
                        // hosted sign-in path.
                        RelayService.ensureRunning(ctx)
                        goPostPair()
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
private fun CompletingScreen(error: String?, email: String?, onRetry: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
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
            if (email != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Linking as $email",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
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
