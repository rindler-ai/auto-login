package ai.rindler.autologin.ui

import ai.rindler.autologin.BuildConfig
import ai.rindler.autologin.KeystoreSecretSource
import ai.rindler.mobile.Mobile
import android.net.Uri
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PairScreen(store: KeystoreSecretSource, onPaired: () -> Unit) {
    // A branded build ships a real BuildConfig.HUB_URL, so the field is prefilled and
    // usually left as-is. A self-host / open-source build ships a placeholder, so the
    // field starts empty and the user pastes their own hub's URL. A previously-stored
    // value always wins so a re-pair reconnects to the same hub.
    val defaultHub = remember { BuildConfig.HUB_URL.takeUnless { it.contains("your-hub.example") } ?: "" }
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
        Text("Pair this device", style = MaterialTheme.typography.headlineMedium, color = cs.onBackground)
        Spacer(Modifier.height(12.dp))
        Text(
            "Enter your hub's address, then the pairing code from its console (Settings → Devices → Add device).",
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(36.dp))
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
        AnimatedVisibility(error != null) {
            ErrorRow(error ?: "")
        }
        Spacer(Modifier.height(28.dp))
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
                    // Persist the hub BEFORE pairing so the relay reconnects to the same
                    // one; the pair/complete endpoint is derived from it below.
                    store.setHubUrl(h)
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                val keyB64 = Mobile.generateDeviceKey()
                                val pubB64 = Mobile.devicePublicKey(keyB64)
                                // pair() returns BOTH the hub bearer token and the server's
                                // ping-signing public key. Persist both: the Go core verifies
                                // every SecretPing against that key before sealing a credential
                                // to the worker, so a device that saved only the token
                                // declines every login.
                                val res = Mobile.pair(
                                    pairUrl(h), code, deviceName(), "android", pubB64,
                                )
                                store.saveIdentity(res.deviceToken, keyB64, res.serverPubkey)
                            }
                        }
                        busy = false
                        result.fold(
                            onSuccess = { onPaired() },
                            onFailure = { error = friendlyPairError(it.message) },
                        )
                    }
                }
            },
        )
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

private fun friendlyPairError(raw: String?): String = when {
    raw == null -> "Something went wrong. Try again."
    // Pairing-channel TOFU (follow-up): the server key at pair/complete did
    // not match the fingerprint in the code — a possible on-path MITM.
    raw.contains("could not verify the hub's identity") -> "This device couldn't verify the hub's identity. You may be on an untrusted network. Try again from a trusted connection."
    raw.contains("401") || raw.contains("invalid") -> "That code didn't work. It may have expired, so generate a new one."
    raw.contains("timeout") || raw.contains("connect") -> "Couldn't reach the hub. Check your connection and retry."
    else -> "Couldn't pair. Generate a fresh code and try again."
}

// wss://host/v1/devices/connect -> https://host/devices/pair/complete
private fun pairUrl(hubUrl: String): String {
    val u = Uri.parse(hubUrl)
    val scheme = if (u.scheme.equals("ws", ignoreCase = true)) "http" else "https"
    return "$scheme://${u.host}${if (u.port > 0) ":${u.port}" else ""}/devices/pair/complete"
}

private fun deviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
