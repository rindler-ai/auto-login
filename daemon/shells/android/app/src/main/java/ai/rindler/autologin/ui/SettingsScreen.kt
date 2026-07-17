package ai.rindler.autologin.ui

import ai.rindler.autologin.BuildConfig
import ai.rindler.autologin.KeystoreSecretSource
import ai.rindler.autologin.RelayService
import ai.rindler.autologin.sms.SmsAutoRead
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

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
    onReset: () -> Unit,
) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var confirmReset by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        TopBar(title = "Settings", onBack = onBack)
        Spacer(Modifier.height(16.dp))

        // This device — grounds the screen with real context.
        AppCard {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                IconChip(Icons.Rounded.Smartphone)
                Spacer(Modifier.size(14.dp))
                Column {
                    Text(
                        "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}",
                        style = MaterialTheme.typography.titleMedium, color = cs.onSurface,
                    )
                    Text("Paired · credentials stored on this device", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(20.dp))

        SmsAutoReadSection(store)
        Spacer(Modifier.height(20.dp))

        Text(
            "MANAGE",
            style = MaterialTheme.typography.labelSmall,
            color = cs.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        AppCard {
            Column {
                SettingRow(
                    icon = Icons.Rounded.Refresh,
                    title = "Re-pair this device",
                    subtitle = "Connect to a different account",
                    onClick = onRepair,
                )
                Divider()
                SettingRow(
                    icon = Icons.Rounded.PrivacyTip,
                    title = "Privacy Policy",
                    subtitle = "How your logins and codes are handled",
                    onClick = {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL)))
                    },
                )
                Divider()
                SettingRow(
                    icon = Icons.Rounded.DeleteOutline,
                    title = "Reset device",
                    subtitle = "Erase all logins and start over",
                    danger = true,
                    onClick = { confirmReset = true },
                )
            }
        }

        Spacer(Modifier.weight(1f))
        Text(
            "Auto-Login · Encrypted on-device storage",
            style = MaterialTheme.typography.labelMedium,
            color = cs.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            shape = MaterialTheme.shapes.large,
            title = { Text("Reset this device?") },
            text = {
                Text("This erases every saved login and unpairs the device. It can't be undone.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    RelayService.stop(ctx)
                    store.reset()
                    onReset()
                }) { Text("Erase everything", color = cs.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text("Cancel") }
            },
        )
    }
}

/**
 * The opt-in for automatic SMS code reading. OFF by default. Turning it on requests the
 * RECEIVE_SMS permission RIGHT HERE, the instant the toggle is pressed — because the app
 * runs as a background daemon (closed), so it must hold the permission up front; it
 * cannot pop a prompt from the background when a text later arrives. Grant it once and
 * The app reads incoming verification texts silently on this device, pulls out just the
 * code, and fills it in; deny and the toggle stays off. Manual entry (ManualCodeScreen)
 * always works, whichever way this is set.
 */
@Composable
private fun SmsAutoReadSection(store: KeystoreSecretSource) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    // optedIn is the persisted intent; hasPerm is the live RECEIVE_SMS grant. "Active"
    // needs BOTH, so the toggle reflects reality: if Android auto-revokes the grant from
    // an unused daemon (or the user revokes it in system Settings), the switch drops to
    // off and the helper prompts to re-grant, instead of claiming "On" while SMS silently
    // stops arriving. hasPerm is re-derived every time this screen resumes.
    var optedIn by remember { mutableStateOf(store.isSmsAutoReadEnabled()) }
    var hasPerm by remember { mutableStateOf(SmsAutoRead.hasPermission(ctx)) }
    var justDenied by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { hasPerm = SmsAutoRead.hasPermission(ctx) }

    val active = optedIn && hasPerm
    val needsPermission = optedIn && !hasPerm

    // Persist the opt-in and make sure the foreground relay is up, so the process stays
    // alive to forward a code when a text arrives.
    fun turnOn() {
        store.setSmsAutoReadEnabled(true)
        optedIn = true
        hasPerm = true
        justDenied = false
        RelayService.ensureRunning(ctx)
    }

    // The permission dialog fires from this launcher, synchronously with the toggle press
    // (an Activity is foreground now). Grant -> on; deny -> the toggle stays off with an
    // explanation, never a silent no-op.
    val requestSms = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) turnOn() else justDenied = true }

    fun setEnabled(on: Boolean) {
        if (!on) {
            // Opt out: the manifest receiver goes inert immediately (it checks this flag).
            // The granted permission is harmless left in place; flipping back on is instant.
            store.setSmsAutoReadEnabled(false)
            optedIn = false
            justDenied = false
            return
        }
        justDenied = false
        if (SmsAutoRead.hasPermission(ctx)) turnOn() else requestSms.launch(Manifest.permission.RECEIVE_SMS)
    }

    Text(
        "SIGN-IN CODES",
        style = MaterialTheme.typography.labelSmall,
        color = cs.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
    )
    AppCard {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconChip(Icons.Rounded.Sms)
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Fill in codes from a text", style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
                Text(
                    "Read verification codes automatically so a login never stalls",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                )
            }
            Spacer(Modifier.size(12.dp))
            Switch(checked = active, onCheckedChange = { setEnabled(it) })
        }
    }
    Spacer(Modifier.height(10.dp))
    val helper = when {
        active ->
            "On — when a login asks for a texted code, Auto-Login reads just the code from that one message on " +
                "this device and fills it in. It only ever looks while a login is waiting, never your other " +
                "texts, and you can always type a code in by hand."
        needsPermission ->
            "Permission to read verification texts was turned off. Flip the toggle on to grant it again — " +
                "until then you'll type 2FA codes in the app by hand."
        justDenied ->
            "Auto-Login needs permission to read verification texts. Flip the toggle on to allow it (or grant SMS " +
                "access in Android Settings). Until then, you'll type 2FA codes in the app by hand."
        else ->
            "Off — you'll type 2FA codes in the app when the hub asks. Turn on and Android asks once for " +
                "permission; after that Auto-Login reads a code only while a login is waiting for one, on this device."
    }
    Text(
        helper,
        style = MaterialTheme.typography.labelMedium,
        color = cs.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val tint = if (danger) cs.error else cs.primary
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconChip(icon, tint = tint, bg = tint.copy(alpha = 0.13f))
        Spacer(Modifier.size(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (danger) cs.error else cs.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun Divider() {
    Box(
        Modifier
            .padding(start = 68.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}
