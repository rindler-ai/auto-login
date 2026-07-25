package ai.rindler.autologin.ui

import org.testng.Assert.assertFalse
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

/*
 * Testing strategy for the pure code-channel decisions.
 *
 * emailAutoReadActive(optedIn, mailboxLinked):
 *   full 2x2 truth table — active IFF opted in AND a mailbox is linked (mirrors SMS's
 *   optedIn && hasPerm). The AND is the point: an opt-in with no mailbox, or a mailbox with
 *   the toggle off, is NOT active.
 *
 * manualEntryVisible(expecting):
 *   request-scoped — the manual-entry affordance is visible exactly while a device-relay OTP
 *   window is open, and hidden otherwise. Two cells (true/false) pin the identity and kill a
 *   constant mutation.
 */
class CodeChannelsTest {

    // --- emailAutoReadActive: active IFF opted in AND a mailbox is linked ---

    @Test
    fun emailActiveOnlyWhenOptedInAndLinked() {
        assertTrue(emailAutoReadActive(optedIn = true, mailboxLinked = true))
        // opted in but no inbox to read — NOT active (mutation: `optedIn` alone / an OR).
        assertFalse(emailAutoReadActive(optedIn = true, mailboxLinked = false))
        // a mailbox linked but the toggle explicitly off — NOT active (the toggle is the
        // control on top of linking); mutation: `mailboxLinked` alone / an OR.
        assertFalse(emailAutoReadActive(optedIn = false, mailboxLinked = true))
        assertFalse(emailAutoReadActive(optedIn = false, mailboxLinked = false))
    }

    // --- manualEntryVisible: request-scoped — shown ONLY while a relay OTP window is open ---

    /**
     * The manual-entry affordance is REQUEST-SCOPED: visible exactly while a device-relay OTP
     * window is open (SmsExpectation.isExpecting), hidden otherwise. This replaces the old
     * always-on floor (manualCodeRowVisible) — outside an active login there is nothing to
     * submit a code to (the rendezvous returns no_pending_login), so the row would be inert.
     * A mutation to a constant (always true / always false) is caught by the two cells.
     */
    @Test
    fun manualEntryVisibleTracksExpectingWindow() {
        assertTrue(manualEntryVisible(expecting = true))
        assertFalse(manualEntryVisible(expecting = false))
    }
}
