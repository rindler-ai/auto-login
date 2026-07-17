// OtpDelivery — the ONE place a consented message body becomes a code and reaches the
// server, so a paused 2FA login can continue hands-free.
//
// PRIVACY — the user's hard constraint, "extremely careful, only the codes":
//   1. Extraction runs ON THE DEVICE (Mobile.extractOTPCode, the same pure extractor
//      the server uses). A body that yields NO code — a normal text the user allowed by
//      mistake, a promo, an order update — returns "" and is dropped right here. It is
//      forwarded nowhere and logged nowhere.
//   2. Only the extracted digits ever leave the phone (POST /devices/sms-relay/manual).
//      The raw body never crosses the network and is never written to a log; neither is
//      the code or the device token.
//   3. The server delivers a code only if a login is actively waiting; a code with no
//      pending login is discarded server-side (no_pending_login), so even a stray
//      forward is inert.
//
// The caller is SmsReceiver, which reads an incoming text in the background once the
// user opted in and granted RECEIVE_SMS. The manual path (ManualCodeScreen) hits the
// same rendezvous with a typed code; from the paused login's view the two are identical.

package ai.rindler.autologin.sms

import ai.rindler.autologin.BuildConfig
import ai.rindler.autologin.CodeSubmitResult
import ai.rindler.autologin.KeystoreSecretSource
import ai.rindler.autologin.submitOtpCode
import ai.rindler.mobile.Mobile
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object OtpDelivery {

    // Process-scoped: the trampoline Activity finishes immediately, but the foreground
    // RelayService (which is what armed the listener in the first place) keeps the
    // process alive across the short retry window below.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Pull the code out of [body] ON DEVICE and forward ONLY the code. Everything else
     * about the message stops here. Consume-and-forget: nothing is logged.
     */
    fun forward(app: Context, body: String?) {
        val text = body?.trim().orEmpty()
        if (text.isEmpty()) return

        // No code -> not a 2FA text -> drop it; nothing is forwarded.
        val code = Mobile.extractOTPCode(text)
        if (code.isNullOrEmpty()) return

        val token = KeystoreSecretSource(app.applicationContext).deviceToken() ?: return
        // Capture the window generation now; the disarm below closes only THIS window, so
        // a second overlapping login that re-armed keeps its own.
        val gen = SmsExpectation.currentGeneration()
        scope.launch {
            // Close the expecting-code window the moment the code lands with a waiting
            // login, so no later text this login triggers is ever inspected (single-shot).
            if (deliverWithRetry(token, code)) SmsExpectation.disarm(app.applicationContext, gen)
        }
    }

    // A verification text can arrive a beat BEFORE the login registers its waiter:
    // the bank texts the code right after "Send code", while the worker is still a
    // step or two from reaching await_device_relay. The rendezvous drops a code that
    // has no waiter yet (no_pending_login), so retry briefly until the waiter appears.
    // Stops the instant the code is delivered; if the text was simply unrelated to a
    // an active login, every attempt is a harmless server-side no-op. ~11s of coverage
    // stays within the foreground process's comfortable window.
    // Returns true iff the code reached a waiting login (so the caller can close the
    // expecting-code window); false if the pairing is bad or the window ran out.
    private suspend fun deliverWithRetry(token: String, code: String): Boolean {
        val backoffs = longArrayOf(0, 3000, 4000, 4000)
        for (waitMs in backoffs) {
            if (waitMs > 0) delay(waitMs)
            when (submitOtpCode(BuildConfig.HUB_URL, token, code)) {
                // Delivered to a waiting login.
                CodeSubmitResult.DELIVERED -> return true
                // The pairing is bad and no retry will help.
                CodeSubmitResult.UNAUTHORIZED -> return false
                // Waiter not up yet (the race) OR a transient blip — a Doze-woken
                // radio failing the immediate first POST, a 5xx mid rolling-deploy.
                // Both deserve the remaining retries within the ~11s window; a code
                // the server already consumed just yields no_pending_login next time.
                CodeSubmitResult.NO_PENDING_LOGIN, CodeSubmitResult.FAILED -> continue
            }
        }
        return false
    }
}
