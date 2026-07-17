// SmsReceiver — reads an incoming verification text in the BACKGROUND and hands only
// its code to the paused login, so a login never stalls waiting for a 2FA code.
//
// It is manifest-declared for SMS_RECEIVED, so the OS delivers a text even while the
// app is closed — the point of the daemon model: hold RECEIVE_SMS (granted once, at the
// Settings toggle) and read silently thereafter, no per-message prompt. This replaces
// the SMS User Consent API, which prompted per text and needed a foreground
// Activity, so it could never work while the app was backgrounded.
//
// PRIVACY — the user's hard constraint, "extremely careful, only the codes":
//   1. Inert unless the user opted in (isSmsAutoReadEnabled). RECEIVE_SMS is requested
//      ONLY when they flip the toggle ON; deny -> toggle stays off -> no broadcast, and
//      even a granted-but-toggled-off state returns here immediately.
//   2. Every text is handed straight to OtpDelivery.forward, which extracts the code ON
//      DEVICE (Mobile.extractOTPCode) and DROPS any body that yields no code. Only the
//      extracted digits ever leave the phone; the raw body is never sent or logged.
//   3. The receiver is BROADCAST_SMS-guarded (manifest), so only the OS can deliver to it.

package ai.rindler.autologin.sms

import ai.rindler.autologin.KeystoreSecretSource
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val app = context.applicationContext

        // Opted out (or not yet opted in) -> never touch a text. The receiver is always
        // registered; this flag is what gates it, exactly matching the toggle state.
        if (!KeystoreSecretSource(app).isSmsAutoReadEnabled()) return

        // MINIMAL EXPOSURE — the load-bearing privacy gate. Inspect a text ONLY while a
        // login is actively awaiting a code (a window opened by the AUTHENTICATED
        // sms_otp_code ping, see SmsExpectation). Outside it we return here WITHOUT ever
        // reading the message body, so the app is exposed to the user's SMS solely during
        // a real login that asked for a code — never the general stream.
        if (!SmsExpectation.isExpecting(app)) return

        // One text can arrive as several PDUs (a long code + message body split across
        // parts). Concatenate the parts of the delivered message so a split body still
        // yields its code; extraction downstream ignores everything that is not a code.
        val body = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            ?.joinToString(separator = "") { it.displayMessageBody.orEmpty() }
            ?.trim()
            .orEmpty()
        if (body.isEmpty()) return

        // Same path as the manual screen: the code is pulled out on-device (a text that
        // yields no code is dropped), the body never sent or logged. The window was armed
        // by a ping over the running RelayService's hub, so the foreground service is
        // alive to keep the process up across OtpDelivery's short retry; OtpDelivery
        // closes the window the moment a code is actually delivered.
        OtpDelivery.forward(app, body)
    }
}
