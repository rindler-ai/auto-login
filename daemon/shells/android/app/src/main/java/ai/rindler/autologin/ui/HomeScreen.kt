package ai.rindler.autologin.ui

import ai.rindler.autologin.BatteryExemption
import ai.rindler.autologin.BuildConfig
import ai.rindler.autologin.KeystoreSecretSource
import ai.rindler.autologin.RelayService
import ai.rindler.autologin.deriveConnectionStatus
import ai.rindler.autologin.fetchSupportedSites
import ai.rindler.autologin.normalizeSiteKey
import ai.rindler.autologin.sms.SmsAutoRead
import android.os.SystemClock
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    store: KeystoreSecretSource,
    onAddLogin: () -> Unit,
    onSettings: () -> Unit,
    onEnterCode: () -> Unit,
    onFinishSetup: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val warningInk = ai.rindler.autologin.ui.theme.LocalExtendedColors.current.warning
    // The relay's ACTUAL state, observed (RelayService.isRunning is snapshot state), and
    // the user's REQUESTED state. They differ only while a toggle is in flight: the relay
    // session is established asynchronously, so the switch answers immediately while the
    // status line keeps telling the truth until the session really lands.
    val running = RelayService.isRunning
    var requested by remember { mutableStateOf(running) }
    // Adopt the service's own state whenever it changes — this both resolves an in-flight
    // toggle and corrects the header when the relay comes up on its own (right after
    // pairing, or a START_STICKY restart).
    LaunchedEffect(running) { requested = running }
    val header = headerState(running, requested)
    // Bound the in-flight window (§4b). If a toggle never lands — the service early-returns
    // because the device predates the serverPubkey change, or Mobile.start throws — inFlight
    // would otherwise stay true forever, freezing the status on "Connecting…" and DISABLING
    // the switch, which is the only recovery control. When in flight, wait out the timeout;
    // if `running` still hasn't caught up, snap `requested` back to the truth so the switch
    // re-enables and the status stops lying. Keyed on (requested, running): a real connect
    // flips `running` in well under a second, which cancels + relaunches this effect with
    // inFlight == false, so the fast path never flashes a reset.
    LaunchedEffect(requested, running) {
        if (!header.inFlight) return@LaunchedEffect
        val startedAt = SystemClock.elapsedRealtime()
        while (!shouldForceReconcile(inFlight = true, elapsedMs = SystemClock.elapsedRealtime() - startedAt)) {
            delay(RELAY_INFLIGHT_POLL_MS)
        }
        requested = running
    }
    var sites by remember { mutableStateOf(store.sites()) }
    // Supported-site domains (normalized) from the live catalog, so a saved login for
    // a site the hub hasn't mapped yet is badged with a warning. Empty until loaded /
    // on a FAILED fetch (null), which just means no badges (fail-open, never a false
    // "Not supported yet" claimed off an outage).
    var supported by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(Unit) {
        supported = fetchSupportedSites(BuildConfig.CATALOG_URL)
            ?.map { normalizeSiteKey(it.domain) }
            ?.toSet()
            ?: emptySet()
    }
    // The quiet "Finish setting up" nudge. Only the two reliability steps count: SMS
    // auto-read must be BOTH opted in and granted to be active, and the Doze exemption is
    // the system's answer. Egress is deliberately excluded — declining the ISP tradeoff is
    // a legitimate final answer and must never nag. Both are OS-owned, so they are
    // re-derived on ON_RESUME rather than remembered (the user can change either behind
    // our back, including from the checklist we just sent them to).
    fun setupStepsLeft(): Int {
        val smsActive = store.isSmsAutoReadEnabled() && SmsAutoRead.hasPermission(ctx)
        return (if (smsActive) 0 else 1) + (if (BatteryExemption.isExempt(ctx)) 0 else 1)
    }
    var stepsLeft by remember { mutableStateOf(setupStepsLeft()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { stepsLeft = setupStepsLeft() }
    // SMS auto-read counts as ACTIVE only when opted in AND still granted. Re-derived on
    // resume, never remembered: Android can auto-revoke the grant from an idle app and the
    // user can revoke it in system Settings, and either must bring the manual-code row back
    // rather than leave them with no way to finish a sign-in.
    fun smsAutoReadActive(): Boolean = store.isSmsAutoReadEnabled() && SmsAutoRead.hasPermission(ctx)
    var smsActive by remember { mutableStateOf(smsAutoReadActive()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { smsActive = smsAutoReadActive() }
    // Shown only after the checklist has been seen (before that it is an interstitial, not
    // a nudge), never after "Don't remind me", and never once both steps are done.
    val showSetupNudge = store.isSetupSeen() && !store.isSetupNudgeDismissed() && stepsLeft > 0

    // Long-pressing a saved login opens a remove confirmation. We never show the stored
    // credential — the only management action is to delete it.
    var pendingDelete by remember { mutableStateOf<String?>(null) }

    AppScreen(
        topBar = { scrolled ->
            // AccountHeader IS the top bar (§2.3): the Switch replaces the old StatusCard's
            // pause/resume; the status line replaces its state text. Toggle is synchronous
            // today (no async in-flight state) → CONNECTED / PAUSED two-state mapping.
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
                onOpenSettings = onSettings,
                scrolled = scrolled,
            )
        },
        footer = true,
    ) {
        // Quiet, neutral, first — an unfinished-setup reminder, not a promotion: no
        // accent color, no badge, no card.
        if (showSetupNudge) {
            SettingRow(
                leading = Icons.Rounded.Checklist,
                title = "Finish setting up",
                supporting = if (stepsLeft == 1) "1 step left" else "$stepsLeft steps left",
                trailing = RowTrailing.Chevron,
                onClick = onFinishSetup,
            )
        }

        // Type a code by hand — the reliability floor. Hidden while SMS auto-read is
        // active, since codes fill themselves then and the row is just clutter; it
        // reappears the moment auto-read is switched off or the permission goes away.
        if (!smsActive) {
            SettingRow(
                leading = Icons.Rounded.Dialpad,
                title = "Enter a login code",
                supporting = "Type a code from a text or email if it wasn't filled in automatically",
                trailing = RowTrailing.Chevron,
                onClick = onEnterCode,
            )
        }

        SectionHeader("SAVED LOGINS", trailing = if (sites.isNotEmpty()) sites.size.toString() else null)

        if (sites.isEmpty()) {
            EmptyLogins(onAdd = onAddLogin)
        } else {
            // Full-bleed floating rows — NO dividers, NO card (divergence #2).
            sites.forEach { s ->
                val unsupported = supported.isNotEmpty() &&
                    !supported.contains(normalizeSiteKey(s))
                MediaRow(
                    title = s,
                    leading = { SiteLogo(s, size = 40, warning = unsupported) },
                    supporting = if (unsupported) "Not supported yet" else "Stored on this device",
                    // Warning ink on an unsupported (not-yet-mapped) site; else default.
                    supportingColor = if (unsupported) warningInk else null,
                    trailing = {
                        Icon(
                            Icons.Rounded.Lock,
                            contentDescription = null,
                            tint = cs.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    onLongClick = { pendingDelete = s },
                )
            }
            // De-tinted "Add a login" row adjacent to the list (accent budget → neutral).
            SettingRow(
                leading = Icons.Rounded.Add,
                title = "Add a login",
                onClick = onAddLogin,
            )
        }
        Spacer(Modifier.height(8.dp))
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
                }) { Text("Remove login", color = cs.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

/** Empty state — one action only (§3.4.6). Centered in the remaining space. */
@Composable
private fun EmptyLogins(onAdd: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(top = 56.dp, start = 16.dp, end = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconBadge(Icons.Outlined.Language, 56.dp)
        Spacer(Modifier.height(16.dp))
        Text("No logins yet", style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
        Spacer(Modifier.height(8.dp))
        Text(
            "Logins you save will appear here, encrypted on this phone. Our servers can't read them.",
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
        Spacer(Modifier.height(24.dp))
        PrimaryButton(
            text = "Add your first login",
            onClick = onAdd,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}
