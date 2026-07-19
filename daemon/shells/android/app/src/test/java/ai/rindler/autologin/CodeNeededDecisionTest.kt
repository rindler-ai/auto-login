package ai.rindler.autologin

import org.testng.Assert.assertEquals
import org.testng.Assert.assertFalse
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

/*
 * Testing strategy for the two "code needed" prompt decisions.
 *
 * The feature: an authenticated code-expectation ping arms the reader, but if the channel
 * CAN'T actually read, the login stalls silently. Each decision answers "must we prompt the
 * user to enter the code by hand?" — TRUE exactly when the channel cannot auto-read. The
 * oracles below come from that spec property (channel can serve IFF all of its conditions
 * hold), not from re-deriving the implementation, so they keep holding for any legal
 * reimplementation.
 *
 * shouldPromptForSms(smsEnabled, hasPermission):
 *   partition smsEnabled:    true, false
 *   partition hasPermission: true, false
 *   The legal space is exactly 2x2, covered EXHAUSTIVELY. SMS auto-read needs BOTH the
 *   opt-in AND RECEIVE_SMS, so the ONE cell that must NOT prompt is (true, true); the three
 *   cells missing either condition must prompt.
 *
 * shouldPromptForEmail(emailEnabled, linkedCount):
 *   partition emailEnabled: true, false
 *   partition linkedCount:  0 (no mailbox linked) vs > 0 (one or more) — the boundary at
 *                           0/1 is where the decision flips when auto-read is on, so it is
 *                           tested directly (0, 1, and a plural 2/5). Email can serve only
 *                           when auto-read is on AND at least one mailbox is linked, so
 *                           either "off" or "the correct email isn't linked" (count 0) must
 *                           prompt.
 */
class CodeNeededDecisionTest {

    // ---- SMS: exhaustive 2x2 -------------------------------------------------------------

    /** covers (true, true) — opted in AND permitted: auto-read serves, so NO prompt. */
    @Test
    fun smsDoesNotPromptWhenFullyArmed() {
        assertFalse(shouldPromptForSms(smsEnabled = true, hasPermission = true))
    }

    /** covers (true, false) — opted in but RECEIVE_SMS denied: can't read, so prompt. */
    @Test
    fun smsPromptsWhenPermissionDenied() {
        assertTrue(shouldPromptForSms(smsEnabled = true, hasPermission = false))
    }

    /** covers (false, true) — permitted but opted out: nothing watches, so prompt. */
    @Test
    fun smsPromptsWhenOptedOut() {
        assertTrue(shouldPromptForSms(smsEnabled = false, hasPermission = true))
    }

    /** covers (false, false) — neither: prompt. */
    @Test
    fun smsPromptsWhenNeither() {
        assertTrue(shouldPromptForSms(smsEnabled = false, hasPermission = false))
    }

    /**
     * The spec invariant across the whole 2x2: prompt IFF the channel cannot auto-read, and
     * SMS can auto-read only when BOTH conditions hold. Fails if the AND is ever weakened to
     * an OR (a single condition wrongly counted as "can read").
     */
    @Test
    fun smsPromptsExactlyWhenNotBothConditionsHold() {
        for (enabled in listOf(true, false)) {
            for (permission in listOf(true, false)) {
                val canAutoRead = enabled && permission
                assertEquals(
                    shouldPromptForSms(enabled, permission),
                    !canAutoRead,
                    "prompt must be the negation of can-auto-read (enabled=$enabled, permission=$permission)",
                )
            }
        }
    }

    // ---- Email: emailEnabled x linkedCount ----------------------------------------------

    /** covers (true, 1) — opted in AND one mailbox linked: can serve, so NO prompt. */
    @Test
    fun emailDoesNotPromptWhenEnabledAndLinked() {
        assertFalse(shouldPromptForEmail(emailEnabled = true, linkedCount = 1))
    }

    /** covers (true, 2/5) — plural mailboxes linked still serves, so NO prompt. */
    @Test
    fun emailDoesNotPromptWithMultipleMailboxes() {
        assertFalse(shouldPromptForEmail(emailEnabled = true, linkedCount = 2))
        assertFalse(shouldPromptForEmail(emailEnabled = true, linkedCount = 5))
    }

    /** covers (true, 0) — opted in but the correct email isn't linked: nothing to poll, prompt. */
    @Test
    fun emailPromptsWhenEnabledButNoMailbox() {
        assertTrue(shouldPromptForEmail(emailEnabled = true, linkedCount = 0))
    }

    /** covers (false, 0) and (false, 1+) — auto-read off can never serve, so prompt regardless. */
    @Test
    fun emailPromptsWheneverDisabled() {
        assertTrue(shouldPromptForEmail(emailEnabled = false, linkedCount = 0))
        assertTrue(shouldPromptForEmail(emailEnabled = false, linkedCount = 1))
        assertTrue(shouldPromptForEmail(emailEnabled = false, linkedCount = 3))
    }

    /**
     * The spec invariant: email can serve only when auto-read is on AND at least one mailbox
     * is linked (count > 0); prompt IFF it cannot. Sweeps both flags and the 0/1 boundary
     * plus a plural count. Fails if the linked-count boundary is moved off zero or the AND is
     * weakened.
     */
    @Test
    fun emailPromptsExactlyWhenItCannotServe() {
        for (enabled in listOf(true, false)) {
            for (count in listOf(0, 1, 2, 5)) {
                val canServe = enabled && count > 0
                assertEquals(
                    shouldPromptForEmail(enabled, count),
                    !canServe,
                    "prompt must be the negation of can-serve (enabled=$enabled, count=$count)",
                )
            }
        }
    }
}
