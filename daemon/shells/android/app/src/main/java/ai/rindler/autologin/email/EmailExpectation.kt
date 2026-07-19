// EmailExpectation is the device-side "a login is waiting for an emailed code right now"
// window — the email analog of SmsExpectation and the load-bearing privacy gate for
// on-device email auto-read: MailboxReader polls the linked mailboxes ONLY while this
// window is open, and touches nothing outside it.
//
// DIVERGENCE from SMS: SMS is receiver-triggered (the OS pushes SMS_RECEIVED and the window
// merely GATES a passive read). Email has no OS broadcast, so the window does double duty:
// it also DRIVES an active poll that the arm itself starts (MailboxReader.kick). The poll is
// (re)started ONLY from a live, verified OnExpectingEmailCode this process-life — the
// persisted deadline below keeps an already-running loop alive across a config change, but
// never initiates a fetch after a process restart (generation resets to 0, so a captured gen
// no longer matches).
//
// Armed from the AUTHENTICATED, replay-checked email_otp_code ping the worker sends over the
// hub (Mobile.CodeExpectationSink -> RelayService), for the ping's server-signed TTL
// (clamped). Never armed from anything received unverified.

package ai.rindler.autologin.email

import android.content.Context

object EmailExpectation {
    private const val PREFS = "email_expectation"
    private const val KEY_UNTIL = "expecting_until_ms"

    // Clamp the server-signed TTL: never arm shorter than a code can take to arrive and race
    // the waiter, never longer than a login would plausibly sit waiting.
    private const val MIN_WINDOW_MS = 90_000L
    private const val MAX_WINDOW_MS = 300_000L

    @Volatile
    private var untilMs: Long = 0L

    // Bumped on every arm, so a completed delivery only closes the window IT opened. After a
    // process restart it resets to 0, so a poll loop that captured an earlier gen cannot
    // resume, and a stale disarm no-ops.
    @Volatile
    private var generation: Long = 0L

    /** Open the window for [ttlSeconds] (clamped) from now. Returns the generation. */
    fun arm(ctx: Context, ttlSeconds: Long): Long {
        val secs = ttlSeconds.coerceIn(0L, 600L)
        val window = (secs * 1000L).coerceIn(MIN_WINDOW_MS, MAX_WINDOW_MS)
        val deadline = System.currentTimeMillis() + window
        untilMs = deadline
        generation += 1
        // commit(): durable before we return, for parity + mid-window restart resume.
        prefs(ctx).edit().putLong(KEY_UNTIL, deadline).commit()
        return generation
    }

    /** True while a login is still awaiting a code (warm @Volatile, prefs fallback). */
    fun isExpecting(ctx: Context): Boolean {
        val now = System.currentTimeMillis()
        if (untilMs > now) return true
        return prefs(ctx).getLong(KEY_UNTIL, 0L) > now
    }

    /** The generation currently open (captured by a poll so it stops only for its own). */
    fun currentGeneration(): Long = generation

    /** Close the window IF it is still the [atGeneration] one (best-effort, single-shot). */
    fun disarm(ctx: Context, atGeneration: Long) {
        if (generation != atGeneration) return
        untilMs = 0L
        prefs(ctx).edit().remove(KEY_UNTIL).apply()
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
