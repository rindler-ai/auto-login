// CodeNeededNotifier — the one-shot "a login needs a code and this device can't auto-fill
// it" notice.
//
// An authenticated code-expectation ping (sms_otp_code / email_otp_code) arms the reader,
// but if that channel CAN'T actually read — SMS auto-read is off or RECEIVE_SMS is denied,
// or email auto-read is off or no mailbox is linked — the login just stalls silently while
// the arm sits there reading nothing. This fires a notification so the user knows to act;
// tapping lands them on the manual code-entry screen (Dest.ManualCode) to type the code, or,
// for the email case, to link the correct mailbox.
//
// Best-effort, exactly like EmailHealthNotifier: notifications are a courtesy, not a
// guarantee. On SDK 33+ POST_NOTIFICATIONS may be denied — notify() is then a no-op, and the
// manual code screen (always reachable from Home) remains the real floor. Everything is
// runCatching-wrapped so a background sink can never crash on it. It NEVER carries the code
// or the site: the copy is generic, the PendingIntent only says "open manual entry".
//
// Distinct notification channel + id from EmailHealthNotifier (which owns "email_health" /
// 42), so the two notices coexist instead of replacing each other.

package ai.rindler.autologin

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * True IFF SMS auto-read CANNOT serve the waiting login, so the user must be prompted to
 * enter the code by hand. Auto-read needs BOTH the opt-in [smsEnabled] AND [hasPermission]
 * (RECEIVE_SMS); missing either means no text is ever read and the login would stall. Pure
 * so it is unit-tested without Android (CodeNeededDecisionTest).
 */
fun shouldPromptForSms(smsEnabled: Boolean, hasPermission: Boolean): Boolean =
    !(smsEnabled && hasPermission)

/**
 * True IFF email auto-read CANNOT serve the waiting login, so the user must be prompted (to
 * link the correct mailbox, or enter the code by hand). Serving needs the opt-in
 * [emailEnabled] AND at least one linked mailbox ([linkedCount] > 0); with auto-read off, or
 * the correct email not linked, no mailbox is polled and the login would stall. Pure so it is
 * unit-tested without Android (CodeNeededDecisionTest).
 */
fun shouldPromptForEmail(emailEnabled: Boolean, linkedCount: Int): Boolean =
    !(emailEnabled && linkedCount > 0)

object CodeNeededNotifier {
    private const val CHANNEL = "code_needed"
    private const val NOTIF_ID = 43 // distinct from EmailHealthNotifier's 42

    // Read by MainActivity to route a notification tap to the manual code screen. A plain
    // String extra (not a parcelable) so it survives the OS delivering the PendingIntent.
    const val EXTRA_NAV = "ai.rindler.autologin.extra.NAV"
    const val NAV_MANUAL_CODE = "manual_code"

    /** A login is waiting on a TEXTED code this device can't auto-read. */
    fun notifySmsCodeNeeded(app: Context) =
        notify(app, "Code needed", "A login needs a text code — tap to enter it.")

    /** A login is waiting on an EMAILED code this device can't auto-read (off, or not linked). */
    fun notifyEmailCodeNeeded(app: Context) =
        notify(
            app,
            "Code needed",
            "A login needs an emailed code — link your email, or tap to enter it.",
        )

    // One-shot, best-effort. Copy is passed in (static, never the code or site). The
    // PendingIntent opens MainActivity with EXTRA_NAV = manual_code, which lands the user on
    // Dest.ManualCode on a fresh launch.
    private fun notify(app: Context, title: String, text: String) {
        val ctx = app.applicationContext
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel(
                    CHANNEL,
                    "Sign-in codes to enter",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = "Tells you when a login needs a code this device can't fill in for you" }
                ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
            }
            val open = PendingIntent.getActivity(
                ctx,
                NOTIF_ID, // a distinct request code from EmailHealthNotifier so the two PendingIntents don't alias
                Intent(ctx, MainActivity::class.java).putExtra(EXTRA_NAV, NAV_MANUAL_CODE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notif = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(open)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            // notify() is a no-op if POST_NOTIFICATIONS is denied (SDK 33+); the manual code
            // screen stays reachable from Home, so we never need to check the grant first.
            NotificationManagerCompat.from(ctx).notify(NOTIF_ID, notif)
        }
    }
}
