// SmsCodeExpectationSink bridges the Go core's authenticated sms_otp_code ping to
// the device-side expecting-code window (SmsExpectation). It is handed to
// Mobile.start; the Go core calls onExpectingSMSCode ONLY after it has verified the
// ping's signature and cleared the replay guard, so an arriving call means a login
// is genuinely, right now, awaiting a texted code for ttlSeconds. That is the only
// thing that ever opens the window SmsReceiver reads — the app never arms itself
// from anything it merely receives unverified.

package ai.rindler.autologin.sms

import ai.rindler.autologin.email.EmailCodeExpectationSink
import ai.rindler.mobile.CodeExpectationSink
import android.content.Context

class SmsCodeExpectationSink(appContext: Context) : CodeExpectationSink {
    private val app = appContext.applicationContext

    // gomobile binds the Go `int` ttlSeconds to a Java long.
    override fun onExpectingSMSCode(site: String, ttlSeconds: Long) {
        SmsExpectation.arm(app, ttlSeconds)
    }

    // The regenerated CodeExpectationSink also requires this method (the Go core added
    // OnExpectingEmailCode). Mobile.start still takes ONE sink, so this single class
    // implements BOTH lanes. Email delegates to EmailCodeExpectationSink: arm
    // EmailExpectation + kick the active MailboxReader poll (gated on opt-in + a linked
    // mailbox). The SMS path above stays byte-identical.
    override fun onExpectingEmailCode(site: String, ttlSeconds: Long) {
        EmailCodeExpectationSink.onExpectingEmailCode(app, site, ttlSeconds)
    }
}
