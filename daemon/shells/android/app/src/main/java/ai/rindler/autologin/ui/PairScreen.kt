package ai.rindler.autologin.ui

import ai.rindler.autologin.BuildConfig
import ai.rindler.autologin.KeystoreSecretSource
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

// The first-run setup screen. Primary path: tap "Sign in", sign in in the Custom Tab,
// and the device auto-enrolls when the browser redirects back (handled by
// MainActivity -> AutoLoginApp). A manual entry — hub URL + pairing code — stays as a
// fallback for anyone who prefers it or whose hub doesn't serve the sign-in flow.
@Composable
fun PairScreen(store: KeystoreSecretSource, onPaired: () -> Unit) {
    val ctx = LocalContext.current
    // A branded build ships a real BuildConfig.HUB_URL, so the field is prefilled and
    // usually left as-is. A self-host / open-source build ships a placeholder, so the
    // field starts empty and the user pastes their own hub's URL. A previously-stored
    // value always wins so a re-pair reconnects to the same hub.
    val defaultHub = remember { BuildConfig.HUB_URL.takeUnless { it.contains("your-hub.example") } ?: "" }
    var showManual by remember { mutableStateOf(false) }
    var hub by remember { mutableStateOf(store.hubUrl() ?: defaultHub) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme

    Column(
        Modifier.fillMaxSize().padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Balance the block just above center; the trust line anchors the bottom so
        // there's no dead lower half.
        Spacer(Modifier.weight(0.85f))
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(cs.primary.copy(alpha = 0.13f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Shield, null, tint = cs.primary, modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.height(28.dp))
        Text("Set up this device", style = MaterialTheme.typography.headlineMedium, color = cs.onBackground)
        Spacer(Modifier.height(12.dp))
        Text(
            "Sign in to your hub and this phone sets itself up automatically. No code to copy.",
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(36.dp))
        PrimaryButton(
            text = "Sign in",
            enabled = !busy,
            onClick = {
                error = null
                openSignInEnroll(ctx)
            },
        )
        AnimatedVisibility(error != null) { ErrorRow(error ?: "") }

        Spacer(Modifier.height(16.dp))
        if (!showManual) {
            TextButton(onClick = { showManual = true }) {
                Text("Enter a code instead", color = cs.onSurfaceVariant)
            }
        } else {
            AppTextField(
                value = hub,
                onValueChange = { hub = it.trim(); error = null },
                label = "Hub URL (wss://…)",
                isError = error != null,
            )
            Spacer(Modifier.height(14.dp))
            AppTextField(
                value = code,
                onValueChange = { code = it.trim(); error = null },
                label = "Pairing code",
                isError = error != null,
            )
            Spacer(Modifier.height(12.dp))
            PrimaryButton(
                text = "Pair",
                enabled = code.isNotBlank() && hub.isNotBlank(),
                loading = busy,
                onClick = {
                    val h = hub.trim()
                    if (!h.startsWith("ws://") && !h.startsWith("wss://")) {
                        // The relay + secrets ride this channel, so the URL must be a
                        // WebSocket endpoint; a wrong scheme is the most common paste mistake.
                        error = "Your hub URL should start with wss:// (or ws://)."
                    } else {
                        busy = true; error = null
                        scope.launch {
                            val result = completeEnroll(store, code, h)
                            busy = false
                            result.fold(
                                onSuccess = { onPaired() },
                                onFailure = { error = friendlyPairError(it.message) },
                            )
                        }
                    }
                },
            )
        }
        Spacer(Modifier.weight(1.15f))
        TrustFooter()
    }
}

@Composable
private fun ErrorRow(msg: String) {
    val cs = MaterialTheme.colorScheme
    androidx.compose.foundation.layout.Row(
        Modifier.padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Rounded.ErrorOutline, null, tint = cs.error, modifier = Modifier.size(16.dp))
        Text(msg, style = MaterialTheme.typography.bodyMedium, color = cs.error)
    }
}
