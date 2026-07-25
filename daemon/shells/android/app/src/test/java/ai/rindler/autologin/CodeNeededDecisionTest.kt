package ai.rindler.autologin

import org.testng.Assert.assertEquals
import org.testng.Assert.assertFalse
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

/*
 * Testing strategy for the "code needed" prompt decisions.
 *
 * The feature: an authenticated code-expectation ping arms the reader. Each decision below is a
 * pure predicate over that moment:
 *
 * shouldPromptForSms(smsEnabled, hasPermission):
 *   decides IMMEDIATE prompt vs the 30s grace. TRUE (prompt at once) exactly when SMS auto-read
 *   CANNOT serve — there is nothing to wait for. FALSE (take the grace) only when it CAN, i.e.
 *   both the opt-in AND RECEIVE_SMS hold. Legal space is exactly 2x2, covered exhaustively.
 *
 * shouldNotifyCodeNeeded(stillExpecting):
 *   the post-grace check on the auto-read path — nudge IFF a code is still awaited after the
 *   grace (none auto-filled: an RCS code the app can't read, or a slow text).
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

    // ---- SMS: immediate-prompt-vs-grace, exhaustive 2x2 ---------------------------------

    /** (true, true) — opted in AND permitted: auto-read may serve, so take the grace (don't
     *  prompt up front). */
    @Test
    fun smsTakesGraceWhenFullyArmed() {
        assertFalse(shouldPromptForSms(smsEnabled = true, hasPermission = true))
    }

    /** (true, false) — opted in but RECEIVE_SMS denied: can't read, so prompt immediately. */
    @Test
    fun smsPromptsImmediatelyWhenPermissionDenied() {
        assertTrue(shouldPromptForSms(smsEnabled = true, hasPermission = false))
    }

    /** (false, true) — permitted but SMS read toggled OFF: nothing watches, so prompt at once. */
    @Test
    fun smsPromptsImmediatelyWhenOptedOut() {
        assertTrue(shouldPromptForSms(smsEnabled = false, hasPermission = true))
    }

    /** (false, false) — neither: prompt immediately. */
    @Test
    fun smsPromptsImmediatelyWhenNeither() {
        assertTrue(shouldPromptForSms(smsEnabled = false, hasPermission = false))
    }

    /**
     * The spec invariant: prompt-immediately IFF auto-read cannot serve, and it can serve only
     * when BOTH the opt-in and RECEIVE_SMS hold. Fails if the AND is ever weakened to an OR.
     */
    @Test
    fun smsPromptsImmediatelyExactlyWhenItCannotAutoRead() {
        for (enabled in listOf(true, false)) {
            for (permission in listOf(true, false)) {
                val canAutoRead = enabled && permission
                assertEquals(
                    shouldPromptForSms(enabled, permission),
                    !canAutoRead,
                    "immediate-prompt must be the negation of can-auto-read (enabled=$enabled, permission=$permission)",
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

    // ---- code-needed notification: fire at the 30s grace IFF a code is still awaited --------

    /*
     * The RCS/late-code rule: an OTP request arms the reader and opens a window; a notification
     * is fired only after a 30s grace, and only if the login is STILL awaiting a code then. If a
     * code auto-arrived within the grace it disarmed the window, so we stay silent; if the window
     * is still open (RCS the app can't read, or a slow text) we prompt. Uniform across every
     * device-relay OTP. The decision is the pure predicate; the 30s wait + the isExpecting read
     * are the impure timing around it.
     */

    /** covers (stillExpecting=true) — no code within the grace ⇒ prompt; and (false) — a code
     *  landed and closed the window ⇒ stay silent. Two cells kill a constant mutation. */
    @Test
    fun notifiesAfterGraceOnlyWhileStillExpecting() {
        assertTrue(shouldNotifyCodeNeeded(stillExpecting = true))
        assertFalse(shouldNotifyCodeNeeded(stillExpecting = false))
    }
}
