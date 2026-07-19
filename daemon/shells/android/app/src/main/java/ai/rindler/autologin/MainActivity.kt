// MainActivity — thin Compose host. The UI lives in ui/ (AutoLoginApp + screens);
// the relay itself lives in RelayService (foreground, always-on), NOT here, so it
// keeps running after the app is closed. On launch we request the notification +
// battery-optimization permissions the always-on service needs, and start it if
// the device is already paired.

package ai.rindler.autologin

import ai.rindler.autologin.ui.AutoLoginApp
import ai.rindler.autologin.ui.Dest
import ai.rindler.autologin.ui.EnrollRequest
import ai.rindler.autologin.ui.parseEnrollUri
import ai.rindler.autologin.ui.theme.AutoLoginTheme
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

class MainActivity : FragmentActivity() {

    private val requestNotif =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best-effort */ }

    // An autologin://paired sign-in deep link, delivered to this singleTask activity
    // via onCreate (cold) or onNewIntent (warm). AutoLoginApp observes it and
    // completes pairing. Never logged — it carries a pairing token.
    private var pendingEnroll by mutableStateOf<EnrollRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A credential-custody app must never leak a password / pairing code / typed
        // 2FA code into the OS Recents snapshot, a screenshot, or a screen recording.
        // FLAG_SECURE blocks all three app-wide — the posture password managers use.
        // (credential-safety review 2026-07-14, finding F2.)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        // Only a FRESH create may act on its launch intent. On a config-change or
        // process-death recreate, getIntent() still holds the ORIGINAL autologin://paired
        // deep link, whose single-use pairing token the first create already consumed —
        // re-parsing it replays a spent token and pops a bogus pairing failure / re-link
        // dialog seconds after a successful sign-in. A genuinely NEW warm link arrives via
        // onNewIntent instead, which is unaffected. (§4a)
        if (shouldHandleLaunchIntent(savedInstanceState == null)) {
            handleEnrollIntent(intent)
        }
        // A "code needed" notification tap carries EXTRA_NAV = manual_code. Honour it ONLY on
        // a fresh create (savedInstanceState == null): it is the INITIAL landing screen, not a
        // per-recreate override — a recreate must keep the user's restored screen (§4d), and
        // getIntent() still holds this extra after a recreate, so gating it here (like the
        // enroll deep link, §4a) keeps a rotation from yanking them back to manual entry.
        val initialDest: Dest? =
            if (savedInstanceState == null &&
                intent?.getStringExtra(CodeNeededNotifier.EXTRA_NAV) == CodeNeededNotifier.NAV_MANUAL_CODE
            ) {
                Dest.ManualCode
            } else {
                null
            }
        enableEdgeToEdge()
        setContent {
            AutoLoginTheme {
                AutoLoginApp(
                    store = KeystoreSecretSource(applicationContext),
                    pendingEnroll = pendingEnroll,
                    onEnrollConsumed = { pendingEnroll = null },
                    initialDest = initialDest,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleEnrollIntent(intent)
    }

    // Extract a sign-in enrollment token from a deep-link intent, if present, then CONSUME
    // it so it can never be read twice. Clearing the stored intent's data (and re-setting
    // it) means even a later process-death recreate that restored savedInstanceState finds
    // no link to replay: parseEnrollUri(null) == null. The pairing token is single-use, so
    // a second read only ever produces a failure. (§4a)
    private fun handleEnrollIntent(intent: Intent?) {
        intent ?: return
        parseEnrollUri(intent.data)?.let {
            pendingEnroll = it
            intent.data = null
            setIntent(intent)
        }
    }

    override fun onStart() {
        super.onStart()
        ensureNotificationPermission()
        RelayService.ensureRunning(this) // no-op until paired
    }

    // Gated on the setup checklist: before it has been seen, the FIRST notification ask
    // belongs to NotificationToggle, where the user has just read what notifications buy
    // them and flipped our switch. An unprimed cold-start prompt spends the one shot
    // Android gives us on a dialog with no context — deny it and the row can only deep-link
    // to system settings. After the checklist, this is the ordinary re-ask on launch.
    private fun ensureNotificationPermission() {
        if (!KeystoreSecretSource(applicationContext).isSetupSeen()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

// Whether THIS onCreate may act on its launch intent (getIntent()). Only a fresh create
// (savedInstanceState == null) may: a recreate re-delivers the original, already-consumed
// autologin://paired deep link via getIntent(), so re-handling it replays a spent pairing
// token. A warm link arrives through onNewIntent, which never routes through here. Pure so
// the guard is unit-tested (MainActivityIntentTest) without an Activity. (§4a)
internal fun shouldHandleLaunchIntent(isFreshCreate: Boolean): Boolean = isFreshCreate
