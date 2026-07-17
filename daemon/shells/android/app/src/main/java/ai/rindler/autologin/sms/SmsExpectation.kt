// SmsExpectation is the device-side "a login is waiting for a texted code right
// now" window. It is the load-bearing privacy gate for SMS auto-read: SmsReceiver
// inspects an incoming text ONLY while this window is open, and ignores every
// other text WITHOUT reading its body. So the app is exposed to the user's SMS
// solely during a real login that asked for a code — never the general stream.
//
// The window is armed from the AUTHENTICATED, replay-checked sms_otp_code ping the
// worker sends over the hub (Mobile.CodeExpectationSink -> RelayService), for the
// ping's server-signed TTL (clamped). It is never armed from anything the app
// merely receives off the wire unverified.
//
// Stored in a PLAIN prefs file (a deadline is not a secret, and a manifest
// SMS_RECEIVED receiver can fire on a COLD process where an in-memory value reads
// 0) plus a @Volatile mirror for a cheap read while the process is warm. arm()
// writes with commit() (not apply()) so the deadline is durable before the sink
// returns — a manifest receiver on a cold process must not miss it to an
// un-flushed async write.

package ai.rindler.autologin.sms

import android.content.Context

object SmsExpectation {
    private const val PREFS = "sms_expectation"
    private const val KEY_UNTIL = "expecting_until_ms"

    // Clamp the server-signed TTL: never arm shorter than a code can take to arrive
    // and race the waiter, never longer than a login would plausibly sit waiting.
    private const val MIN_WINDOW_MS = 90_000L
    private const val MAX_WINDOW_MS = 300_000L

    @Volatile
    private var untilMs: Long = 0L

    // Bumped on every arm, so a completed delivery only closes the window IT opened
    // (a later, overlapping login that re-armed keeps its own window). @Volatile /
    // process-local: after a process restart it resets to 0, and a stale disarm from
    // before the restart simply no-ops (the window then expires by TTL).
    @Volatile
    private var generation: Long = 0L

    /** Open the window for [ttlSeconds] (clamped) from now. Returns the generation. */
    fun arm(ctx: Context, ttlSeconds: Long): Long {
        // Coerce the seconds to a sane range BEFORE converting to millis so a server
        // that (even signed) hands a huge TTL cannot overflow or arm an endless window.
        val secs = ttlSeconds.coerceIn(0L, 600L)
        val window = (secs * 1000L).coerceIn(MIN_WINDOW_MS, MAX_WINDOW_MS)
        val deadline = System.currentTimeMillis() + window
        untilMs = deadline
        generation += 1
        // commit(): durable before we return, so a cold-process receiver can gate.
        prefs(ctx).edit().putLong(KEY_UNTIL, deadline).commit()
        return generation
    }

    /** True while a login is still awaiting a code (warm @Volatile, prefs fallback). */
    fun isExpecting(ctx: Context): Boolean {
        val now = System.currentTimeMillis()
        if (untilMs > now) return true
        return prefs(ctx).getLong(KEY_UNTIL, 0L) > now
    }

    /** The generation currently open (captured by a delivery so it disarms only its own). */
    fun currentGeneration(): Long = generation

    /** Close the window IF it is still the [atGeneration] one (best-effort, single-shot). */
    fun disarm(ctx: Context, atGeneration: Long) {
        if (generation != atGeneration) return // a newer login re-armed; leave its window
        untilMs = 0L
        prefs(ctx).edit().remove(KEY_UNTIL).apply()
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
