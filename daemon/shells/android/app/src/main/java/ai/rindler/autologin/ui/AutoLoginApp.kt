package ai.rindler.autologin.ui

import ai.rindler.autologin.BuildConfig
import ai.rindler.autologin.KeystoreSecretSource
import ai.rindler.autologin.RelayService
import ai.rindler.autologin.ui.theme.LocalReducedMotion
import ai.rindler.autologin.ui.theme.MotionTokens
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

internal enum class Dest { Onboarding, Pair, Completing, Setup, Home, Enroll, Settings, ManualCode, Advanced, LinkedEmails }

/**
 * Map a RESTORED destination to a safe landing screen (§4d). Every screen restores
 * unchanged so a rotation / process-death recreate keeps the user where they were —
 * EXCEPT [Dest.Completing], the uninterruptible in-flight pairing step. Its pairing
 * coroutine (a LaunchedEffect / rememberCoroutineScope job) does not survive recreation,
 * so restoring INTO it would strand a dead spinner with no active enroll; it lands on
 * [Dest.Pair], where CompletingScreen's own retry goes. Pure; unit-tested (NavRestoreTest).
 */
internal fun restoredDest(saved: Dest): Dest = if (saved == Dest.Completing) Dest.Pair else saved

// Persist navigation across a config change (rotation) or a process-death recreate so a
// half-typed manual 2FA code / login form is not dropped back to the start screen (§4d).
// The Dest enum is saved by name; the initial-destination `when` below runs only on a
// genuine first composition (no saved value), matching the pre-existing behaviour.
private val DestSaver: Saver<Dest, String> = Saver(
    save = { it.name },
    restore = { name -> restoredDest(runCatching { Dest.valueOf(name) }.getOrDefault(Dest.Home)) },
)

// internal (not public): the nav vocabulary it speaks — Dest, restoredDest, DestSaver — is
// all internal, and its only caller is MainActivity in this module. Being internal lets it
// take a Dest? initial destination without a public symbol exposing the internal Dest type.
@Composable
internal fun AutoLoginApp(
    store: KeystoreSecretSource,
    pendingEnroll: EnrollRequest? = null,
    onEnrollConsumed: () -> Unit = {},
    // The screen to land on for THIS fresh launch, when set by MainActivity from a "code
    // needed" notification tap (Dest.ManualCode). Null = use the normal start resolution.
    // Only ever the INITIAL destination: because it is read inside the rememberSaveable
    // initial lambda (which runs solely on a first composition, no saved value), a
    // recreate restores the saved Dest and ignores this — so it can never fight the §4d
    // restored instance state.
    initialDest: Dest? = null,
) {
    val ctx = LocalContext.current
    // Reduced-motion (OS animator scale 0): the nav slide becomes a plain cross-fade.
    val reduced = LocalReducedMotion.current
    // rememberSaveable so a rotation keeps the current screen instead of resetting to the
    // start (§4d). The initial `when` runs only on a first composition (no saved value);
    // on a recreate the saved Dest is restored via DestSaver.
    var dest by rememberSaveable(stateSaver = DestSaver) {
        mutableStateOf(
            initialDest ?: when {
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
    // slides in from the left. Purely presentational — saved so the restored screen
    // animates in from the right direction after a recreate.
    var forward by rememberSaveable { mutableStateOf(true) }
    // Which chrome the checklist wears: the pinned-CTA interstitial (post-pairing) or the
    // pushed screen with a back arrow + "Don't remind me" (reached from the Home nudge).
    // Saved so a rotation on the Setup screen keeps the right chrome.
    var setupFromHome by rememberSaveable { mutableStateOf(false) }
    // The email screen is reached from Settings, Home, AND the setup checklist. onlyAdd picks
    // the onboarding single-address form vs the full manage page; returnTo is where back lands.
    // Both saved so a rotation keeps the right mode + return target.
    var emailOnlyAdd by rememberSaveable { mutableStateOf(false) }
    var emailReturnTo by rememberSaveable(stateSaver = DestSaver) { mutableStateOf(Dest.Home) }
    fun go(next: Dest, isForward: Boolean = true) { forward = isForward; dest = next }
    // Every post-pairing route to Home passes through here: a device that has never seen
    // the reliability checklist gets it once, and only once.
    fun goPostPair() {
        setupFromHome = false // the interstitial chrome, whatever the last visit used
        go(if (!store.isSetupSeen()) Dest.Setup else Dest.Home)
    }

    // Sign-in enrollment: an autologin://paired deep link arrives via MainActivity as
    // `pendingEnroll`. The link is hostile input (any web page can fire it), so it is
    // gated first — gateEnroll rejects a link naming any server but the build default,
    // and an already-linked phone gets an explicit confirmation dialog instead of a
    // silent re-pair. Then pairing runs with the token against the GATED hub; on
    // failure the error shows on the Completing screen with a retry.
    var enrollError by remember { mutableStateOf<String?>(null) }
    // The account email the deep link carried, shown as "Linking as {email}…" on the
    // Completing screen before it is persisted (§3.3 / §4).
    var linkingEmail by remember { mutableStateOf<String?>(null) }
    // A deep-link enrollment waiting on the "Link this phone again?" confirmation.
    var pendingRelink by remember { mutableStateOf<Pair<EnrollRequest, String>?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun runEnroll(req: EnrollRequest, hub: String) {
        enrollError = null
        linkingEmail = req.email
        if (!store.isOnboarded()) store.setOnboarded()
        go(Dest.Completing)
        val result = completeEnroll(store, req.token, hub)
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

    LaunchedEffect(pendingEnroll) {
        val req = pendingEnroll ?: return@LaunchedEffect
        when (val gate = gateEnroll(
            linkHub = req.hub,
            storedHub = store.hubUrl(),
            buildDefault = BuildConfig.HUB_URL,
            alreadyPaired = store.deviceToken() != null,
        )) {
            is EnrollGate.RejectedHub -> {
                onEnrollConsumed()
                linkingEmail = null
                enrollError =
                    "This sign-in link points to a different server, so this phone wasn't linked. Try signing in again."
                go(Dest.Completing)
            }
            is EnrollGate.ConfirmRelink -> pendingRelink = req to gate.hub
            is EnrollGate.Proceed -> {
                runEnroll(req, gate.hub)
                onEnrollConsumed()
            }
        }
    }

    pendingRelink?.let { (relinkReq, relinkHub) ->
        fun dismissRelink() {
            pendingRelink = null
            onEnrollConsumed()
        }
        AlertDialog(
            onDismissRequest = { dismissRelink() },
            shape = MaterialTheme.shapes.large,
            title = { Text("Link this phone again?") },
            text = {
                Text(
                    "This phone is already linked. Continuing replaces that link and connects it to " +
                        "${hubHost(relinkHub)} instead. Only continue if you started this sign-in yourself.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    dismissRelink()
                    scope.launch { runEnroll(relinkReq, relinkHub) }
                }) { Text("Replace link") }
            },
            dismissButton = {
                TextButton(onClick = { dismissRelink() }) { Text("Cancel") }
            },
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
        Dest.LinkedEmails -> emailReturnTo
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
                if (reduced) {
                    // Reduced motion: honour it — no horizontal slide, just a quick
                    // cross-fade (opacity is not motion).
                    fadeIn(tween(MotionTokens.fast)) togetherWith
                        fadeOut(tween(MotionTokens.fast)) using SizeTransform(clip = false)
                } else {
                    val dir = if (forward) 1 else -1
                    (slideInHorizontally(tween(320)) { w -> dir * w / 6 } + fadeIn(tween(260))) togetherWith
                        (slideOutHorizontally(tween(320)) { w -> -dir * w / 6 } + fadeOut(tween(200))) using
                        SizeTransform(clip = false)
                }
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
                    onAddEmail = { emailOnlyAdd = true; emailReturnTo = Dest.Setup; go(Dest.LinkedEmails) },
                )
                Dest.Home -> HomeScreen(
                    store = store,
                    onAddLogin = { go(Dest.Enroll) },
                    onSettings = { go(Dest.Settings) },
                    onEnterCode = { go(Dest.ManualCode) },
                    onFinishSetup = { setupFromHome = true; go(Dest.Setup) },
                    onManageEmails = { emailOnlyAdd = false; emailReturnTo = Dest.Home; go(Dest.LinkedEmails) },
                )
                Dest.LinkedEmails -> LinkedEmailsScreen(
                    store = store,
                    onlyAdd = emailOnlyAdd,
                    onBack = { go(emailReturnTo, isForward = false) },
                )
                Dest.Enroll -> EnrollScreen(store = store, onDone = { go(Dest.Home, isForward = false) })
                Dest.ManualCode -> ManualCodeScreen(store = store, onDone = { go(Dest.Home, isForward = false) })
                Dest.Settings -> SettingsScreen(
                    store = store,
                    onBack = { go(Dest.Home, isForward = false) },
                    onManageEmails = { emailOnlyAdd = false; emailReturnTo = Dest.Settings; go(Dest.LinkedEmails) },
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
