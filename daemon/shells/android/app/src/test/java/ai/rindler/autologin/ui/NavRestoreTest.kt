package ai.rindler.autologin.ui

import org.testng.Assert.assertEquals
import org.testng.Assert.assertNotEquals
import org.testng.annotations.Test

/*
 * Testing strategy for restoredDest(saved):
 *   partition saved: Dest.Completing (the uninterruptible in-flight pairing step),
 *                    every other Dest (the stable screens)
 * Covered exhaustively over the Dest enum. The property: a rotation / process-death
 * recreate keeps the user on the SAME screen (that is the whole point of saving nav state),
 * EXCEPT Completing — its pairing coroutine does not survive recreation, so restoring into
 * it would strand a dead spinner. It must land on Pair (where CompletingScreen's own retry
 * goes) instead of itself.
 */
class NavRestoreTest {

    /** covers saved == Completing — never restore into the uninterruptible step. */
    @Test
    fun completingRestoresToPairNotItself() {
        assertEquals(restoredDest(Dest.Completing), Dest.Pair)
        assertNotEquals(restoredDest(Dest.Completing), Dest.Completing)
    }

    /** covers every stable screen — restored unchanged, so rotation keeps your place. */
    @Test
    fun everyStableScreenRestoresUnchanged() {
        for (dest in Dest.entries.filter { it != Dest.Completing }) {
            assertEquals(restoredDest(dest), dest, "$dest must restore to itself")
        }
    }
}
