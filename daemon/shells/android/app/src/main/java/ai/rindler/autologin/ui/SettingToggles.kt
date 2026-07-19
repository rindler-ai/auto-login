// SettingToggles — the live permission/opt-in switches, shared by Settings AND the
// post-pairing setup checklist.
//
// Each toggle owns its own state machine and renders exactly ONE full-bleed [SettingRow]
// — no SectionHeader, no card, no divider — so a caller can drop it into either surface
// and get identical behaviour, identical strings, and one place to fix a bug. Settings
// supplies its own SectionHeaders around them; SetupScreen renders them bare.
//
// The house rule every one of these obeys: THE SWITCH NEVER LIES. State that the OS owns
// (an SMS grant, a Doze exemption, a notification grant) is re-derived on ON_RESUME rather
// than remembered, because the user can change it in system Settings behind our back.

package ai.rindler.autologin.ui

import ai.rindler.autologin.BatteryExemption
import ai.rindler.autologin.BuildConfig
import ai.rindler.autologin.KeystoreSecretSource
import ai.rindler.autologin.RelayService
import ai.rindler.autologin.disableEgress
import ai.rindler.autologin.mintEgress
import ai.rindler.autologin.sms.SmsAutoRead
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Battery5Bar
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The opt-in for automatic SMS code reading. OFF by default. Turning it on requests the
 * RECEIVE_SMS permission RIGHT HERE, the instant the toggle is pressed — because the app
 * runs as a background daemon (closed), so it must hold the permission up front; it
 * cannot pop a prompt from the background when a text later arrives. Grant it once and
 * the app reads incoming verification texts silently on this device, pulls out just the
 * code, and fills it in; deny and the toggle stays off. Manual entry (ManualCodeScreen)
 * always works, whichever way this is set. Renders one full-bleed [SettingRow]; the
 * caller owns any section header above it.
 */
@Composable
fun SmsAutoReadToggle(store: KeystoreSecretSource) {
    val ctx = LocalContext.current
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

    val supporting = when {
        active -> "Fills one-time codes automatically, only while a login is waiting"
        needsPermission -> "Permission was turned off — turn on again to re-grant it"
        justDenied -> "Permission denied — turn on to allow, or type codes by hand"
        else -> "Fills one-time codes automatically so a login never stalls"
    }

    SettingRow(
        leading = Icons.Rounded.Sms,
        title = "Read codes from SMS",
        supporting = supporting,
        trailing = RowTrailing.Switch(checked = active, onChange = { setEnabled(it) }),
    )
}

/**
 * The Doze exemption for the always-on relay. Checked state is the SYSTEM's answer
 * (PowerManager.isIgnoringBatteryOptimizations), re-derived on ON_RESUME — never a cached
 * belief, because the user can revoke it in system Settings at any time.
 *
 * ON opens the OS allow-dialog. OFF cannot be honoured programmatically (Android has no
 * revoke API), so it opens the system battery-optimization list and leaves the switch
 * where the system has it: a switch that flipped off while the exemption stayed on would
 * be a switch that lies.
 */
@Composable
fun BatteryToggle() {
    val ctx = LocalContext.current
    var exempt by remember { mutableStateOf(BatteryExemption.isExempt(ctx)) }
    // Both paths leave the app for a system screen, so resume is where the truth lands.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { exempt = BatteryExemption.isExempt(ctx) }

    SettingRow(
        leading = Icons.Rounded.Battery5Bar,
        title = "Keep running in the background",
        supporting = if (exempt) {
            "On — codes keep flowing while the app is closed"
        } else {
            "Android pauses idle apps — this keeps codes flowing while the app is closed"
        },
        trailing = RowTrailing.Switch(
            checked = exempt,
            onChange = { on ->
                if (on) BatteryExemption.requestExemption(ctx) else BatteryExemption.openRevokeSettings(ctx)
            },
        ),
    )
}

/**
 * Status notifications, shown ONLY when the OS can still be asked (SDK 33+ and the
 * permission is not granted) — once granted there is nothing to offer, so the row
 * disappears rather than sitting there permanently on.
 *
 * A first denial is recoverable by asking again; Android silently no-ops the second ask
 * once the user is in the permanently-denied state, so a launcher result that leaves the
 * grant unchanged routes to the app's notification settings instead of doing nothing.
 */
@Composable
fun NotificationToggle() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val ctx = LocalContext.current

    fun granted(): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    var hasPerm by remember { mutableStateOf(granted()) }
    // Covers the trip out to system notification settings as well as an OS-side revoke.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { hasPerm = granted() }
    // Set once the in-app prompt has been shown and come back denied: from then on the
    // system will not show it again, so the switch must hand off to Settings.
    var promptExhausted by remember { mutableStateOf(false) }

    val requestNotif = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { ok ->
        hasPerm = ok
        if (!ok) promptExhausted = true
    }

    fun openNotificationSettings() {
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    // Nothing to ask for — the row would be an always-on switch with no action behind it.
    if (hasPerm) return

    SettingRow(
        leading = Icons.Rounded.Notifications,
        title = "Show status notifications",
        supporting = if (promptExhausted) {
            "Blocked — turn notifications on for this app in system settings"
        } else {
            "See when this phone is asked for a login — nothing happens silently"
        },
        trailing = RowTrailing.Switch(
            checked = false,
            onChange = { on ->
                // Only an ON tap does anything: the row is shown solely in the ungranted
                // state, so there is no OFF to honour here.
                if (on) {
                    if (promptExhausted) {
                        openNotificationSettings()
                    } else {
                        requestNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            },
        ),
    )
}

/**
 * The opt-in for using this device as the hub's egress connection. OFF by default.
 * When ON, the paired device runs a tunnel egress so the user's OWN agent sessions exit
 * through THIS device's IP (RelayService.reconcileEgress -> Mobile.startEgress).
 *
 * Turning ON is a two-step consent: a confirm dialog spells out the ISP-terms tradeoff
 * FIRST, and only on confirm does the app mint a per-user egress token (mintEgress), store
 * it, and kick the relay to start the tunnel. Turning OFF unlinks locally (reconciles the
 * tunnel down at once) and best-effort revokes the token server-side (disableEgress).
 * `active` is the persisted opt-in; a mint failure snaps it back off with an error.
 * Renders one full-bleed [SettingRow] (+ its consent dialog); the caller owns any header.
 */
@Composable
fun EgressToggle(store: KeystoreSecretSource) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // Egress mint/revoke hit the SAME hub the device paired against (stored at pairing
    // time), falling back to the build-time default when unset.
    val hub = store.hubUrl() ?: BuildConfig.HUB_URL
    var active by remember { mutableStateOf(store.isEgressEnabled()) }
    var confirming by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    // The switch is the opt-in; `connected` is the go-core's LIVE handshake state, polled
    // so the status is truthful (mint can succeed while the tunnel never comes up — an
    // unreachable gateway or a handshake failure). Without this, the toggle read "On" even
    // when nothing was relaying.
    var connected by remember { mutableStateOf(RelayService.egressConnected()) }
    LaunchedEffect(active) {
        while (active) {
            // A permanent rejection (token revoked/rotated) means the tunnel will never
            // reconnect. Reflect a truthful OFF, drop the local link, and reconcile the
            // dead session away so the switch and status stop showing a phantom "on".
            if (RelayService.egressTerminated()) {
                store.unlinkEgress()
                RelayService.ensureRunning(ctx)
                active = false
                failed = true
                break
            }
            connected = RelayService.egressConnected()
            delay(2000)
        }
        connected = false
    }

    fun turnOff() {
        val token = store.deviceToken()
        store.unlinkEgress()
        active = false
        failed = false
        RelayService.ensureRunning(ctx) // reconciles egress OFF (token now gone)
        // Best-effort server-side revoke so the minted token stops working; the local
        // unlink already dropped the tunnel, so a failed revoke is only a cleanup miss.
        if (token != null) {
            scope.launch { disableEgress(hub, token) }
        }
    }

    // Only runs after the user confirms the consent dialog: mint a token, and on success
    // link it + start the tunnel. On any failure, leave the switch off with an error.
    fun confirmOn() {
        confirming = false
        failed = false
        scope.launch {
            val mint = mintEgress(hub, store.deviceToken() ?: "", Build.MODEL ?: "device")
            if (mint != null) {
                store.linkEgress(mint.token, mint.gateway)
                RelayService.ensureRunning(ctx) // reconciles egress ON
                active = true
            } else {
                active = false
                failed = true
            }
        }
    }

    fun setEnabled(on: Boolean) {
        if (!on) {
            turnOff()
            return
        }
        // Turning ON always shows the consent dialog FIRST; nothing is minted until confirm.
        failed = false
        confirming = true
    }

    val supporting = when {
        connected -> "On — sites you automate see this device's IP"
        failed -> "Couldn't turn this on — try again"
        active -> "Turning on… if this stays, check your connection"
        else -> "Your sessions come from your home internet, not a data center — fewer blocks"
    }

    SettingRow(
        leading = Icons.Rounded.SwapVert,
        title = "Route requests through this device",
        supporting = supporting,
        trailing = RowTrailing.Switch(checked = active, onChange = { setEnabled(it) }),
    )

    if (confirming) {
        AlertDialog(
            // Cancelling (dismiss or the Cancel button) reverts the switch to off: `active`
            // was never set true, so simply closing the dialog leaves it off.
            onDismissRequest = { confirming = false },
            shape = MaterialTheme.shapes.large,
            title = { Text("Use this device's connection?") },
            text = {
                // DRAFT consent copy, verbatim, pending legal review.
                Text(
                    "When on, Auto-Login routes your AI agent's web traffic through this device and its " +
                        "internet connection. Sites you automate will see THIS device's IP address, not " +
                        "the server's. Many residential ISPs prohibit running a \"server,\" \"proxy,\" or " +
                        "\"commercial\" traffic on consumer plans (e.g. Comcast Xfinity, Verizon Fios, " +
                        "T-Mobile Home Internet), which can lead to service suspension. You are responsible " +
                        "for compliance with your ISP's terms. Only your own agent sessions use this " +
                        "connection. You can turn this off anytime.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmOn() }) { Text("Turn on") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Cancel") }
            },
        )
    }
}
