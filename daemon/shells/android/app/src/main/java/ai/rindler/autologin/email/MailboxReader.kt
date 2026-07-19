// MailboxReader — the active, windowed IMAP poll that reads an emailed 2FA code ON DEVICE and
// hands ONLY the code to the paused login. This is the email lane's answer to SMS having NO
// OS broadcast: where SmsReceiver passively gates a text the OS already delivered,
// MailboxReader must DRIVE the read, so the armed window starts and bounds a poll loop.
//
// PRIVACY / gates (mirror SmsReceiver's, plus email-specific ones):
//   - never polls unless email auto-read is ON, a mailbox is linked, and the window is open
//     (isExpecting). A user who linked then removed the mailbox gets no read.
//   - the loop is (re)started ONLY from kick(), which is only ever called by a live
//     OnExpectingEmailCode (a verified ping this process-life). A restored persisted deadline
//     never starts a fetch.
//   - sender match (fromContains) + the after:<arm> time bound (sinceEpochSec) are enforced
//     INSIDE Mobile.fetchEmailOTPOnce (Go core), which returns a single unambiguous cued code
//     or "". The app-password used to dial IMAP NEVER transits — the Go core dials the host
//     directly; only the extracted code is ever POSTed (via EmailOtpDelivery).
//
// TYPED-ERROR handling (the deliberate divergence from the reference's swallow): the fetch is
// NOT `runCatching{}.getOrDefault("")`. That collapse makes a revoked app-password
// indistinguishable from "no mail yet" — an infinite silent retry that stalls the login with
// zero signal. Here a broken mailbox (ErrMailboxAuth) is badged, notified once, and dropped
// for the window; a transient blip is retried; "no mail yet" just polls on.

package ai.rindler.autologin.email

import ai.rindler.autologin.KeystoreSecretSource
import ai.rindler.mobile.Mobile
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/** What one on-device mailbox read produced — the typed outcome the poll loop acts on. */
internal sealed interface FetchOutcome {
    data class Code(val code: String) : FetchOutcome
    data object NoneYet : FetchOutcome
    data object Unavailable : FetchOutcome
    data object AuthFailed : FetchOutcome
}

object MailboxReader {

    // ~4s cadence matches the server's email OTP poll interval. Process-scoped so the loop
    // survives the trampoline sink call; RelayService (FG, holding the hub WS) keeps the
    // process alive across the window, exactly like OtpDelivery's retry.
    private const val POLL_INTERVAL_MS = 4_000L

    // 0 = a SINGLE IMAP pass per fetch (Go: timeoutSeconds<=0 -> one pass, then return). The
    // Kotlin loop below provides the ~4s cadence, so each tick is one connect + search + close
    // bounded to mail at/after the arm time.
    private const val FETCH_TIMEOUT_SEC = 0L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var pollJob: Job? = null

    /** Start (or restart) the poll for the [generation] the sink just armed. Called ONLY from
     *  a live OnExpectingEmailCode. */
    fun kick(app: Context, site: String, generation: Long) {
        val ctx = app.applicationContext
        // Capture the arm time NOW: kick runs immediately after EmailExpectation.arm from the
        // same verified ping, so this IS the arm epoch. Every fetch is bounded to mail that
        // arrived at/after it, so a stale pre-arm code is never read.
        val sinceEpochSec = System.currentTimeMillis() / 1000L
        pollJob?.cancel()
        pollJob = scope.launch { pollLoop(ctx, site, generation, sinceEpochSec) }
    }

    private suspend fun pollLoop(app: Context, site: String, generation: Long, sinceEpochSec: Long) {
        val store = KeystoreSecretSource(app)
        val fromContains = senderPatternFor(site)
        // Fail closed on an underivable sender bound: passing "" to fetchEmailOTPOnce makes the
        // Go core's FromContains match ANY sender, silently disabling the only sender bound —
        // an unrelated cued OTP could then be forwarded. Site comes from a server-signed ping
        // so this should never be blank; if it is, skip rather than widen to match-any.
        if (fromContains.isBlank()) return
        // Ordering HINT only: the mailbox whose address matches this site's saved username is
        // polled first. It is NEVER a filter — every linked mailbox is still read.
        val loginUsername = savedUsernameFor(store, site)
        // Mailboxes that failed AUTH this window are dropped for the rest of it (a dead
        // credential can't recover mid-window); they stay badged for the UI to surface.
        val broken = mutableSetOf<String>()

        while (
            EmailExpectation.currentGeneration() == generation &&
            EmailExpectation.isExpecting(app)
        ) {
            // Re-check the opt-in every tick; a mid-window unlink stops the read.
            if (!store.isEmailAutoReadEnabled()) return
            val ordered = orderMailboxesForLogin(store.linkedEmails(), loginUsername)
            if (ordered.isEmpty()) return
            for (mbox in ordered) {
                val addr = normalizeEmail(mbox.address)
                if (addr in broken) continue
                when (val outcome = readOnce(mbox, fromContains, sinceEpochSec)) {
                    is FetchOutcome.Code -> {
                        // A working read clears any stale "broken" badge on this mailbox.
                        store.clearEmailNeedsAttention(mbox.address)
                        // Delivered to a waiting login -> window closed for this generation, stop.
                        if (EmailOtpDelivery.deliver(app, outcome.code, generation)) return
                    }
                    FetchOutcome.NoneYet -> {} // keep polling this mailbox
                    FetchOutcome.Unavailable -> {} // transient — keep polling (self-heals)
                    FetchOutcome.AuthFailed -> {
                        // Revoked/wrong app-password: the mailbox is broken. Drop it for this
                        // window, badge it durably, and fire the one-shot notice ONLY on the
                        // healthy->broken transition (markEmailNeedsAttention returns true once).
                        broken += addr
                        if (store.markEmailNeedsAttention(mbox.address)) {
                            EmailHealthNotifier.notifyMailboxBroken(app, mbox.address)
                        }
                    }
                }
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    /**
     * One on-device read, classified. Mobile.fetchEmailOTPOnce returns the extracted code (or
     * "" for no-mail-yet) and THROWS a typed error for a broken/unreachable mailbox — so this
     * distinguishes the three outcomes instead of swallowing them all to "". The app-password
     * is passed straight to the Go core (which dials IMAP locally) and never returned or logged.
     */
    private fun readOnce(mbox: EmailMailbox, fromContains: String, sinceEpochSec: Long): FetchOutcome =
        try {
            val code = Mobile.fetchEmailOTPOnce(
                mbox.host, mbox.address, mbox.appPassword, fromContains, sinceEpochSec, FETCH_TIMEOUT_SEC,
            )
            if (code.isNullOrEmpty()) FetchOutcome.NoneYet else FetchOutcome.Code(code)
        } catch (e: Exception) {
            if (isMailboxAuthError(e.message)) FetchOutcome.AuthFailed else FetchOutcome.Unavailable
        }

    /** The saved login's username for [site], used only as the poll-ordering hint. Never a
     *  filter, so a parse miss (null) simply means "no hint". */
    private fun savedUsernameFor(store: KeystoreSecretSource, site: String): String? =
        runCatching {
            val raw = store.lookup(site).takeIf { it.isNotEmpty() } ?: return null
            JSONObject(raw).optString("username").takeIf { it.isNotBlank() }
        }.getOrNull()

    // Best-effort expected-sender hint from the site domain: the Go core applies it as the
    // FromContains restriction so only mail from the site's domain is a candidate.
    private fun senderPatternFor(site: String): String =
        site.trim().lowercase()
            .removePrefix("https://").removePrefix("http://").removePrefix("www.")
            .substringBefore("/")
}
