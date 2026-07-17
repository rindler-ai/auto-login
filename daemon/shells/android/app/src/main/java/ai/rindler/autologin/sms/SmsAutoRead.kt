// SmsAutoRead — the small helper that answers "is auto-read live?" for the rest of the
// app. Auto-read has two conditions, and BOTH must hold: the user opted in
// (KeystoreSecretSource.isSmsAutoReadEnabled) AND the app holds RECEIVE_SMS.
//
// There is nothing to "arm": reading is a manifest-declared SmsReceiver that the OS
// delivers SMS_RECEIVED to (even while the app is closed), gated inside by the opt-in
// flag. The permission is requested ONCE, at the Settings toggle, while an Activity is
// foreground — never from the background. Opted out (or permission denied) means the
// receiver returns immediately and the app falls back to manual entry (ManualCodeScreen).

package ai.rindler.autologin.sms

import ai.rindler.autologin.KeystoreSecretSource
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

object SmsAutoRead {

    /** True when the user has flipped the Settings toggle on. */
    fun isEnabled(ctx: Context): Boolean =
        KeystoreSecretSource(ctx.applicationContext).isSmsAutoReadEnabled()

    /** True when RECEIVE_SMS is granted (so SmsReceiver will actually get a broadcast). */
    fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECEIVE_SMS) ==
            PackageManager.PERMISSION_GRANTED

    /** Auto-read genuinely reads a text only when opted in AND permitted. */
    fun isActive(ctx: Context): Boolean = isEnabled(ctx) && hasPermission(ctx)
}
