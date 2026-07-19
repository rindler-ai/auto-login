package ai.rindler.autologin.ui

import org.testng.Assert.assertFalse
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

/*
 * Testing strategy for shouldForceReconcile(inFlight, elapsedMs):
 *   partition inFlight:   true (a toggle still disagreeing with reality), false (settled)
 *   partition elapsedMs vs RELAY_INFLIGHT_TIMEOUT_MS:
 *       0, below the timeout, EXACTLY at the timeout, above the timeout
 * The boundary at RELAY_INFLIGHT_TIMEOUT_MS is load-bearing on both sides:
 *   - a healthy connect lands in well under a second, so any elapsed below the timeout must
 *     NOT reconcile — reconciling early would flash the switch off mid-connect (the fast
 *     path the fix must not regress);
 *   - a toggle that can never land (the service early-returns, or Mobile.start throws) must
 *     reconcile the moment elapsed reaches the timeout, so the stuck "Connecting…" +
 *     disabled switch unsticks instead of freezing forever.
 * And a settled header (inFlight == false) must never reconcile, whatever the elapsed time.
 */
class HomeInFlightTimeoutTest {
    private val timeout = RELAY_INFLIGHT_TIMEOUT_MS

    /** A settled header never reconciles, at any elapsed time — there is nothing to undo. */
    @Test
    fun settledNeverReconciles() {
        for (elapsed in listOf(0L, timeout / 2, timeout, timeout * 10)) {
            assertFalse(
                shouldForceReconcile(inFlight = false, elapsedMs = elapsed),
                "a settled header must not reconcile (elapsed=$elapsed)",
            )
        }
    }

    /** The fast path: an in-flight toggle below the timeout is left alone, so a real
     *  sub-second connect never flashes a reset. */
    @Test
    fun inFlightBelowTimeoutIsLeftAlone() {
        for (elapsed in listOf(0L, 1L, timeout / 2, timeout - 1)) {
            assertFalse(
                shouldForceReconcile(inFlight = true, elapsedMs = elapsed),
                "must not reconcile before the timeout (elapsed=$elapsed)",
            )
        }
    }

    /**
     * The boundary is inclusive: the reset fires EXACTLY at the timeout, not a poll later.
     * This is the case a `>=`→`>` mutation breaks.
     */
    @Test
    fun inFlightAtTheTimeoutReconciles() {
        assertTrue(shouldForceReconcile(inFlight = true, elapsedMs = timeout))
    }

    /** Past the timeout, a stuck in-flight toggle is always reconciled. */
    @Test
    fun inFlightPastTheTimeoutReconciles() {
        for (elapsed in listOf(timeout + 1, timeout * 2, Long.MAX_VALUE)) {
            assertTrue(
                shouldForceReconcile(inFlight = true, elapsedMs = elapsed),
                "a stuck in-flight toggle past the timeout must reconcile (elapsed=$elapsed)",
            )
        }
    }
}
