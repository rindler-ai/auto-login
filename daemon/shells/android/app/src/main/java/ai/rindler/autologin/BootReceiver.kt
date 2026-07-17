// BootReceiver — brings the relay back after a reboot or an app update, so the
// user never has to reopen the app to stay protected. No-op if not yet paired
// (RelayService.ensureRunning checks).

package ai.rindler.autologin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED ->
                RelayService.ensureRunning(context)
        }
    }
}
