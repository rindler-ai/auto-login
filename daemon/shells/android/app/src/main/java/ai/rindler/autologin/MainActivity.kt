// MainActivity — thin Compose host. The UI lives in ui/ (AutoLoginApp + screens);
// the relay itself lives in RelayService (foreground, always-on), NOT here, so it
// keeps running after the app is closed. On launch we request the notification +
// battery-optimization permissions the always-on service needs, and start it if
// the device is already paired.

package ai.rindler.autologin

import ai.rindler.autologin.ui.AutoLoginApp
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
        handleEnrollIntent(intent)
        enableEdgeToEdge()
        setContent {
            AutoLoginTheme {
                AutoLoginApp(
                    store = KeystoreSecretSource(applicationContext),
                    pendingEnroll = pendingEnroll,
                    onEnrollConsumed = { pendingEnroll = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleEnrollIntent(intent)
    }

    // Extract a sign-in enrollment token from a deep-link intent, if present.
    private fun handleEnrollIntent(intent: Intent?) {
        parseEnrollUri(intent?.data)?.let { pendingEnroll = it }
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
