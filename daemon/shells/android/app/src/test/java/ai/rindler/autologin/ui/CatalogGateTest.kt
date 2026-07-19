package ai.rindler.autologin.ui

import org.testng.Assert.assertFalse
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

/*
 * Testing strategy for canOfferMappingRequest(catalogLoaded, isSupported, hasMatches,
 * looksLikeDomain): four booleans — the space (2^4) is covered exhaustively by the
 * honesty property, plus representatives for the one true cell and each false gate.
 *
 * The honesty property: "Request site mapping" is the UI asserting "this site is not
 * supported". A FAILED catalog fetch (catalogLoaded = false) proves nothing about any
 * site, so that assertion may NEVER appear off an outage — during one, every save used
 * to fall through to the raw-typed-string key and silently never work.
 */
class CatalogGateTest {
    private val bools = listOf(true, false)

    /** The one cell that offers the request: loaded catalog, no match, domain-like text. */
    @Test
    fun offersMappingOnlyOffALoadedCatalogWithNoMatch() {
        assertTrue(
            canOfferMappingRequest(
                catalogLoaded = true, isSupported = false, hasMatches = false, looksLikeDomain = true,
            ),
        )
    }

    /** covers the honesty property exhaustively: a failed/unloaded catalog never claims
     *  "not supported", whatever the other inputs. */
    @Test
    fun aFailedCatalogNeverClaimsASiteIsUnsupported() {
        for (supported in bools) for (matches in bools) for (domainLike in bools) {
            assertFalse(
                canOfferMappingRequest(
                    catalogLoaded = false,
                    isSupported = supported,
                    hasMatches = matches,
                    looksLikeDomain = domainLike,
                ),
                "catalog not loaded, but the UI would assert 'not supported' " +
                    "(supported=$supported matches=$matches domainLike=$domainLike)",
            )
        }
    }

    /** Each remaining gate stands on its own: a supported site, a suggestion match, or
     *  non-domain text all suppress the request row even when the catalog loaded. */
    @Test
    fun supportedOrMatchedOrNonDomainTextSuppressesTheRequest() {
        assertFalse(canOfferMappingRequest(true, isSupported = true, hasMatches = false, looksLikeDomain = true))
        assertFalse(canOfferMappingRequest(true, isSupported = false, hasMatches = true, looksLikeDomain = true))
        assertFalse(canOfferMappingRequest(true, isSupported = false, hasMatches = false, looksLikeDomain = false))
    }
}
