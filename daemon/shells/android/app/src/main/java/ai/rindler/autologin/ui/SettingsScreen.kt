package ai.rindler.autologin.ui

import ai.rindler.autologin.BuildConfig
import ai.rindler.autologin.KeystoreSecretSource
import ai.rindler.autologin.RelayService
import ai.rindler.autologin.deriveConnectionStatus
import ai.rindler.mobile.Mobile
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Policy
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The app-specific privacy policy. Google Play requires this URL in the Data safety
 * declaration, and Apple's Guideline 5.1.1(i) requires the equivalent link to be
 * reachable from INSIDE the app — so both shells carry this row and point at the
 * same page (a PARITY shared surface; keep the copy identical to iOS's).
 */
private val PRIVACY_POLICY_URL = BuildConfig.PRIVACY_POLICY_URL

@Composable
fun SettingsScreen(
    store: KeystoreSecretSource,
    onBack: () -> Unit,
    onRepair: () -> Unit,
    onSignOut: () -> Unit,
    onAdvanced: () -> Unit,
) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    // The relay enabled/paused state drives the AccountHeader Switch here too (the same
    // component as Home; only the avatar is inert on Settings via onOpenSettings = null).
    // Actual vs requested, same split as Home: RelayService.isRunning is snapshot state, so
    // the status line follows the real session while the switch answers the tap at once.
    val running = RelayService.isRunning
    var requested by remember { mutableStateOf(running) }
    LaunchedEffect(running) { requested = running }
    val header = headerState(running, requested)
    var confirmSignOut by remember { mutableStateOf(false) }
    // Sign-out is a NETWORK action first: it unlinks the device server-side (Mobile.unpair
    // -> POST /devices/revoke-self) before anything local is wiped. If that call fails the
    // account is still linked, so we say so instead of silently wiping and leaving a live
    // device on the account. "Sign out anyway" is the explicit offline escape.
    var signingOut by remember { mutableStateOf(false) }
    var signOutError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun wipeAndLeave() {
        confirmSignOut = false
        signOutError = null
        RelayService.stop(ctx)
        store.signOut()
        onSignOut()
    }

    fun signOut() {
        val token = store.deviceToken()
        if (token.isNullOrBlank()) { wipeAndLeave(); return } // nothing linked server-side
        signingOut = true
        signOutError = null
        scope.launch {
            val hub = store.hubUrl() ?: BuildConfig.HUB_URL
            val result = withContext(Dispatchers.IO) {
                runCatching { Mobile.unpair(hub, token) }
            }
            signingOut = false
            result.fold(
                onSuccess = { wipeAndLeave() },
                onFailure = {
                    signOutError =
                        "Couldn't reach the server to unlink this phone, so it's still " +
                            "linked to your account. Check your connection and try again."
                },
            )
        }
    }

    AppScreen(title = "Settings", onBack = onBack) {
        // 1. Identity — same AccountHeader; avatar not clickable here, Switch still live.
        AccountHeader(
            email = store.accountEmail(),
            avatarUrl = store.avatarUrl(),
            status = header.status,
            serviceEnabled = header.switchOn,
            toggleInFlight = header.inFlight,
            onToggle = {
                requested = !requested
                if (requested) RelayService.ensureRunning(ctx) else RelayService.stop(ctx)
            },
            onOpenSettings = null,
            scrolled = false,
        )

        // 2. This device.
        SectionHeader("THIS DEVICE")
        SettingRow(
            leading = Icons.Rounded.Smartphone,
            title = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}",
            supporting = "Paired · credentials stored on this device",
            // No onClick → no ripple; it's context, not an action.
        )

        // 3. Sign-in codes (SMS auto-read) — shared with the setup checklist.
        SectionHeader("SIGN-IN CODES")
        SmsAutoReadToggle(store)

        // 4. Reliability — the Doze exemption the always-on relay depends on.
        SectionHeader("RELIABILITY")
        BatteryToggle()

        // 5. Connection (device egress).
        SectionHeader("CONNECTION")
        EgressToggle(store)

        // 6. Manage — the only place saved-row dividers survive (InsetDivider 56dp).
        SectionHeader("MANAGE")
        SettingRow(
            leading = Icons.Rounded.Sync,
            title = "Re-pair device",
            trailing = RowTrailing.Chevron,
            onClick = onRepair,
        )
        InsetDivider(56.dp)
        SettingRow(
            leading = Icons.Rounded.Tune,
            title = "Advanced",
            trailing = RowTrailing.Chevron,
            onClick = onAdvanced,
        )
        InsetDivider(56.dp)
        SettingRow(
            leading = Icons.Rounded.Policy,
            title = "Privacy policy",
            trailing = RowTrailing.Chevron,
            onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL))) },
        )

        // 7. Danger zone, spatially last (destructive-isolation).
        Spacer(Modifier.height(32.dp))
        InsetDivider(0.dp)
        Spacer(Modifier.height(8.dp))
        SettingRow(
            leading = Icons.AutoMirrored.Rounded.Logout,
            title = "Sign out",
            danger = true,
            onClick = { confirmSignOut = true },
        )
        Spacer(Modifier.height(24.dp))
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            shape = MaterialTheme.shapes.large,
            title = { Text("Sign out?") },
            text = {
                Text(
                    signOutError
                        ?: "This unlinks this phone from your Rindler account and erases all logins saved on this device.",
                    color = if (signOutError != null) cs.error else Color.Unspecified,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !signingOut,
                    // First attempt unlinks server-side; after a failure the same button
                    // becomes the explicit local-only escape, clearly labelled.
                    onClick = { if (signOutError != null) wipeAndLeave() else signOut() },
                ) {
                    Text(
                        when {
                            signingOut -> "Signing out…"
                            signOutError != null -> "Sign out anyway"
                            else -> "Sign out"
                        },
                        color = cs.error,
                    )
                }
            },
            dismissButton = {
                TextButton(enabled = !signingOut, onClick = {
                    confirmSignOut = false; signOutError = null
                }) { Text("Cancel") }
            },
        )
    }
}
