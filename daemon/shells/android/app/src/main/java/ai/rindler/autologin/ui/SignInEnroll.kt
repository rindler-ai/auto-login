// Sign-in-based device enrollment. Instead of copying a pairing code, the user taps
// "Sign in": we open the hub's /devices/authorize page in a Chrome Custom Tab, they
// sign in with their account, and the page redirects back to
// autologin://paired?token=…&hub=… . MainActivity catches that deep link and
// completeEnroll() finishes pairing through the SAME Mobile.pair() flow as manual
// entry — the device still generates and holds its own Ed25519 key; only the
// single-use pairing token crosses the browser.

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
        hub = uri.getQueryParameter("hub"),
        email = uri.getQueryParameter("email")?.takeIf { it.isNotBlank() },
        avatar = uri.getQueryParameter("avatar")?.takeIf { it.isNotBlank() },
    )
}

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
 * Finish pairing with a server-minted [token] (from the deep link OR a manually
 * entered code), pairing against [hub]. Unlike the branded custody app, the OSS app
 * has no trusted compiled-in hub, so the caller supplies which hub to pair against:
 * the sign-in path takes it from the deep link (EnrollRequest.hub), the manual path
 * from the entered hub field. The hub is persisted first (store.setHubUrl) so the
 * relay reconnects to the SAME one, then pairing runs against it. The device
 * generates its own Ed25519 key and persists all three identity values (token, key,
 * server ping-signing pubkey).
 */
suspend fun completeEnroll(store: KeystoreSecretSource, token: String, hub: String): Result<Unit> =
    withContext(Dispatchers.IO) {
        runCatching {
            // Persist the hub BEFORE pairing so the relay reconnects to the same one;
            // the pair/complete endpoint is derived from it below.
            store.setHubUrl(hub)
            val keyB64 = Mobile.generateDeviceKey()
            val pubB64 = Mobile.devicePublicKey(keyB64)
            // pair() returns BOTH the hub bearer token and the server's ping-signing
            // public key. Persist both: the Go core verifies every SecretPing against
            // that key before sealing a credential to the worker, so a device that
            // saved only the token declines every login.
            val res = Mobile.pair(
                pairUrl(hub), token, deviceName(), "android", pubB64,
            )
            store.saveIdentity(res.deviceToken, keyB64, res.serverPubkey)
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
    raw == null -> "Something went wrong. Try again."
    // Pairing-channel TOFU: the server key at pair/complete did not match the
    // fingerprint in the code — a possible on-path MITM.
    raw.contains("could not verify the hub's identity") ->
        "This device couldn't verify the server's identity. You may be on an untrusted network. Try again from a trusted connection."
    raw.contains("401") || raw.contains("invalid") ->
        "That code didn't work. It may have expired, so generate a new one."
    raw.contains("timeout") || raw.contains("connect") ->
        "Couldn't reach the server. Check your connection and retry."
    else -> "Couldn't pair. Generate a fresh code and try again."
}
