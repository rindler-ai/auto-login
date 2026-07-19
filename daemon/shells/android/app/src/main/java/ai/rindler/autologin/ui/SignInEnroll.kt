// Sign-in-based device enrollment. Instead of copying a pairing code, the user taps
// "Sign in": we open the hub's /devices/authorize page in a Chrome Custom Tab, they
// sign in with their account, and the page redirects back to
// autologin://paired?token=…&hub=… . MainActivity catches that deep link,
// gateEnroll() decides what it may do (a link-supplied hub must be the build
// default, and an already-linked phone asks the user first), and completeEnroll()
// finishes pairing through the SAME Mobile.pair() flow as manual entry — the
// device still generates and holds its own Ed25519 key; only the single-use
// pairing token crosses the browser.

package ai.rindler.autologin.ui

import ai.rindler.autologin.BuildConfig
import ai.rindler.autologin.KeystoreSecretSource
import ai.rindler.mobile.Mobile
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.browser.customtabs.CustomTabsIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val ENROLL_SCHEME = "autologin"
private const val ENROLL_HOST = "paired"

/**
 * A pairing token handed back from the sign-in web flow via the deep link, plus the
 * optional Rindler account identity (email / Google avatar) the web page appends from
 * Clerk. Identity is presentational only — it drives AccountHeader; a link that omits
 * it degrades to the "Signed in" + Shield fallback.
 */
data class EnrollRequest(
    val token: String,
    val hub: String?,
    val email: String? = null,
    val avatar: String? = null,
)

/** Parse an `autologin://paired?token=…&hub=…&email=…&avatar=…` deep link, or null. */
fun parseEnrollUri(uri: Uri?): EnrollRequest? {
    if (uri == null) return null
    if (!uri.scheme.equals(ENROLL_SCHEME, ignoreCase = true)) return null
    if (!uri.host.equals(ENROLL_HOST, ignoreCase = true)) return null
    val token = uri.getQueryParameter("token")?.takeIf { it.isNotBlank() } ?: return null
    return EnrollRequest(
        token = token,
        // Blank == omitted, so gateEnroll sees exactly two shapes: a named hub
        // (which must match the build default) or none (fall back to stored/default).
        hub = uri.getQueryParameter("hub")?.takeIf { it.isNotBlank() },
        email = uri.getQueryParameter("email")?.takeIf { it.isNotBlank() },
        avatar = uri.getQueryParameter("avatar")?.takeIf { it.isNotBlank() },
    )
}

/**
 * What a deep-link enrollment is allowed to do. The deep link is HOSTILE INPUT —
 * any web page the phone visits can fire `autologin://paired?token=…&hub=…` — so
 * it never gets to choose a server: pairing re-points the relay AND hands the new
 * server the device bearer token, and once paired every credential release is
 * auto-approved. See [gateEnroll] for the rules.
 */
sealed interface EnrollGate {
    /** Pair now, against [hub] (a trusted value, never the raw link parameter). */
    data class Proceed(val hub: String) : EnrollGate

    /** This phone is already linked: ask the user before replacing that link with [hub]. */
    data class ConfirmRelink(val hub: String) : EnrollGate

    /** The link named a server this build does not trust: do nothing with it. */
    data object RejectedHub : EnrollGate
}

/**
 * Gate a deep-link enrollment (pure; unit-tested in EnrollGateTest).
 *
 * Hub rule: a hub arriving IN THE LINK must match the compiled-in default
 * ([buildDefault], i.e. BuildConfig.HUB_URL) or the whole link is rejected — a
 * web page must never re-point this device at its own server. Self-hosting is
 * unaffected: a self-hoster's hub is either their build's own default (they
 * compiled it in) or typed by hand in Advanced, which pairs via
 * [completeEnroll] directly and never passes through this gate. A link that
 * names no hub falls back to the hub the USER already chose ([storedHub],
 * written only by successful pairings) or the build default.
 *
 * Re-link rule: a phone that already holds a device token never re-enrolls
 * silently — the caller must show an explicit confirmation naming the server
 * ([ConfirmRelink]) — otherwise a drive-by link with a freshly minted token
 * would quietly re-point an already-trusted device.
 */
fun gateEnroll(
    linkHub: String?,
    storedHub: String?,
    buildDefault: String,
    alreadyPaired: Boolean,
): EnrollGate {
    val hub = when {
        linkHub == null -> storedHub ?: buildDefault
        // Tolerate only trivial variance (whitespace / case); on a match, pair
        // against the CANONICAL compiled-in string, never the link's spelling.
        linkHub.trim().equals(buildDefault.trim(), ignoreCase = true) -> buildDefault
        else -> return EnrollGate.RejectedHub
    }
    return if (alreadyPaired) EnrollGate.ConfirmRelink(hub) else EnrollGate.Proceed(hub)
}

/** The bare host of a hub URL, for user-facing copy: "wss://x.example:8443/v1/…" -> "x.example". */
fun hubHost(hub: String): String =
    hub.trim()
        .substringAfter("://")
        .substringBefore("/")
        .substringBefore(":")
        .ifEmpty { hub.trim() }

/** Open the sign-in enrollment page in a Custom Tab. */
// Returns false if no browser could be launched (no browser installed, or a
// disabled/broken default) so the caller can show a fallback instead of failing
// silently. A browser that opens but can't reach the page ("site can't be reached")
// is the browser's own error surface, not catchable here.
fun openSignInEnroll(context: Context): Boolean {
    val url = Uri.parse(BuildConfig.AUTHORIZE_URL).buildUpon()
        .path("/devices/authorize")
        .appendQueryParameter("scheme", ENROLL_SCHEME)
        .appendQueryParameter("name", deviceName())
        .build()
    return try {
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, url)
        true
    } catch (_: Exception) {
        false
    }
}

/**
 * Finish pairing with a server-minted [token] (from a gated deep link OR a manually
 * entered code), pairing against [hub]. The caller supplies which hub to pair
 * against: the sign-in path takes it from [gateEnroll] (never the raw link
 * parameter), the manual path from the Advanced screen's hub field. [hub] is a
 * CANDIDATE until pairing succeeds: nothing is persisted before Mobile.pair()
 * returns, and then the hub is committed atomically WITH the identity
 * (store.saveIdentity) so the relay reconnects to the same hub that minted the
 * token. A failed pairing leaves the previous hub and identity untouched — a
 * hostile or broken pairing attempt must never leave the app pointed at a server
 * it did not successfully pair with (every later authed call — submitOtpCode,
 * egress mint/disable — would carry the real device bearer token there).
 */
suspend fun completeEnroll(store: KeystoreSecretSource, token: String, hub: String): Result<Unit> =
    withContext(Dispatchers.IO) {
        runCatching {
            val keyB64 = Mobile.generateDeviceKey()
            val pubB64 = Mobile.devicePublicKey(keyB64)
            // pair() returns BOTH the hub bearer token and the server's ping-signing
            // public key. Persist both: the Go core verifies every SecretPing against
            // that key before sealing a credential to the worker, so a device that
            // saved only the token declines every login.
            val res = Mobile.pair(
                pairUrl(hub), token, deviceName(), "android", pubB64,
            )
            store.saveIdentity(res.deviceToken, keyB64, res.serverPubkey, hub)
        }
    }

// wss://host/v1/devices/connect -> https://host/devices/pair/complete
internal fun pairUrl(hubUrl: String): String {
    val u = Uri.parse(hubUrl)
    val scheme = if (u.scheme.equals("ws", ignoreCase = true)) "http" else "https"
    return "$scheme://${u.host}${if (u.port > 0) ":${u.port}" else ""}/devices/pair/complete"
}

internal fun deviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

/** Map a raw pairing error to friendly copy (shared by sign-in + manual entry). */
fun friendlyPairError(raw: String?): String = when {
    raw == null -> "Couldn't finish linking this phone. Try again."
    // Pairing-channel TOFU: the server key at pair/complete did not match the
    // fingerprint in the code — a possible on-path MITM.
    raw.contains("could not verify the hub's identity") ->
        "This device couldn't verify the server's identity. You may be on an untrusted network. Try again from a trusted connection."
    raw.contains("401") || raw.contains("invalid") ->
        "That sign-in link didn't work — it may have expired. Sign in again, or generate a fresh code if you're using one."
    raw.contains("timeout") || raw.contains("connect") ->
        "Couldn't reach the server. Check your connection and retry."
    else -> "Couldn't finish linking this phone. Sign in again, or generate a fresh code and retry."
}
