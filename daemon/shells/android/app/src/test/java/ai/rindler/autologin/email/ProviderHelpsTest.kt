package ai.rindler.autologin.email

import org.testng.Assert.assertEquals
import org.testng.Assert.assertNotNull
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

/*
 * allProviderHelps() is the pure list that makes app-password help DISCOVERABLE on the link
 * form (it renders every provider upfront, before any address is typed). These tests pin it
 * against the concrete six providers and prove it can never drift from the per-address
 * lookup, since both read one shared table.
 *
 * Testing strategy:
 *   allProviderHelps():
 *     - shape: exactly the six known providers, each with a non-blank label and an https URL,
 *       in a stable order (Gmail first). The expected URL set is pinned INDEPENDENTLY of the
 *       table, so dropping a provider from the table turns this red — the mutation gate.
 *     - consistency with providerAppPasswordHelp(): every domain the per-address lookup
 *       recognises resolves to a help present in the list (same label + URL), and every entry
 *       in the list is reachable from at least one domain (no orphan link). Both directions,
 *       so a table refactor that split the two sources would be caught.
 *
 * Mutation gate (described, and exercised by hand): delete any one EmailProvider entry from
 * EMAIL_PROVIDERS in EmailMailboxSupport.kt. `surfacesExactlySixProvidersUpfront` then sees
 * size 5 and a missing URL and fails, and `listIsConsistentWithPerDomainLookup` sees the
 * corresponding domain resolve to null and fails. Restoring the entry returns both to green.
 */
class ProviderHelpsTest {

    /** One representative-heavy list of every domain the per-address lookup must recognise
     *  (all aliases across the six families). Kept here, NOT derived from the table, so it is
     *  an independent oracle. */
    private val knownDomains = listOf(
        "gmail.com", "googlemail.com",
        "icloud.com", "me.com", "mac.com",
        "yahoo.com", "ymail.com", "rocketmail.com",
        "outlook.com", "hotmail.com", "live.com", "msn.com",
        "aol.com",
        "fastmail.com", "fastmail.fm",
    )

    /** The six app-password pages, pinned by URL, independent of the table. If a provider is
     *  dropped from EMAIL_PROVIDERS this expected set no longer matches — the mutation gate. */
    private val expectedUrls = setOf(
        "https://myaccount.google.com/apppasswords",
        "https://account.apple.com/account/manage",
        "https://login.yahoo.com/account/security/app-passwords",
        "https://account.live.com/proofs/AppPassword",
        "https://login.aol.com/account/security/app-passwords",
        "https://app.fastmail.com/settings/security/apppasswords",
    )

    /** Mutation killed: removing any EmailProvider from the table — the list would then carry
     *  five entries and the missing URL, so both the size and the URL-set assertion fail. */
    @Test
    fun surfacesExactlySixProvidersUpfront() {
        val helps = allProviderHelps()
        assertEquals(helps.size, 6, "all six providers must be offered upfront")
        assertEquals(helps.map { it.url }.toSet(), expectedUrls)
        for (h in helps) {
            assertTrue(h.label.isNotBlank(), "provider label must be non-blank")
            assertTrue(h.url.startsWith("https://"), "provider url must be https: ${h.url}")
        }
    }

    /** Stable, sensible order: the most common consumer mailbox (Gmail) leads. Kills a
     *  mutation that reorders the table so the list surfaces in an arbitrary order. */
    @Test
    fun ordersTheMostCommonProviderFirst() {
        assertEquals(allProviderHelps().first().url, "https://myaccount.google.com/apppasswords")
    }

    /** The single-table refactor's invariant, both directions: the upfront list and the
     *  per-address highlight are exactly the same set of providers, so they can never
     *  disagree. Kills a mutation that gives allProviderHelps its own second copy of the
     *  URLs (which could then drift from providerAppPasswordHelp). */
    @Test
    fun listIsConsistentWithPerDomainLookup() {
        // Forward: every recognised domain resolves to a help that is present in the list.
        for (domain in knownDomains) {
            val help = providerAppPasswordHelp("user@$domain")
            assertNotNull(help, "$domain should be a recognised provider")
            assertTrue(
                allProviderHelps().any { it.label == help!!.label && it.url == help.url },
                "$domain -> ${help!!.url} must appear in allProviderHelps()",
            )
        }
        // Backward: every entry in the upfront list is reachable from some known domain, so
        // the list carries no orphan link the per-address lookup can never produce.
        val detected = knownDomains.mapNotNull { providerAppPasswordHelp("user@$it") }.toSet()
        assertEquals(allProviderHelps().toSet(), detected)
    }
}
