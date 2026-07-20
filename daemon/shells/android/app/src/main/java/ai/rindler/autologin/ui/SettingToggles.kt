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
import ai.rindler.autologin.EgressMintResult
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Battery5Bar
import androidx.compose.material.icons.rounded.MailOutline
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
import androidx.compose.ui.Modifier
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
        needsPermission -> "SMS permission was turned off — turn this on to allow it again"
        justDenied -> "Permission denied — turn on to allow, or type codes by hand"
        else -> "Lets this app see incoming texts to pull out one-time codes — only while a sign-in is waiting for one"
    }

    SettingRow(
        leading = Icons.Rounded.Sms,
        title = "Read codes from SMS",
        supporting = supporting,
        trailing = RowTrailing.Switch(checked = active, onChange = { setEnabled(it) }),
    )
}

/**
 * The opt-in for automatic EMAIL code reading — the exact mirror of [SmsAutoReadToggle],
 * where a LINKED MAILBOX is email's analog of the SMS permission. OFF by default. Auto-read
 * is "active" only when the user opted in AND at least one mailbox is linked, so the switch
 * never claims "On" while there is no inbox to read (a mailbox can be removed or break on the
 * manage page behind our back — [store.isEmailLinked] is re-derived on ON_RESUME, never
 * remembered).
 *
 * Turning it ON with no mailbox linked routes to the link flow ([onLinkMailbox]) instead of
 * silently no-opping — the way the SMS toggle pops the permission prompt. Linking a mailbox
 * itself flips the opt-in on ([KeystoreSecretSource.linkEmail]), so this toggle is the
 * EXPLICIT control layered on top of that, not a replacement for it. Turning OFF disables
 * auto-read (the reader gates on the same flag); the linked mailbox stays put, so flipping
 * back on is instant. Renders one full-bleed [SettingRow]; the caller owns any header.
 */
@Composable
fun EmailAutoReadToggle(store: KeystoreSecretSource, onLinkMailbox: () -> Unit) {
    // optedIn is the persisted intent; linked is whether a mailbox exists on this device.
    // "Active" needs BOTH — the same two-condition shape as SMS (opt-in + grant). Re-derived
    // on resume so a mailbox added in the link flow, or removed / broken on the manage page,
    // is reflected without the switch lying.
    var optedIn by remember { mutableStateOf(store.isEmailAutoReadEnabled()) }
    var linked by remember { mutableStateOf(store.isEmailLinked()) }
    // Linking a mailbox flips the opt-in on and removing the last one flips it off, so BOTH
    // are re-read together — coming back from the link flow must reflect the new state.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        optedIn = store.isEmailAutoReadEnabled()
        linked = store.isEmailLinked()
    }

    val active = emailAutoReadActive(optedIn, linked)
    val needsMailbox = optedIn && !linked

    fun setEnabled(on: Boolean) {
        if (!on) {
            // Opt out: the reader goes inert immediately (it gates on this flag). The linked
            // mailbox is left in place; flipping back on is instant, and removing a mailbox
            // is a separate action on the manage page.
            store.setEmailAutoReadEnabled(false)
            optedIn = false
            return
        }
        if (store.isEmailLinked()) {
            store.setEmailAutoReadEnabled(true)
            optedIn = true
            linked = true
        } else {
            // Nothing to read yet. Turning this on with no mailbox would be a silent no-op,
            // so route to the link flow — linkEmail() turns the opt-in on, and the ON_RESUME
            // above reflects it when the user returns here.
            onLinkMailbox()
        }
    }

    val supporting = when {
        active -> "Fills one-time codes automatically, only while a login is waiting"
        needsMailbox -> "No mailbox linked — link one to read emailed codes again"
        else -> "Lets Auto Login read a linked inbox for one-time codes — only while a sign-in is waiting for one"
    }

    SettingRow(
        leading = Icons.Rounded.MailOutline,
        title = "Read codes from email",
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
            "On — sign-ins can complete while the app is closed"
        } else {
            "Android pauses idle apps — allow this so sign-ins keep working when the app is closed. Uses a little more battery."
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
 * Status notifications (SDK 33+ only, where the permission exists at all). The row is
 * ALWAYS shown on those versions and reports the live grant, like every other row in the
 * checklist — it deliberately does not remove itself once granted.
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

    // This row STAYS once the permission is granted. It used to remove itself, on the
    // reasoning that an always-on switch has no action behind it — which was wrong twice
    // over. It made a row vanish under the user's finger the instant they granted, which
    // reads as a bug rather than as success, and it left the setting with no home in the
    // app afterwards. It also broke the checklist's own consistency: the two rows above
    // stay put and report their state. BatteryToggle already solves this exact shape (the
    // OS owns revocation there too) by staying visible and handing OFF to system settings,
    // so this now behaves the same way.
    SettingRow(
        leading = Icons.Rounded.Notifications,
        title = "Show status notifications",
        supporting = when {
            hasPerm -> "On — you'll see a notice while Auto Login is running"
            promptExhausted -> "Blocked — turn notifications on for this app in system settings"
            else -> "Show a notice while Auto Login is running in the background"
        },
        trailing = RowTrailing.Switch(
            checked = hasPerm,
            onChange = { on ->
                if (on) {
                    if (promptExhausted) {
                        openNotificationSettings()
                    } else {
                        requestNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                } else {
                    // Android has no revoke API, so OFF hands off to system settings and
                    // leaves the switch wherever the system actually has it (re-derived on
                    // resume). Flipping it off locally would be a switch that lies.
                    openNotificationSettings()
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
    // Distinct from `failed`: the server offers no egress gateway at all, so the switch
    // can never succeed here and must not invite a retry.
    var unavailable by remember { mutableStateOf(false) }
    // The switch is the opt-in; `connected` is the go-core's LIVE handshake state, polled
    // so the status is truthful (mint can succeed while the tunnel never comes up — an
    // unreachable gateway or a handshake failure). Without this, the toggle read "On" even
    // when nothing was relaying.
    var connected by remember { mutableStateOf(RelayService.egressConnected()) }
    // Re-derive on resume — the same ON_RESUME truth-refresh HomeScreen uses for smsActive
    // (§4c). `active` was read ONCE at composition, so if RelayService reconciled egress OFF
    // behind the UI's back (it drops a permanently-rejected tunnel and clears the stored
    // flag in reconcileEgress) the switch would keep showing "Turning on…"/"On" over a
    // tunnel that is already gone. On resume: honour a permanent termination exactly as the
    // poll loop does, else re-read the persisted opt-in, then refresh the live handshake
    // state. Setting `active` here also relaunches LaunchedEffect(active) below, so its poll
    // loop follows the store rather than fighting it.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (RelayService.egressTerminated()) {
            store.unlinkEgress()
            RelayService.ensureRunning(ctx)
            active = false
            failed = true
        } else {
            active = store.isEgressEnabled()
        }
        connected = RelayService.egressConnected()
    }
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
        unavailable = false
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
        unavailable = false
        scope.launch {
            when (val mint = mintEgress(hub, store.deviceToken() ?: "", Build.MODEL ?: "device")) {
                is EgressMintResult.Ok -> {
                    store.linkEgress(mint.mint.token, mint.mint.gateway)
                    RelayService.ensureRunning(ctx) // reconciles egress ON
                    active = true
                }
                // No gateway on this server: permanent, so say so instead of inviting a
                // retry that can only fail the same way.
                EgressMintResult.Unavailable -> {
                    active = false
                    unavailable = true
                }
                EgressMintResult.Failed -> {
                    active = false
                    failed = true
                }
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
        unavailable = false
        confirming = true
    }

    val supporting = when {
        connected -> "On — sites you automate see this phone's IP"
        unavailable -> "Optional · not available on this server yet"
        failed -> "Turned off — the connection failed. Turn on to try again."
        active -> "Turning on… taking a while? Check this phone's internet connection"
        else -> "Optional · your agent browses from your home internet instead of a data centre, which fewer sites block"
    }

    SettingRow(
        leading = Icons.Rounded.SwapVert,
        title = "Use this phone's internet connection",
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
                // Consent copy. States the real tradeoff without overstating the risk: this
                // carries the user's OWN agent traffic over their own connection. It is not a
                // shared proxy pool and no bandwidth is resold, so the ISP clauses about
                // running a "server"/"proxy" or reselling access do not squarely apply, and
                // an account-suspension warning was disproportionate to what actually happens.
                Text(
                    // M3 AlertDialog CLIPS an over-long text slot instead of scrolling it,
                    // so at a large font scale the bottom of this disclosure simply vanished
                    // while "Turn on" stayed tappable. Consent the user cannot read is not
                    // consent, so the body scrolls.
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    text = "When on, Auto Login sends your own agent's web traffic through this phone's " +
                        "internet connection. Sites you automate will see this phone's IP address " +
                        "instead of the server's.\n\n" +
                        "Only your own sessions use it. Your connection is never shared with anyone " +
                        "else, pooled, or resold.\n\n" +
                        "It uses your home data, and some internet providers limit what consumer plans " +
                        "may be used for, so it's worth a look at your plan's terms. You can turn this " +
                        "off at any time.",
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
