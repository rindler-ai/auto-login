package ai.rindler.autologin.ui

import org.testng.Assert.assertEquals
import org.testng.Assert.assertFalse
import org.testng.Assert.assertNotEquals
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

/*
 * Testing strategy for enrollStateAccepted(supplied, stored, storedTsMs, nowMs, ttlMs):
 * this is the anti-fixation nonce gate — a deep-link autologin://paired is accepted ONLY
 * when its `state` matches the still-unexpired nonce openSignInEnroll minted on THIS phone.
 *
 *   partition stored:    null / blank (no sign-in pending on this phone), a real nonce
 *   partition supplied:  null / blank (a drive-by link with no state), matches, mismatches
 *   partition age (nowMs - storedTsMs) vs ttlMs:
 *                        below the TTL, EXACTLY at the TTL, just past it
 *
 * The single security property: a drive-by page never holds the 128-bit nonce, so the ONLY
 * accepting cell is (a real pending nonce) AND (supplied == stored) AND (unexpired). Every
 * other cell rejects. The TTL boundary is inclusive of the TTL (age == ttl still accepts);
 * age == ttl + 1 rejects.
 */
class EnrollStateGateTest {
    private val nonce = "0123456789abcdef0123456789abcdef" // a representative 128-bit hex nonce
    private val ttl = ENROLL_STATE_TTL_MS
    private val t0 = 1_000_000_000_000L // an arbitrary mint time

    /** The ONLY accepting case: a real pending nonce, an exact match, well within the TTL. */
    @Test
    fun matchingUnexpiredNonceIsAccepted() {
        assertTrue(enrollStateAccepted(nonce, nonce, t0, t0 + ttl / 2, ttl))
    }

    /** No pending nonce (nothing was started on this phone) ⇒ reject, even if the link
     *  carries a plausible-looking state. Mutation killed: dropping the `stored` null-guard. */
    @Test
    fun noPendingNonceIsRejected() {
        assertFalse(enrollStateAccepted(nonce, null, 0L, t0, ttl))
        assertFalse(enrollStateAccepted(nonce, "", t0, t0, ttl))
    }

    /** A drive-by link with no state at all ⇒ reject. Mutation killed: dropping the
     *  `supplied` null-guard would wrongly accept a stateless link when stored is null too,
     *  or (worse) treat null==null as a match. */
    @Test
    fun aLinkWithNoStateIsRejected() {
        assertFalse(enrollStateAccepted(null, nonce, t0, t0, ttl))
        assertFalse(enrollStateAccepted("", nonce, t0, t0, ttl))
        // both null — the "== compares two nulls to true" trap
        assertFalse(enrollStateAccepted(null, null, t0, t0, ttl))
    }

    /** A wrong nonce ⇒ reject (an attacker guessed / reused a stale one). Mutation killed:
     *  replacing `supplied == stored` with `true`. */
    @Test
    fun aMismatchedNonceIsRejected() {
        assertFalse(enrollStateAccepted("ffffffffffffffffffffffffffffffff", nonce, t0, t0, ttl))
    }

    /** The TTL boundary is INCLUSIVE: age exactly == ttl still accepts (a slow-but-legit
     *  sign-in), age == ttl + 1 rejects. Mutation killed: `>` ⇄ `>=` on the expiry check. */
    @Test
    fun ttlBoundaryIsInclusive() {
        assertTrue(enrollStateAccepted(nonce, nonce, t0, t0 + ttl, ttl))
        assertFalse(enrollStateAccepted(nonce, nonce, t0, t0 + ttl + 1, ttl))
    }

    /** A long-expired matching nonce ⇒ reject. Mutation killed: dropping the expiry check
     *  entirely (an old nonce that leaked would otherwise still pair). */
    @Test
    fun anExpiredMatchingNonceIsRejected() {
        assertFalse(enrollStateAccepted(nonce, nonce, t0, t0 + ttl * 10, ttl))
    }

    /** newEnrollState mints a 128-bit (32 lower-hex char) nonce, and two mints differ —
     *  guards against a "return a constant" regression that would defeat the whole scheme. */
    @Test
    fun newEnrollStateIsRandom128BitHex() {
        val a = newEnrollState()
        val b = newEnrollState()
        assertEquals(a.length, 32, "expected 32 hex chars for 128 bits, got '${a}'")
        assertTrue(a.matches(Regex("[0-9a-f]{32}")), "expected lower-hex, got '$a'")
        assertNotEquals(a, b, "two mints must not collide (a constant nonce defeats the gate)")
    }
}
