package ai.rindler.autologin.email

import org.testng.Assert.assertEquals
import org.testng.Assert.assertFalse
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

/*
 * Pure helpers for the email-OTP lane. Each test names the MUTATION it kills, so the suite
 * is provably non-vacuous (a described mutation must turn a passing test red).
 *
 * stripAppPasswordWhitespace(raw):
 *   partition: no whitespace, interior single spaces (the Google 4x4 grouping), tabs/newlines
 *   from a paste, leading/trailing spaces, an all-spaces string. Property: no whitespace
 *   survives, and every significant character does.
 *
 * imapHostForDomain(email):
 *   partition: each known provider family, an unknown domain, no '@'. Property: known → the
 *   BARE host (no :993, since the Go core appends it); unknown/degenerate → null.
 *
 * orderMailboxesForLogin(mailboxes, username):
 *   partition: username matches one address / matches none / is null / is blank; match not
 *   first. Property: a match is moved to the front, EVERY mailbox is retained (hint, not
 *   filter), and the rest keep their order.
 */
class EmailMailboxSupportTest {

    // --- stripAppPasswordWhitespace ---

    /** Mutation killed: replacing the body with `raw` (no strip) — the padded Google form
     *  would then be stored verbatim and IMAP LOGIN would reject it. */
    @Test
    fun stripsEveryKindOfWhitespaceButKeepsCharacters() {
        assertEquals(stripAppPasswordWhitespace("abcd efgh ijkl mnop"), "abcdefghijklmnop")
        assertEquals(stripAppPasswordWhitespace("  abcd\tefgh\nijkl "), "abcdefghijkl")
        assertEquals(stripAppPasswordWhitespace("nospaces"), "nospaces")
        assertEquals(stripAppPasswordWhitespace("   "), "")
    }

    /** A password with no whitespace must be returned byte-for-byte (kills a mutation that
     *  trims or lower-cases). */
    @Test
    fun leavesASignificantPasswordUntouched() {
        val pw = "aB3\$xY9!qWeR"
        assertEquals(stripAppPasswordWhitespace(pw), pw)
    }

    // --- imapHostForDomain ---

    /** Mutation killed: returning `imap.gmail.com:993` — the Go core appends :993, so a
     *  ported-with-port host would dial `...:993:993`. The host MUST be bare. */
    @Test
    fun knownProvidersMapToBareImapHosts() {
        assertEquals(imapHostForDomain("jo@gmail.com"), "imap.gmail.com")
        assertEquals(imapHostForDomain("jo@googlemail.com"), "imap.gmail.com")
        assertEquals(imapHostForDomain("JO@ICLOUD.COM"), "imap.mail.me.com")
        assertEquals(imapHostForDomain("jo@yahoo.com"), "imap.mail.yahoo.com")
        assertEquals(imapHostForDomain("jo@outlook.com"), "outlook.office365.com")
        // Not a single detected host carries a port.
        for (addr in listOf("a@gmail.com", "a@icloud.com", "a@yahoo.com", "a@outlook.com", "a@aol.com")) {
            assertFalse(imapHostForDomain(addr)!!.contains(":"), "$addr host must be bare (no :993)")
        }
    }

    /** An unknown domain and a malformed address both yield null (kills a mutation that
     *  returns a fixed host for everything). */
    @Test
    fun unknownOrMalformedYieldsNull() {
        assertEquals(imapHostForDomain("jo@example.com"), null)
        assertEquals(imapHostForDomain("not-an-email"), null)
        assertEquals(imapHostForDomain(""), null)
    }

    // --- orderMailboxesForLogin ---

    private fun mbox(addr: String) = EmailMailbox(addr, "imap.example", "pw")

    /** Mutation killed: returning `mailboxes` unchanged — the username-matching mailbox
     *  must be hoisted to the front so it is polled first. */
    @Test
    fun theUsernameMatchingMailboxIsTriedFirst() {
        val list = listOf(mbox("work@fastmail.com"), mbox("me@gmail.com"), mbox("alt@yahoo.com"))
        val ordered = orderMailboxesForLogin(list, "me@gmail.com")
        assertEquals(ordered.first().address, "me@gmail.com")
        // Hint, not filter: all three survive, and the non-matching ones keep their order.
        assertEquals(ordered.map { it.address }, listOf("me@gmail.com", "work@fastmail.com", "alt@yahoo.com"))
    }

    /** Case/space-insensitive match (kills a mutation using raw `==` instead of normalize). */
    @Test
    fun matchIsCaseAndSpaceInsensitive() {
        val list = listOf(mbox("work@fastmail.com"), mbox("me@gmail.com"))
        assertEquals(orderMailboxesForLogin(list, "  ME@Gmail.com ").first().address, "me@gmail.com")
    }

    /** No match / null / blank username: the list is returned intact and in order (kills a
     *  mutation that drops non-matching mailboxes or reorders on no match). */
    @Test
    fun noMatchOrNoUsernameKeepsEveryMailboxInOrder() {
        val list = listOf(mbox("a@gmail.com"), mbox("b@yahoo.com"))
        assertEquals(orderMailboxesForLogin(list, "z@nowhere.com").map { it.address }, listOf("a@gmail.com", "b@yahoo.com"))
        assertEquals(orderMailboxesForLogin(list, null).map { it.address }, listOf("a@gmail.com", "b@yahoo.com"))
        assertEquals(orderMailboxesForLogin(list, "   ").map { it.address }, listOf("a@gmail.com", "b@yahoo.com"))
    }

    // --- isMailboxAuthError (the cross-language classification the reader depends on) ---

    /** A broken mailbox (ErrMailboxAuth) message classifies as auth; a transient one does
     *  not — the distinction that keeps a revoked app-password from retrying forever. */
    @Test
    fun authErrorIsDistinguishedFromTransient() {
        assertTrue(isMailboxAuthError("email-otp: mailbox rejected the app password"))
        assertFalse(isMailboxAuthError("email-otp: mailbox temporarily unreachable"))
        assertFalse(isMailboxAuthError(null))
    }
}
