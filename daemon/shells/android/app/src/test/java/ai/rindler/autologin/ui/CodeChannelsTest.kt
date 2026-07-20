package ai.rindler.autologin.ui

import org.testng.Assert.assertEquals
import org.testng.Assert.assertFalse
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

/*
 * Testing strategy for the two pure code-channel decisions.
 *
 * emailAutoReadActive(optedIn, mailboxLinked):
 *   full 2x2 truth table — active IFF opted in AND a mailbox is linked (mirrors SMS's
 *   optedIn && hasPerm). The AND is the point: an opt-in with no mailbox, or a mailbox with
 *   the toggle off, is NOT active.
 *
 * manualCodeRowVisible(smsActive, emailActive):
 *   full 2x2 truth table — the manual "Enter a login code" row is hidden ONLY when BOTH
 *   channels are active, and shown whenever EITHER is inactive (the reliability floor: any
 *   code that can't auto-fill still needs a manual path). i.e. !(sms && email).
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

    // --- manualCodeRowVisible: hidden ONLY when BOTH channels are active ---

    /** Both channels active ⇒ codes auto-fill ⇒ the manual row is clutter, hidden. This is
     *  the single cell that changed when email was added to the old `!smsActive` rule; a
     *  mutation dropping the email term (back to `!smsActive`) would wrongly HIDE the row
     *  in the sms-active-but-email-inactive case below. */
    @Test
    fun hiddenOnlyWhenBothActive() {
        assertFalse(manualCodeRowVisible(smsActive = true, emailActive = true))
    }

    /** SMS active but email inactive ⇒ an emailed code can't auto-fill ⇒ show the row. This
     *  case is exactly what the old `!smsActive` rule got wrong. */
    @Test
    fun shownWhenSmsActiveButEmailInactive() {
        assertTrue(manualCodeRowVisible(smsActive = true, emailActive = false))
    }

    /** Email active but SMS inactive ⇒ an SMS code can't auto-fill ⇒ show the row. */
    @Test
    fun shownWhenEmailActiveButSmsInactive() {
        assertTrue(manualCodeRowVisible(smsActive = false, emailActive = true))
    }

    /** Neither active ⇒ nothing auto-fills ⇒ show the row (the reliability floor). */
    @Test
    fun shownWhenNeitherActive() {
        assertTrue(manualCodeRowVisible(smsActive = false, emailActive = false))
    }

    /** The rule is exactly !(sms && email) across the whole 2x2 — the AND-not form, not an
     *  OR-not (`!(sms || email)`) which would only hide when neither is active. */
    @Test
    fun visibilityIsNotAndAcrossTheWholeSpace() {
        for (sms in listOf(false, true)) for (email in listOf(false, true)) {
            assertEquals(
                manualCodeRowVisible(sms, email),
                !(sms && email),
                "manual-row visibility must be !(sms && email) (sms=$sms email=$email)",
            )
        }
    }
}
