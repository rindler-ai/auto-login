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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private sealed interface SubmitState {
    data object Idle : SubmitState
    data object Submitting : SubmitState
    data object Success : SubmitState
    data object NoPendingLogin : SubmitState
    data class Failed(val message: String) : SubmitState
}

@Composable
fun ManualCodeScreen(
    store: KeystoreSecretSource,
    onDone: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var state by remember { mutableStateOf<SubmitState>(SubmitState.Idle) }

    // After a successful send, hold "Sent" on screen for a beat, then close.
    LaunchedEffect(state) {
        if (state is SubmitState.Success) {
            delay(1000)
            onDone()
        }
    }

    val trimmed = code.trim()
    val submitting = state is SubmitState.Submitting

    fun submit() {
        if (trimmed.isEmpty() || submitting) return
        val token = store.deviceToken()
        if (token == null) {
            state = SubmitState.Failed("This device isn't paired anymore. Re-pair in Settings, then retry.")
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
                    SubmitState.Failed("This device's pairing looks invalid. Re-pair in Settings, then try again.")
                CodeSubmitResult.FAILED ->
                    SubmitState.Failed("Couldn't reach the server. Check your connection and try again.")
            }
        }
    }

    // Fixed header, a weighted scrollable body, and the trust line pinned to the
    // bottom — the same vertical rhythm as Home/Settings so the screen never looks
    // top-crammed with a floating footer.
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        TopBar(title = "Enter code", onBack = onDone)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(8.dp))
            IconChip(Icons.Rounded.Dialpad, size = 52)
            Spacer(Modifier.height(16.dp))
            Text(
                "Type the code your login is waiting for and Auto-Login will submit it.",
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))

            AppTextField(
                value = code,
                onValueChange = { new ->
                    code = new.filter { it.isDigit() }.take(10)
                    // Clear a prior result on a fresh edit, but NEVER interrupt an
                    // in-flight submit (the field is locked below anyway) — resetting
                    // Submitting->Idle would defeat the re-entry guard and let a second
                    // tap fire a concurrent POST.
                    if (state != SubmitState.Idle && state != SubmitState.Submitting) state = SubmitState.Idle
                },
                label = "2FA code",
                enabled = !submitting, // lock the field while the POST is in flight
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            )

            Spacer(Modifier.height(12.dp))
            StatusRow(state)

            Spacer(Modifier.height(24.dp))
            PrimaryButton(
                text = "Submit",
                onClick = { submit() },
                enabled = trimmed.isNotEmpty(),
                loading = submitting,
            )
        }
        TrustFooter()
    }
}

@Composable
private fun StatusRow(state: SubmitState) {
    val cs = MaterialTheme.colorScheme
    val (icon, text, tint) = when (state) {
        is SubmitState.Success ->
            Triple(Icons.Rounded.CheckCircle, "Sent — your login is continuing.", cs.primary)
        is SubmitState.NoPendingLogin ->
            Triple(Icons.Rounded.Info, "No login is waiting for a code right now.", cs.onSurfaceVariant)
        is SubmitState.Failed ->
            Triple(Icons.Rounded.ErrorOutline, state.message, cs.error)
        else -> return
    }
    InlineStatus(icon, text, tint)
}

@Composable
private fun InlineStatus(icon: ImageVector, text: String, tint: Color) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = tint)
    }
}
