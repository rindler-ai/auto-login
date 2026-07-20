// CodeChannels — the pure visibility/active decisions for the two one-time-code channels
// (SMS + email), extracted so the rules can be unit-tested without Compose or a device.
//
// A channel is "active" only when the user opted in AND its delivery precondition holds:
//   - SMS:   the RECEIVE_SMS grant  (SmsAutoRead.hasPermission)
//   - email: at least one linked mailbox (KeystoreSecretSource.isEmailLinked)
// so a switch — or a decision derived from one — can never claim a channel is on while
// nothing could actually read a code.

package ai.rindler.autologin.ui

/**
 * Email auto-read is active only when the user opted in AND a mailbox is linked (email's
 * analog of the SMS permission grant). Mirrors the SMS `optedIn && hasPerm` shape.
 */
internal fun emailAutoReadActive(optedIn: Boolean, mailboxLinked: Boolean): Boolean =
    optedIn && mailboxLinked

/**
 * The manual "Enter a login code" row is the reliability floor: shown whenever EITHER code
 * channel can't auto-read, hidden ONLY when BOTH SMS and email auto-read are active (codes
 * fill themselves then, so the row would be clutter). i.e. `!(smsActive && emailActive)`.
 */
internal fun manualCodeRowVisible(smsActive: Boolean, emailActive: Boolean): Boolean =
    !(smsActive && emailActive)
