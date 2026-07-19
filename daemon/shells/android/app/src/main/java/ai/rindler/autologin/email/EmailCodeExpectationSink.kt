// EmailCodeExpectationSink bridges the Go core's authenticated email_otp_code ping to the
// device-side expecting-code window (EmailExpectation) + the active MailboxReader poll. It is
// invoked from SmsCodeExpectationSink.onExpectingEmailCode (the single sink handed to
// Mobile.start), which the Go core calls ONLY after verifying the ping's signature and
// clearing the replay guard — so an arriving call means a login is genuinely, right now,
// awaiting an emailed code for ttlSeconds.
//
// Fail-closed: arm + poll ONLY if the user opted in AND a mailbox is linked. This is the sole
// starter of MailboxReader, so a poll only ever begins from a live verified ping this
// process-life — never from a restored persisted deadline.

package ai.rindler.autologin.email

import ai.rindler.autologin.CodeNeededNotifier
import ai.rindler.autologin.KeystoreSecretSource
import ai.rindler.autologin.shouldPromptForEmail
import android.content.Context

object EmailCodeExpectationSink {

    fun onExpectingEmailCode(app: Context, site: String, ttlSeconds: Long) {
        val ctx = app.applicationContext
        val store = KeystoreSecretSource(ctx)
        // Whether email auto-read CAN serve: opted in AND at least one mailbox linked. Read
        // HERE (Android) and reduced to the pure shouldPromptForEmail decision so the
        // prompt-or-not is unit-tested. (linkedEmails().isNotEmpty() == isEmailLinked().)
        val enabled = store.isEmailAutoReadEnabled()
        val linkedCount = store.linkedEmails().size
        if (shouldPromptForEmail(enabled, linkedCount)) {
            // Can't serve — auto-read off, or the correct email isn't linked. The armed
            // reader (below) would poll nothing, so the login stalls silently: nudge the
            // user to link their email or enter the code by hand. Then fail closed: never
            // open a mailbox.
            CodeNeededNotifier.notifyEmailCodeNeeded(ctx)
            return
        }
        val generation = EmailExpectation.arm(ctx, ttlSeconds)
        MailboxReader.kick(ctx, site, generation)
    }
}
