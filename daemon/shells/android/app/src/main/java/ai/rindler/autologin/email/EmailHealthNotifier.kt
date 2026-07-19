// EmailHealthNotifier — the one-shot "a linked mailbox stopped working" notice.
//
// Fired ONCE per breakage (MailboxReader calls this only on the healthy->broken transition,
// gated by KeystoreSecretSource.markEmailNeedsAttention returning true), because a code that
// never arrives is otherwise silent: the login just stalls. The notice tells the user WHICH
// mailbox to fix (a masked address) and taps through to the app to re-link it.
//
// Best-effort: notifications are a courtesy, not a guarantee. On SDK 33+ POST_NOTIFICATIONS
// may be denied — the durable warning badge on Home (emailNeedsAttention) is the real signal;
// this is the proactive nudge on top. Any failure is swallowed so a background poll can never
// crash on it.

package ai.rindler.autologin.email

import ai.rindler.autologin.MainActivity
import ai.rindler.autologin.R
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object EmailHealthNotifier {
    private const val CHANNEL = "email_health"
    private const val NOTIF_ID = 42

    fun notifyMailboxBroken(app: Context, address: String) {
        val ctx = app.applicationContext
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel(
                    CHANNEL,
                    "Email sign-in codes",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = "Tells you when a linked mailbox stops working" }
                ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
            }
            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notif = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle("Email sign-in codes stopped working")
                .setContentText("Auto Login can't open ${maskEmail(address)}. Tap to re-link it.")
                .setAutoCancel(true)
                .setContentIntent(open)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            // notify() is a no-op if POST_NOTIFICATIONS is denied (SDK 33+); the warning badge
            // remains the durable signal, so we never need to check the grant first.
            NotificationManagerCompat.from(ctx).notify(NOTIF_ID, notif)
        }
    }
}
