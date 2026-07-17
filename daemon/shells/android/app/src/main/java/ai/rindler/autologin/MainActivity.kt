// MainActivity — thin Compose host. The UI lives in ui/ (AutoLoginApp + screens);
// the relay itself lives in RelayService (foreground, always-on), NOT here, so it
// keeps running after the app is closed. On launch we request the notification +
// battery-optimization permissions the always-on service needs, and start it if
// the device is already paired.

package ai.rindler.autologin

import ai.rindler.autologin.ui.AutoLoginApp
import ai.rindler.autologin.ui.theme.AutoLoginTheme
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

class MainActivity : FragmentActivity() {

    private val requestNotif =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best-effort */ }

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
        enableEdgeToEdge()
        setContent {
            AutoLoginTheme {
                AutoLoginApp(store = KeystoreSecretSource(applicationContext))
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ensureNotificationPermission()
        RelayService.ensureRunning(this) // no-op until paired
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** Offer to exempt the app from Doze so the always-on relay is not killed. */
    fun requestIgnoreBatteryOptimizations() {
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName"),
                    ),
                )
            }
        }
    }
}
