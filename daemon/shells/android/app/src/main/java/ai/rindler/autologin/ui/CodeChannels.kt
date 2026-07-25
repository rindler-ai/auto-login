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
 * The REQUEST-SCOPED manual-entry affordance: visible exactly while a device-relay OTP window
 * is open ([expecting] == SmsExpectation.isExpecting), hidden otherwise. Unlike an always-on
 * floor, this appears only when a login is actively awaiting a code —
 * outside that window a typed code has no waiting login (the rendezvous replies no_pending_login),
 * so the affordance would be inert. It shows the instant a relay OTP is requested (so a code that
 * can't auto-fill — RCS the app can't read, or SMS auto-read that missed — can still be entered)
 * and disappears when the code is received, is typed, or the window expires.
 */
internal fun manualEntryVisible(expecting: Boolean): Boolean = expecting
