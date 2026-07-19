package ai.rindler.autologin

import org.testng.Assert.assertEquals
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

/*
 * Testing strategy for normalizeSiteKey(s):
 *   partition scheme:     none, http://, https://, uppercase scheme
 *   partition www:        absent, leading "www.", a non-www subdomain (meaningful, kept)
 *   partition tail:       none, path, trailing slash, query, fragment, port, trailing dot
 *   partition case/space: lowercase, mixed case, surrounding whitespace
 *   partition degenerate: blank, scheme-only (no domain left)
 * The load-bearing property is COLLAPSE: every way a user plausibly types one site must
 * yield the ONE key the server's ping is matched against — a miss here is a login that
 * looks saved but never works. Plus idempotence (keys already stored stay stable).
 *
 * Testing strategy for planSiteKeyMigration(storedKeys):
 *   partition per key:  already normalized, mis-keyed with a free normalized slot,
 *                       mis-keyed duplicating a healthy row, mis-keyed duplicating an
 *                       earlier move, normalizes to "" (unintelligible)
 * Oracle from the postcondition: after applying the plan every surviving row sits under
 * its normalized key, no row is ever moved onto "" and no data is dropped unless a row
 * already exists under the same normalized key.
 */
class SiteKeyTest {

    /** covers the collapse property — the raw typed variants of one site → one key. */
    @Test
    fun rawTypedVariantsCollapseToOneKey() {
        val variants = listOf(
            "www.chase.com",
            "https://chase.com/login",
            "http://www.chase.com",
            "chase.com/",
            "CHASE.COM",
            "  chase.com  ",
            "HTTPS://WWW.Chase.COM/login/",
            "chase.com:443",
            "chase.com?ref=homepage",
            "chase.com#signin",
            "chase.com.",
        )
        for (typed in variants) {
            assertEquals(
                normalizeSiteKey(typed), "chase.com",
                "'$typed' must key to the domain a ping asks for",
            )
        }
    }

    /** A bare domain is a fixed point, so already-stored normalized keys never churn. */
    @Test
    fun normalizationIsIdempotent() {
        for (key in listOf("chase.com", "app.chase.com", "bmv-ohio.example")) {
            assertEquals(normalizeSiteKey(key), key)
            assertEquals(normalizeSiteKey(normalizeSiteKey(key)), normalizeSiteKey(key))
        }
    }

    /** Only "www." is decoration; other subdomains name DIFFERENT sites and are kept. */
    @Test
    fun nonWwwSubdomainsAreKept() {
        assertEquals(normalizeSiteKey("https://app.chase.com/login"), "app.chase.com")
    }

    /** covers the degenerate partition — no domain left means NO key, never a "" row. */
    @Test
    fun inputWithNoDomainNormalizesToEmpty() {
        for (s in listOf("", "   ", "https://", "http://")) {
            assertEquals(normalizeSiteKey(s), "", "'$s' has no usable key")
        }
    }

    // --- planSiteKeyMigration ---

    /** A healthy index (all keys already normalized) must be left completely alone. */
    @Test
    fun aCleanIndexIsANoOp() {
        val plan = planSiteKeyMigration(listOf("chase.com", "app.chase.com"))
        assertTrue(plan.moves.isEmpty() && plan.drops.isEmpty(), "clean index produced $plan")
    }

    /**
     * covers the outage shape: rows saved under the raw typed string while the catalog
     * fetch was down. Each is moved to the key a ping actually asks for.
     */
    @Test
    fun misKeyedRowsAreMovedToTheirNormalizedKey() {
        val plan = planSiteKeyMigration(listOf("www.chase.com", "https://x.example/login"))
        assertEquals(
            plan.moves,
            listOf("www.chase.com" to "chase.com", "https://x.example/login" to "x.example"),
        )
        assertTrue(plan.drops.isEmpty())
    }

    /**
     * A raw duplicate of a row that ALREADY sits under the normalized key is dropped:
     * the normalized row is the only one lookup() could ever have served, so it wins.
     */
    @Test
    fun aRawDuplicateOfAHealthyRowIsDropped() {
        val plan = planSiteKeyMigration(listOf("chase.com", "https://chase.com/login"))
        assertTrue(plan.moves.isEmpty(), "must not overwrite the serving row: $plan")
        assertEquals(plan.drops, listOf("https://chase.com/login"))
    }

    /** Two mis-keyed variants of one site merge: first moves, second drops. */
    @Test
    fun twoMisKeyedVariantsOfOneSiteMergeIntoOne() {
        val plan = planSiteKeyMigration(listOf("www.chase.com", "https://chase.com/"))
        assertEquals(plan.moves, listOf("www.chase.com" to "chase.com"))
        assertEquals(plan.drops, listOf("https://chase.com/"))
    }

    /** A key that normalizes to "" is untouched — never strand a row under the empty key. */
    @Test
    fun anUnintelligibleKeyIsLeftAlone() {
        val plan = planSiteKeyMigration(listOf("https://", "chase.com"))
        assertTrue(plan.moves.isEmpty() && plan.drops.isEmpty(), "unintelligible key mishandled: $plan")
    }
}
