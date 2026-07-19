// BatteryExemption — the Doze exemption the always-on relay depends on.
//
// The relay holds a persistent socket so the server can ping this device the moment a
// login needs a credential. Doze / App Standby suspend that socket, so an unexempted app
// stops answering while it is closed. Android lets an app ASK for the exemption but never
// REVOKE it programmatically — revoking is system-only — so the UI has two different
// intents: requestExemption() for ON, openRevokeSettings() for OFF. A switch that
// pretended to revoke would be a switch that lies (the WARP a11y bug we keep citing).
//
// State is never cached: isExempt() is re-read on every ON_RESUME, because the user can
// change it out from under us in system Settings.

package ai.rindler.autologin

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

object BatteryExemption {

    /// True when this package is already exempt from battery optimizations, i.e. the relay
    /// socket survives Doze. Read live from PowerManager — never remembered across resumes.
    fun isExempt(ctx: Context): Boolean {
        val pm = ctx.getSystemService(PowerManager::class.java) ?: return false
        return runCatching { pm.isIgnoringBatteryOptimizations(ctx.packageName) }.getOrDefault(false)
    }

    /// Ask the system for the exemption. Shows the OS's own allow/deny dialog for this
    /// package; the result arrives as a state change we re-derive on resume, not a callback.
    /// Fired only from an explicit user switch-on, never silently.
    fun requestExemption(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${ctx.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /// Open the system's battery-optimization list so the user can REVOKE the exemption.
    /// There is no API to drop it ourselves, so turning the switch off hands off to the OS
    /// instead of flipping a control that would not reflect reality.
    fun openRevokeSettings(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
