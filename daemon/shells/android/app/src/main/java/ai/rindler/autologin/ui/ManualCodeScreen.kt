// ManualCodeScreen — the manual 2FA code-entry sheet: type the code your login is
// waiting for, tap Submit, done. This is the reliability FLOOR under automatic SMS
// reading (sms/SmsAutoRead): if the user left auto-read off, dismissed Android's
// share-this-text consent prompt, or auto-read ever misses a text, this is how a
// paused login still moves. Reachable any time from Home, regardless of whether
// auto-read is set up.
//
// Posts to the SAME device-authed rendezvous the SMS reader uses
// (submitOtpCode -> POST /devices/sms-relay/manual); from the paused login's view
// the two paths are identical. Never logs the typed code (consume-and-forget).
// Mirrors iOS ManualCodeView.swift.

package ai.rindler.autologin.ui

import ai.rindler.autologin.BuildConfig
import ai.rindler.autologin.CodeSubmitResult
import ai.rindler.autologin.KeystoreSecretSource
import ai.rindler.autologin.submitOtpCode
import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** True when a screen reader (TalkBack) drives the UI — touch exploration is on. */
private fun screenReaderActive(context: Context): Boolean {
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
    return am?.isTouchExplorationEnabled == true
}

private sealed interface SubmitState {
    data object Idle : SubmitState
    data object Submitting : SubmitState
    data object Success : SubmitState
    data object NoPendingLogin : SubmitState
    data class Failed(val message: String) : SubmitState
}

// The widest code the system ever recognizes. core/otp ExtractCode caps a mixed
// alphanumeric code at 12 chars (alnumMaxLen); a digit code at 8. The auto-read path
// can therefore never submit a code longer than 12, so the manual field caps there too
// — long enough to never truncate a real code, short enough to guard against paste junk,
// and kept in lockstep with the extractor so both paths agree on what a code can be.
private const val MAX_CODE_LEN = 12

/**
 * Reduce raw text-field input to the EXACT alphabet a submitted code uses, so a typed
 * code and an auto-read code reach the rendezvous byte-identically.
 *
 * The auto-read path forwards whatever core/otp ExtractCode returns, and that output is
 * always ASCII [A-Za-z0-9] with every separator stripped and case preserved:
 *   - grouped digit codes drop their space/hyphen  ("048 913"  -> "048913"),
 *   - hyphen-grouped alphanumeric codes reassemble WITHOUT the hyphen
 *       ("X7G2-9K1P" -> "X7G29K1P"),  [core/otp/extract_test.go "alnum-hyphen-grouped"]
 *   - the alphanumeric matcher is \b[A-Za-z0-9]{4,12}\b and is CASE-PRESERVING
 *       ("a1b2c3" stays "a1b2c3").    [core/otp/extract_test.go "alnum-lower"]
 *
 * So this: keeps ASCII letters and digits, drops everything else (spaces, hyphens, any
 * punctuation, and — deliberately using explicit ASCII ranges rather than Kotlin's
 * Unicode-aware isLetterOrDigit()/isDigit() — every non-ASCII letter/digit, emoji, and
 * full-width/other-script digit that the extractor's ASCII regex would never match),
 * does NOT change case (some sites' codes are case-sensitive; the extractor preserves
 * case, so we must not force it), and caps at MAX_CODE_LEN.
 *
 * NOTE the ONE divergence from auto-read: a Google-style SMS body "G-4F2K9A" is extracted
 * by ExtractCode as "4F2K9A" (it drops the lone "G-" prefix); a user who types "G-4F2K9A"
 * verbatim here sanitizes to "G4F2K9A". Replicating the extractor's prefix/keyword
 * heuristics is out of scope for a field with no surrounding prose to disambiguate — the
 * user types the code characters they intend.
 */
fun sanitizeCodeInput(raw: String): String =
    raw.filter { it in '0'..'9' || it in 'A'..'Z' || it in 'a'..'z' }.take(MAX_CODE_LEN)

@Composable
fun ManualCodeScreen(
    store: KeystoreSecretSource,
    onDone: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // rememberSaveable: a half-typed code must survive a device rotation mid-entry — that
    // rotation lands in the middle of a time-critical 2FA window, and a dropped code forces
    // a re-read. The code is already visible on this FLAG_SECURE screen, so persisting it
    // into the saved-instance bundle does not widen its exposure. Only the code field is
    // saved; `state` stays transient (a mid-submit status should reset on recreate).
    var code by rememberSaveable { mutableStateOf("") }
    var state by remember { mutableStateOf<SubmitState>(SubmitState.Idle) }

    // After a successful send, hold "Sent" on screen for a beat, then close. The success
    // StatusLine is a Polite live region, but at 1000ms the screen auto-closes before
    // TalkBack finishes speaking it, so a screen-reader user never hears the success. When
    // touch exploration is on, hold ~3x longer so the announcement lands before the close.
    LaunchedEffect(state) {
        if (state is SubmitState.Success) {
            delay(if (screenReaderActive(ctx)) 3000 else 1000)
            onDone()
        }
    }

    val trimmed = code.trim()
    val submitting = state is SubmitState.Submitting

    fun submit() {
        if (trimmed.isEmpty() || submitting) return
        val token = store.deviceToken()
        if (token == null) {
            state = SubmitState.Failed("This phone is no longer linked to your account. Sign in again from Settings, then retry.")
            return
        }
        state = SubmitState.Submitting
        // POST to the hub the device paired against, not the build-time default: the
        // device token and the paused login live only on that hub.
        val hub = store.hubUrl() ?: BuildConfig.HUB_URL
        scope.launch {
            state = when (submitOtpCode(hub, token, trimmed)) {
                CodeSubmitResult.DELIVERED -> SubmitState.Success
                CodeSubmitResult.NO_PENDING_LOGIN -> SubmitState.NoPendingLogin
                CodeSubmitResult.UNAUTHORIZED ->
                    SubmitState.Failed("This phone's link to your account is no longer valid. Sign in again from Settings, then retry.")
                CodeSubmitResult.FAILED ->
                    SubmitState.Failed("Couldn't reach the server. Check your connection and try again.")
            }
        }
    }

    AppScreen(title = "Enter code", onBack = onDone, footer = true) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(24.dp))
            IconBadge(Icons.Rounded.Dialpad, 56.dp)
            Spacer(Modifier.height(16.dp))
            Text(
                "Type the code your login is waiting for and Auto Login will submit it.",
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))

            AppTextField(
                value = code,
                onValueChange = { new ->
                    code = sanitizeCodeInput(new)
                    // Clear a prior result on a fresh edit, but NEVER interrupt an
                    // in-flight submit (the field is locked below anyway) — resetting
                    // Submitting->Idle would defeat the re-entry guard and let a second
                    // tap fire a concurrent POST.
                    if (state != SubmitState.Idle && state != SubmitState.Submitting) state = SubmitState.Idle
                },
                label = "Verification code",
                mono = true,
                enabled = !submitting, // lock the field while the POST is in flight
                // Codes are alphanumeric (e.g. "G4F2K9A"), so a number-only IME would make
                // letters untypeable. Password type gives the full alphanumeric keyboard;
                // autoCorrectEnabled=false stops autocorrect from mangling an opaque code
                // and keeps it out of the keyboard's learning dictionary.
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    autoCorrectEnabled = false,
                ),
            )

            Spacer(Modifier.height(4.dp))
            StatusRow(state)

            Spacer(Modifier.height(24.dp))
            PrimaryButton(
                text = "Submit",
                onClick = { submit() },
                enabled = trimmed.isNotEmpty(),
                loading = submitting,
            )
        }
    }
}

@Composable
private fun StatusRow(state: SubmitState) {
    when (state) {
        is SubmitState.Success ->
            StatusLine(StatusKind.Success, "Sent — your login is continuing.")
        is SubmitState.NoPendingLogin ->
            StatusLine(StatusKind.Info, "No login is waiting for a code right now.")
        is SubmitState.Failed ->
            StatusLine(StatusKind.Error, state.message)
        else -> Unit
    }
}
