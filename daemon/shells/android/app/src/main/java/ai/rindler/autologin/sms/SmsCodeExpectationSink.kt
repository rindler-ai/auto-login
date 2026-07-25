// SmsCodeExpectationSink bridges the Go core's authenticated sms_otp_code ping to
// the device-side expecting-code window (SmsExpectation). It is handed to
// Mobile.start; the Go core calls onExpectingSMSCode ONLY after it has verified the
// ping's signature and cleared the replay guard, so an arriving call means a login
// is genuinely, right now, awaiting a texted code for ttlSeconds. That is the only
// thing that ever opens the window SmsReceiver reads — the app never arms itself
// from anything it merely receives unverified.

package ai.rindler.autologin.sms

import ai.rindler.autologin.CodeNeededNotifier
import ai.rindler.autologin.KeystoreSecretSource
import ai.rindler.autologin.email.EmailCodeExpectationSink
import ai.rindler.autologin.shouldNotifyCodeNeeded
import ai.rindler.autologin.shouldPromptForSms
import ai.rindler.mobile.CodeExpectationSink
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SmsCodeExpectationSink(appContext: Context) : CodeExpectationSink {
    private val app = appContext.applicationContext

    // gomobile binds the Go `int` ttlSeconds to a Java long.
    override fun onExpectingSMSCode(site: String, ttlSeconds: Long) {
        // Always arm — a live, replay-checked login asked for a code, and the window is
        // harmless when nothing reads it. The manual-entry affordance on Home is scoped to
        // this window, so it appears the instant the request lands.
        SmsExpectation.arm(app, ttlSeconds)
        val enabled = KeystoreSecretSource(app).isSmsAutoReadEnabled()
        val hasPermission = SmsAutoRead.hasPermission(app)
        if (shouldPromptForSms(enabled, hasPermission)) {
            // SMS auto-read CAN'T serve (turned off or RECEIVE_SMS denied), so nothing will ever
            // auto-fill this code — prompt IMMEDIATELY instead of making the user sit through a
            // 30s grace whose only purpose is to give auto-read a chance. (The grace exists to
            // cover the case where the config DOES deliver over readable SMS and it just needs a
            // moment; with auto-read unavailable there is nothing to wait for.)
            CodeNeededNotifier.notifySmsCodeNeeded(app)
        } else {
            // Auto-read MIGHT fill it — give it the grace, then nudge only if the window is STILL
            // open (no code arrived: RCS the app can't read, or a slow text). A code that lands
            // within the grace disarms the window (OtpDelivery) so we stay silent; one that lands
            // just after still auto-continues the login and cancels this notice.
            scope.launch {
                delay(CODE_GRACE_MS)
                if (shouldNotifyCodeNeeded(SmsExpectation.isExpecting(app))) {
                    CodeNeededNotifier.notifySmsCodeNeeded(app)
                }
            }
        }
    }

    // The regenerated CodeExpectationSink also requires this method (the Go core added
    // OnExpectingEmailCode). Mobile.start still takes ONE sink, so this single class
    // implements BOTH lanes. Email delegates to EmailCodeExpectationSink: arm
    // EmailExpectation + kick the active MailboxReader poll (gated on opt-in + a linked
    // mailbox).
    override fun onExpectingEmailCode(site: String, ttlSeconds: Long) {
        EmailCodeExpectationSink.onExpectingEmailCode(app, site, ttlSeconds)
    }

    private companion object {
        // The grace before we nudge for manual entry. Long enough for a readable SMS to
        // auto-fill and disarm the window (so we don't nag a normal text login), short enough
        // that a code we can't read (RCS) still leaves the user most of the ~5-minute code
        // lifetime to type it by hand.
        const val CODE_GRACE_MS = 30_000L

        // Process-scoped: the foreground RelayService that armed the window keeps the process
        // alive across the grace delay below (same rationale as OtpDelivery's retry scope).
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
