// EmailOtpDelivery — hands an on-device-extracted email code to the server rendezvous so a
// paused email-2FA login can continue. Email analog of sms/OtpDelivery.forward, except the
// code is ALREADY extracted on-device by Mobile.fetchEmailOTPOnce (Go core), so this only
// delivers + disarms.
//
// PRIVACY: only the extracted code leaves the phone (POST /devices/email-relay/manual, body
// exactly {"code":code}); no mailbox address, app-password, subject, sender, or body is ever
// sent or logged. The server delivers a code only if a login is actively waiting
// (no_pending_login otherwise), so a stray forward is inert.

package ai.rindler.autologin.email

import ai.rindler.autologin.BuildConfig
import ai.rindler.autologin.CodeSubmitResult
import ai.rindler.autologin.KeystoreSecretSource
import ai.rindler.autologin.submitEmailOtpCode
import android.content.Context
import kotlinx.coroutines.delay

object EmailOtpDelivery {

    /** Deliver [code] and, on success, disarm the [atGeneration] window. Returns true iff the
     *  code reached a waiting login (so MailboxReader can stop polling). */
    suspend fun deliver(app: Context, code: String, atGeneration: Long): Boolean {
        val ctx = app.applicationContext
        if (code.isEmpty()) return false
        val store = KeystoreSecretSource(ctx)
        val token = store.deviceToken() ?: return false
        // Deliver to the hub the device actually PAIRED against (store.hubUrl()), not the
        // build-time default — the device token and the waiting login only exist on that hub.
        val hub = store.hubUrl() ?: BuildConfig.HUB_URL
        val ok = deliverWithRetry(hub, token, code)
        // Close THIS window the moment the code lands with a waiting login (single-shot), so
        // no later cued mail this login triggers is inspected.
        if (ok) EmailExpectation.disarm(ctx, atGeneration)
        return ok
    }

    // A code can land a beat before the login registers its waiter; the rendezvous drops a
    // code with no waiter (no_pending_login), so retry briefly until it appears. ~11s of
    // coverage, comfortably inside the foreground process window. Byte-identical shape to
    // sms/OtpDelivery.deliverWithRetry.
    private suspend fun deliverWithRetry(hub: String, token: String, code: String): Boolean {
        val backoffs = longArrayOf(0, 3000, 4000, 4000)
        for (waitMs in backoffs) {
            if (waitMs > 0) delay(waitMs)
            when (submitEmailOtpCode(hub, token, code)) {
                CodeSubmitResult.DELIVERED -> return true
                CodeSubmitResult.UNAUTHORIZED -> return false
                CodeSubmitResult.NO_PENDING_LOGIN, CodeSubmitResult.FAILED -> continue
            }
        }
        return false
    }
}
