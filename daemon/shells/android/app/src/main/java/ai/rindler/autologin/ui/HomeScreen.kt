package ai.rindler.autologin.ui

import ai.rindler.autologin.BuildConfig
import ai.rindler.autologin.KeystoreSecretSource
import ai.rindler.autologin.RelayService
import ai.rindler.autologin.fetchSupportedSites
import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.ui.draw.scale
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun HomeScreen(
    store: KeystoreSecretSource,
    onAddLogin: () -> Unit,
    onSettings: () -> Unit,
    onEnterCode: () -> Unit,
) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var active by remember { mutableStateOf(RelayService.isRunning) }
    var sites by remember { mutableStateOf(store.sites()) }
    // Supported-site domains (lowercased) from the live catalog, so a saved login for
    // a site the hub hasn't mapped yet is badged with a warning. Empty until loaded /
    // on failure, which just means no badges (fail-open, never a false warning).
    var supported by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(Unit) {
        supported = fetchSupportedSites(BuildConfig.CATALOG_URL)
            .map { it.domain.trim().lowercase().removePrefix("www.") }
            .toSet()
    }
    // Tapping a saved login opens a remove confirmation. We never show the stored
    // credential — the only management action is to delete it.
    var pendingDelete by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        // Brand header — fixed above the scroll region.
        Row(
            Modifier.fillMaxWidth().height(64.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(32.dp).clip(MaterialTheme.shapes.small)
                    .background(cs.primary.copy(alpha = 0.13f)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Shield, null, tint = cs.primary, modifier = Modifier.size(18.dp)) }
            Spacer(Modifier.size(10.dp))
            Text("Auto-Login", style = MaterialTheme.typography.titleLarge, color = cs.onBackground)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onSettings) {
                Icon(Icons.Rounded.Settings, "Settings", tint = cs.onSurfaceVariant)
            }
        }

        // Scrollable middle — takes the remaining height so the trust line pins to
        // the bottom instead of leaving a dead lower half.
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(8.dp))
            StatusCard(active = active, onToggle = {
                active = !active
                if (active) RelayService.ensureRunning(ctx) else RelayService.stop(ctx)
            })

            // Low-key path to type a 2FA code by hand — the reliability floor when a
            // login is waiting and auto-read is off or missed the text.
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
                    .clickable(onClick = onEnterCode).padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Dialpad, null, tint = cs.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("Enter a login code", style = MaterialTheme.typography.labelLarge, color = cs.primary)
            }

            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Saved logins",
                    style = MaterialTheme.typography.titleMedium,
                    color = cs.onBackground,
                )
                Spacer(Modifier.weight(1f))
                if (sites.isNotEmpty()) {
                    Text("${sites.size}", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(12.dp))

            if (sites.isEmpty()) {
                // Empty state owns the single call-to-action (no separate button
                // beneath it repeating the same ask).
                EmptyLogins(onAdd = onAddLogin)
            } else {
                AppCard {
                    Column {
                        sites.forEachIndexed { i, s ->
                            val unsupported = supported.isNotEmpty() &&
                                !supported.contains(s.trim().lowercase().removePrefix("www."))
                            LoginRow(site = s, unsupported = unsupported, onClick = { pendingDelete = s })
                            if (i != sites.lastIndex) {
                                Box(
                                    Modifier
                                        .padding(start = 68.dp)
                                        .fillMaxWidth()
                                        .height(1.dp)
                                        .background(cs.outlineVariant),
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                SecondaryButton(
                    text = "Add a login",
                    onClick = onAddLogin,
                    leading = {
                        Icon(Icons.Rounded.Add, null, tint = cs.onSurface, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.size(8.dp))
                    },
                )
            }
            Spacer(Modifier.height(24.dp))
        }

        TrustFooter()
    }

    pendingDelete?.let { site ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            shape = MaterialTheme.shapes.large,
            icon = { SiteAvatar(site, size = 40) },
            title = { Text("Remove this login?") },
            text = {
                Text("Auto-Login won't be able to sign you in to $site until you add it again. The stored login is erased from this device.")
            },
            confirmButton = {
                TextButton(onClick = {
                    store.delete(site)
                    sites = store.sites()
                    pendingDelete = null
                }) { Text("Remove", color = cs.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun StatusCard(active: Boolean, onToggle: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val container by animateColorAsState(
        // primary-at-alpha when active (same accent hue the rest of the app uses),
        // neutral fill when paused.
        if (active) cs.primary.copy(alpha = 0.13f) else cs.surfaceContainerHigh, label = "c",
    )
    // A slow breath on the active badge — a "live" signal that isn't distracting.
    val breath = rememberInfiniteTransition(label = "breath")
    val s by breath.animateFloat(1f, 1.06f, infiniteRepeatable(tween(1500), androidx.compose.animation.core.RepeatMode.Reverse), label = "s")
    AppCard {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(48.dp).scale(if (active) s else 1f).clip(CircleShape).background(container),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (active) Icons.Rounded.Shield else Icons.Rounded.Pause,
                        contentDescription = null,
                        tint = if (active) cs.primary else cs.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Spacer(Modifier.size(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (active) "Active" else "Paused",
                        style = MaterialTheme.typography.titleLarge,
                        color = cs.onSurface,
                    )
                    Text(
                        if (active) "Your logins are ready when the hub needs them"
                        else "Sign-ins are paused until you resume",
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            if (active) {
                SecondaryButton(text = "Pause protection", onClick = onToggle)
            } else {
                PrimaryButton(text = "Resume protection", onClick = onToggle)
            }
        }
    }
}

@Composable
private fun LoginRow(site: String, unsupported: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SiteLogo(site, warning = unsupported)
        Spacer(Modifier.size(14.dp))
        Column(Modifier.weight(1f)) {
            Text(site, style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
            Text(
                if (unsupported) "Not supported yet — stored on this device" else "Stored on this device",
                style = MaterialTheme.typography.labelMedium,
                color = cs.onSurfaceVariant,
            )
        }
        Icon(
            Icons.Rounded.Lock,
            contentDescription = null,
            tint = cs.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun EmptyLogins(onAdd: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    AppCard {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IconChip(Icons.Outlined.Language, size = 48)
            Spacer(Modifier.height(14.dp))
            Text("No logins yet", style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
            Spacer(Modifier.height(4.dp))
            Text(
                // Non-breaking space bonds the final two words so the last line never
                // orphans a single word ("ask." on its own line).
                "Add a site and Auto-Login can sign you in whenever you ask.",
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            PrimaryButton(
                text = "Add a login",
                onClick = onAdd,
            )
        }
    }
}
