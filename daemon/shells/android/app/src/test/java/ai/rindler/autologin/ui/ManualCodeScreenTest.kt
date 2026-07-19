package ai.rindler.autologin.ui

import org.testng.Assert.assertEquals
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

/*
 * Testing strategy for sanitizeCodeInput(raw):
 *   partition content:    empty, digits-only, letters-only, mixed alphanumeric
 *   partition separators: interior/edge spaces, single hyphen, multiple hyphens
 *   partition junk:       ASCII punctuation, non-ASCII letters/digits, emoji, full-width
 *   partition length:     below cap, exactly at cap (12), one over, far over
 *   partition case:       mixed case (must survive untouched)
 *
 * The oracle is NOT this function's own code but the code the AUTO-READ path submits:
 * core/otp ExtractCode. That extractor emits ASCII [A-Za-z0-9] with every separator
 * stripped, case preserved, and length <= 12 (alnumMaxLen). The load-bearing property is
 * PARITY: a code the user types must reduce to the SAME alphabet a code auto-read would
 * forward, so both hit the rendezvous byte-identically. Two extractor fixtures are pinned
 * as concrete oracles below: "alnum-hyphen-grouped" (X7G2-9K1P -> X7G29K1P) proves the
 * hyphen is dropped, and "alnum-lower" (a1b2c3 stays a1b2c3) proves case is preserved.
 */
class ManualCodeScreenTest {

    /**
     * The whole partition set. Each pair is (raw input, expected sanitized output); the
     * invariant tests below re-assert the alphabet/length guarantees across all of them.
     */
    private val cases = listOf(
        // empty
        "" to "",
        // digits-only (the pre-fix baseline still works)
        "048913" to "048913",
        // letters-only — letters must NOT be swallowed (they were, under isDigit())
        "ABCD" to "ABCD",
        // mixed alphanumeric — the headline bug: a real code like G4F2K9A must be typeable
        "G4F2K9A" to "G4F2K9A",
        // separators: hyphen dropped (matches ExtractCode's reassembly), spaces dropped
        "G-4F2K9A" to "G4F2K9A",
        "X7G2-9K1P" to "X7G29K1P",
        "048 913" to "048913",
        "  4 8 2 9  " to "4829",
        // disallowed ASCII punctuation stripped
        "A!B@1#2\$4" to "AB124",
        "4.8.2.9" to "4829",
        // case preserved — never force upper/lower
        "a1B2c3" to "a1B2c3",
        // non-ASCII letters/digits, emoji, full-width all dropped (explicit ASCII ranges)
        "café🔥" to "caf",   // "café🔥" -> "caf"
        "١٢٣" to "",         // Arabic-Indic ١٢٣ -> ""
        "Ａ１" to "",               // full-width Ａ１ -> ""
        // realistic combined: spaces + hyphen + lowercase around a mixed code
        " g-4f2k9a " to "g4f2k9a",
    )

    /** covers the exact-output oracle for every partition case. */
    @Test
    fun sanitizesEachPartitionToItsExpectedOutput() {
        for ((raw, want) in cases) {
            assertEquals(sanitizeCodeInput(raw), want, "sanitizeCodeInput(${quote(raw)})")
        }
    }

    /**
     * covers the headline bug and is the MUTATION target: reverting the filter to
     * isDigit() makes this go RED (G4F2K9A -> "429"). Letters and mixed codes must pass
     * through verbatim — the reliability floor under SMS auto-read requires it.
     */
    @Test
    fun alphanumericCodeIsFullyTypeable() {
        assertEquals(sanitizeCodeInput("G4F2K9A"), "G4F2K9A")
        assertEquals(sanitizeCodeInput("ABCDEF"), "ABCDEF")
        assertEquals(sanitizeCodeInput("AB12CD"), "AB12CD")
    }

    /**
     * covers case preservation. Oracle: ExtractCode's "alnum-lower" fixture keeps
     * "a1b2c3" as "a1b2c3", so we must not upper/lower-case what the user typed.
     */
    @Test
    fun caseIsPreservedNeverForced() {
        val out = sanitizeCodeInput("a1B2c3")
        assertEquals(out, "a1B2c3")
        assertTrue(out != out.uppercase(), "must not be uppercased")
        assertTrue(out != out.lowercase(), "must not be lowercased")
    }

    /**
     * covers separator stripping, pinned to the extractor's "alnum-hyphen-grouped"
     * oracle: X7G2-9K1P reassembles to X7G29K1P (hyphen removed, not kept, not truncated).
     */
    @Test
    fun separatorsAreStrippedToMatchTheExtractor() {
        assertEquals(sanitizeCodeInput("X7G2-9K1P"), "X7G29K1P")
        assertEquals(sanitizeCodeInput("048 913"), "048913")
        assertEquals(sanitizeCodeInput("1-2-3-4-5-6"), "123456")
    }

    /**
     * covers the length boundary: below cap kept whole, exactly 12 kept whole, 13 and far
     * over truncated to 12. Also proves the cap is applied AFTER filtering (junk between
     * code chars does not eat into the budget): a raw string with separators keeps 12 real
     * chars. This also guards the cap value itself against a MAX_CODE_LEN=10 regression.
     */
    @Test
    fun cappedAtTwelveAfterFiltering() {
        assertEquals(sanitizeCodeInput("12345678901"), "12345678901")       // 11: below cap
        assertEquals(sanitizeCodeInput("123456789012"), "123456789012")     // 12: at cap
        assertEquals(sanitizeCodeInput("1234567890123"), "123456789012")    // 13: -> 12
        assertEquals(sanitizeCodeInput("ABCD1234EFGH5678IJKL"), "ABCD1234EFGH") // 20 -> 12
        // filter-then-cap: 16 alnum chars behind separators still yields the first 12.
        assertEquals(sanitizeCodeInput("1234-5678-9012-3456"), "123456789012")
    }

    /** covers the empty partition — nothing in, nothing out (never crashes, never junk). */
    @Test
    fun emptyStaysEmpty() {
        assertEquals(sanitizeCodeInput(""), "")
        assertEquals(sanitizeCodeInput("   "), "")
        assertEquals(sanitizeCodeInput("-.@!"), "")
    }

    // --- invariants asserted across the WHOLE partition set ---

    /**
     * Whatever the input, the output is drawn ONLY from ASCII [A-Za-z0-9]. This is the
     * property that keeps the typed code inside the alphabet the rendezvous accepts; it
     * fails if any separator, punctuation, unicode letter/digit, or emoji ever leaks.
     */
    @Test
    fun outputIsAlwaysAsciiAlphanumeric() {
        for ((raw, _) in cases) {
            val out = sanitizeCodeInput(raw)
            assertTrue(
                out.all { it in '0'..'9' || it in 'A'..'Z' || it in 'a'..'z' },
                "output ${quote(out)} for input ${quote(raw)} escaped the code alphabet",
            )
        }
    }

    /** The output never exceeds the extractor's max code length. */
    @Test
    fun outputNeverExceedsMaxLength() {
        for ((raw, _) in cases) {
            assertTrue(sanitizeCodeInput(raw).length <= 12, "over-long output for ${quote(raw)}")
        }
    }

    /** Idempotence: a clean (already-sanitized) code re-sanitizes to itself. */
    @Test
    fun sanitizationIsIdempotent() {
        for ((raw, _) in cases) {
            val once = sanitizeCodeInput(raw)
            assertEquals(sanitizeCodeInput(once), once, "not idempotent for ${quote(raw)}")
        }
    }

    private fun quote(s: String) = "'$s'"
}
