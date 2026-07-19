package ai.rindler.autologin

import ai.rindler.autologin.ui.parseEnrollUri
import org.testng.Assert.assertFalse
import org.testng.Assert.assertNull
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

/*
 * Testing strategy for shouldHandleLaunchIntent(isFreshCreate):
 *   partition isFreshCreate: true  (a cold launch, savedInstanceState == null),
 *                            false (a config-change / process-death recreate)
 * The 1-boolean space is covered exhaustively. The property this pins: a recreate must
 * NOT re-handle its launch intent. getIntent() still holds the ORIGINAL autologin://paired
 * deep link after a recreate, and its pairing token is single-use — the first create
 * already spent it — so re-parsing it can only produce a bogus pairing failure / re-link
 * dialog seconds after a successful sign-in. A genuinely new warm link never routes through
 * this guard (it arrives via onNewIntent).
 *
 * Plus the consume half of §4a: once a deep link's data has been turned into a
 * pendingEnroll, MainActivity clears the stored intent's data (intent.data = null). The
 * invariant that makes that safe is parseEnrollUri(null) == null — a data-cleared intent
 * yields no enrollment, so even a process-death recreate that restored savedInstanceState
 * finds nothing to replay.
 */
class MainActivityIntentTest {

    /** covers isFreshCreate = true — a cold launch acts on its deep link exactly once. */
    @Test
    fun freshCreateHandlesItsLaunchIntent() {
        assertTrue(shouldHandleLaunchIntent(isFreshCreate = true))
    }

    /**
     * covers isFreshCreate = false — the replay bug. A recreate must decline its launch
     * intent; this is the assertion that fails if the savedInstanceState == null guard is
     * ever dropped and rotation starts replaying the spent pairing token again.
     */
    @Test
    fun aRecreateDoesNotReplayTheLaunchIntent() {
        assertFalse(shouldHandleLaunchIntent(isFreshCreate = false))
    }

    /** A consumed (data-cleared) intent parses to no enrollment — nothing left to replay. */
    @Test
    fun aDataClearedIntentYieldsNoEnrollment() {
        assertNull(parseEnrollUri(null))
    }
}
