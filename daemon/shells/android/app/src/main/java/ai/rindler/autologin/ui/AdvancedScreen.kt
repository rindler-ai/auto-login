package ai.rindler.autologin.ui

import ai.rindler.autologin.KeystoreSecretSource
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

// The "settings within settings" Advanced page: point Auto-Login at a self-hosted
// server instead of the default, and pair with a code. Buried behind Settings ->
// Advanced (post-auth) and the sign-in screen's "Use a self-hosted server" link
// (pre-auth bootstrap), so normal users never see server/pairing fields.
@Composable
fun AdvancedScreen(
    store: KeystoreSecretSource,
    onPaired: () -> Unit,
    onBack: () -> Unit,
) {
    var hub by remember { mutableStateOf(store.hubUrl() ?: "") }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        TopBar(title = "Advanced", onBack = onBack)
        Spacer(Modifier.height(16.dp))
        Text(
            "SELF-HOSTED SERVER",
            style = MaterialTheme.typography.labelSmall,
            color = cs.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        Text(
            "Most people don't need this. Point Auto-Login at your own server instead of the default.",
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 18.dp),
        )
        AppTextField(
            value = hub,
            onValueChange = { hub = it.trim(); error = null },
            label = "Server address (wss://…)",
            isError = error != null,
        )
        Spacer(Modifier.height(14.dp))
        AppTextField(
            value = code,
            onValueChange = { code = it.trim(); error = null },
            label = "Pairing code",
            isError = error != null,
        )
        AnimatedVisibility(error != null) { ErrorRow(error ?: "") }
        Spacer(Modifier.height(16.dp))
        PrimaryButton(
            text = "Connect",
            enabled = code.isNotBlank() && hub.isNotBlank(),
            loading = busy,
            onClick = {
                val h = hub.trim()
                if (!h.startsWith("ws://") && !h.startsWith("wss://")) {
                    // The relay + secrets ride this channel, so the URL must be a
                    // WebSocket endpoint; a wrong scheme is the most common paste mistake.
                    error = "Your server address should start with wss:// (or ws://)."
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
