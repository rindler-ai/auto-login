package ai.rindler.autologin.ui

import ai.rindler.autologin.ConnectionStatus
import org.testng.Assert.assertEquals
import org.testng.Assert.assertFalse
import org.testng.Assert.assertNotEquals
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

/*
 * Testing strategy for headerState(running, requested):
 *   partition running:   true, false
 *   partition requested: true, false
 * The legal input space is exactly 2x2, so the partition is covered EXHAUSTIVELY rather
 * than by representatives. The two interesting cells are the ones where the inputs
 * disagree: a toggle the user just flipped, and the window right after pairing where the
 * service has started but its session has not landed yet.
 *
 * Oracles come from the spec's guaranteed properties, not from re-deriving the
 * implementation: the two invariants below are asserted across the WHOLE space, so they
 * keep holding for any legal reimplementation of the status mapping.
 */
class HeaderStateTest {
    private val space = listOf(true to true, true to false, false to true, false to false)

    /** covers running=true, requested=true — steady state, relay up and wanted up. */
    @Test
    fun connectedWhenRunningAndRequested() {
        val s = headerState(running = true, requested = true)
        assertEquals(s.status, ConnectionStatus.CONNECTED)
        assertTrue(s.switchOn)
        assertFalse(s.inFlight)
    }

    /** covers running=false, requested=false — steady state, relay down and wanted down. */
    @Test
    fun pausedWhenNotRunningAndNotRequested() {
        val s = headerState(running = false, requested = false)
        assertEquals(s.status, ConnectionStatus.PAUSED)
        assertFalse(s.switchOn)
        assertFalse(s.inFlight)
    }

    /**
     * covers running=false, requested=true — the user just switched the relay ON, or the
     * app just paired: the service is starting but the session has not landed. The switch
     * must already read ON so the control does not feel dead, while the status line must
     * NOT yet claim Connected.
     */
    @Test
    fun connectingWhileTurningOn() {
        val s = headerState(running = false, requested = true)
        assertEquals(s.status, ConnectionStatus.CONNECTING)
        assertTrue(s.switchOn)
        assertTrue(s.inFlight)
    }

    /** covers running=true, requested=false — the user just switched it OFF; still tearing down. */
    @Test
    fun connectingWhileTurningOff() {
        val s = headerState(running = true, requested = false)
        assertEquals(s.status, ConnectionStatus.CONNECTING)
        assertFalse(s.switchOn)
        assertTrue(s.inFlight)
    }

    /**
     * covers bug: header read "Paused" while the relay was genuinely running.
     *
     * The status line is a statement about the ACTUAL session, so across the entire input
     * space it may never say PAUSED while the relay is up — whatever the user last
     * requested. This is the assertion that fails if the status is ever derived from a
     * stale or requested-only value again.
     */
    @Test
    fun neverReportsPausedWhileRunning() {
        for ((_, requested) in space.filter { it.first }) {
            val s = headerState(running = true, requested = requested)
            assertNotEquals(
                s.status,
                ConnectionStatus.PAUSED,
                "relay is running, so the header must not say Paused (requested=$requested)",
            )
        }
    }

    /** The dual: never claim Connected while the relay is genuinely down. */
    @Test
    fun neverReportsConnectedWhileNotRunning() {
        for ((_, requested) in space.filter { !it.first }) {
            val s = headerState(running = false, requested = requested)
            assertNotEquals(
                s.status,
                ConnectionStatus.CONNECTED,
                "relay is down, so the header must not say Connected (requested=$requested)",
            )
        }
    }

    /** The switch tracks the REQUEST, so a tap is always reflected immediately. */
    @Test
    fun switchAlwaysMirrorsTheRequest() {
        for ((running, requested) in space) {
            assertEquals(headerState(running, requested).switchOn, requested)
        }
    }

    /** inFlight is exactly the disagreement between wanted and actual. */
    @Test
    fun inFlightIffStatesDisagree() {
        for ((running, requested) in space) {
            assertEquals(headerState(running, requested).inFlight, running != requested)
        }
    }
}
