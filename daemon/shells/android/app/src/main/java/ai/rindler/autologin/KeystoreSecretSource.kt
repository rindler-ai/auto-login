// KeystoreSecretSource — the native secure store behind the Go core.
//
// Implements `MobileSecretSource` (gomobile-bound). The Go core calls
// `lookup(site)` per approved ping and expects the credential record as a JSON
// STRING, or "" when the device holds nothing for the site:
//
//   {"username":"…","password":"…",
//    "totp":{"Secret":"<base64 raw seed>","Digits":6,"Period":30,"Algorithm":"SHA1"}}
//
// `totp` is optional — omit it (or null) for password-only sites. `Secret` is
// base64 of the RAW seed bytes (already base32-decoded), matching totp.Config's
// []byte field. Storage is EncryptedSharedPreferences, whose master key lives in
// the Android Keystore (hardware-backed where available). An approved requested
// value is loaded transiently and sealed to the login worker; the durable record
// remains in native storage.

package ai.rindler.autologin

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import ai.rindler.mobile.SecretSource
import org.json.JSONArray

class KeystoreSecretSource(context: Context) : SecretSource {

    // Master key in the Android Keystore (AES-256-GCM, StrongBox where present).
    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "rindler_custody_secrets",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    // Reserved keys for this device's identity (never a real site host).
    private companion object {
        const val K_DEVICE_TOKEN = "rindler-meta:device-token"
        const val K_DEVICE_KEY = "rindler-meta:device-key" // base64 Ed25519 private key

        // The SERVER's base64 Ed25519 PUBLIC key, handed to us once at pairing.
        // The Go core verifies every SecretPing against it before sealing anything: the
        // signature covers the worker's ephemeral key, so an on-path party that
        // substitutes its own recipient key is caught. A device without it declines
        // every ping (fail-closed, by design) and must re-pair.
        const val K_SERVER_PUBKEY = "rindler-meta:server-pubkey"

        const val K_SITE_INDEX = "rindler-meta:sites"       // JSON array of enrolled sites
        const val K_ONBOARDED = "rindler-meta:onboarded"    // has the intro been seen
        const val K_SMS_AUTOREAD = "rindler-meta:sms-autoread" // user opted into auto-reading 2FA texts
        const val K_HUB_URL = "rindler-meta:hub-url"        // the hub this device pairs + connects to

        // Device-egress proxy opt-in + the durable egress token. When ON, the paired
        // device runs a tunnel egress so the user's OWN agent sessions exit through THIS
        // device's IP. The token is a per-user tunnel token minted server-side; the
        // gateway is the tunnel wss:// endpoint. clear()/reset() wipes it.
        const val K_EGRESS_ENABLED = "rindler-meta:egress-enabled"
        const val K_EGRESS_TOKEN = "rindler-meta:egress-token"     // rt_live_ per-user tunnel token
        const val K_EGRESS_GATEWAY = "rindler-meta:egress-gateway" // wss:// tunnel gateway URL
    }

    // --- hub URL (which hub this device pairs + connects to) ---

    /// The hub WebSocket URL the user pointed this device at during pairing, or null
    /// if never set. A release APK ships with a BuildConfig.HUB_URL default (a real
    /// hub for a branded build, or a placeholder for a self-host build); the pairing
    /// screen lets the user override it, and the stored value wins from then on so the
    /// relay reconnects to the SAME hub that minted the pairing token. Not a secret —
    /// just a hostname — but kept in the same store so "Reset device" clears it too.
    fun hubUrl(): String? = prefs.getString(K_HUB_URL, null)?.takeIf { it.isNotBlank() }

    fun setHubUrl(url: String) {
        prefs.edit().putString(K_HUB_URL, url.trim()).apply()
    }

    // --- SMS auto-read opt-in (the user's choice; no OS permission is involved) ---

    /// Whether the user turned ON "fill in codes from a text". This is only the user's
    /// INTENT to arm the SMS User Consent listener (SmsAutoRead); even armed, the app
    /// sees a message only if the user allows Android's per-message consent prompt.
    /// Default OFF — nothing watches for a text until asked.
    fun isSmsAutoReadEnabled(): Boolean = prefs.getBoolean(K_SMS_AUTOREAD, false)

    fun setSmsAutoReadEnabled(on: Boolean) {
        prefs.edit().putBoolean(K_SMS_AUTOREAD, on).apply()
    }

    // --- Device-egress proxy opt-in + tunnel token ---

    /// Whether the user turned ON "use my device as the hub's connection". Default OFF;
    /// the egress tunnel runs only when this is on AND a token is linked (RelayService).
    fun isEgressEnabled(): Boolean = prefs.getBoolean(K_EGRESS_ENABLED, false)

    fun setEgressEnabled(on: Boolean) {
        prefs.edit().putBoolean(K_EGRESS_ENABLED, on).apply()
    }

    /// True once an egress token + gateway are stored (independent of the enabled flag).
    fun isEgressLinked(): Boolean = egressCredentials() != null

    /// The durable egress token + tunnel gateway, or null if not linked. The token never
    /// authorizes anything but this user's own egress; the device only carries opaque TCP.
    fun egressCredentials(): EgressCredentials? {
        val token = prefs.getString(K_EGRESS_TOKEN, null)?.takeIf { it.isNotBlank() } ?: return null
        val gateway = prefs.getString(K_EGRESS_GATEWAY, null)?.takeIf { it.isNotBlank() } ?: return null
        return EgressCredentials(token, gateway)
    }

    /// Persist the minted egress token + gateway and turn the toggle ON (one commit).
    fun linkEgress(token: String, gateway: String) {
        prefs.edit()
            .putString(K_EGRESS_TOKEN, token)
            .putString(K_EGRESS_GATEWAY, gateway)
            .putBoolean(K_EGRESS_ENABLED, true)
            .apply()
    }

    /// Forget the egress token and turn the toggle off. The caller should ALSO revoke the
    /// server daemon (POST /devices/egress/disable) so the token stops working.
    fun unlinkEgress() {
        prefs.edit()
            .remove(K_EGRESS_TOKEN)
            .remove(K_EGRESS_GATEWAY)
            .putBoolean(K_EGRESS_ENABLED, false)
            .apply()
    }

    // --- onboarding / reset (drive the first-run intro) ---

    fun isOnboarded(): Boolean = prefs.getBoolean(K_ONBOARDED, false)

    fun setOnboarded() {
        prefs.edit().putBoolean(K_ONBOARDED, true).apply()
    }

    /// Wipe everything on this device (identity — token + device key + the server's
    /// ping-signing pubkey — plus all credentials + onboarding). clear() drops every
    /// key in this store, so the server pubkey goes with it: no stale key survives a
    /// device that is no longer paired. Used by "Reset device"; the next launch starts
    /// fresh at the intro. Mirrors iOS's Keychain.resetAll().
    fun reset() {
        prefs.edit().clear().apply()
    }

    /// Called by the Go core per approved ping. Returns the site's credential JSON
    /// (the contract above) or "" if we hold nothing for it.
    override fun lookup(site: String): String {
        // Credentials are stored as the ready-to-parse JSON string, keyed by site.
        return prefs.getString(site, "") ?: ""
    }

    /// Called by the Go core to answer a server site-inventory query: the domains
    /// this device holds a login for, as a JSON array STRING (gomobile can't bind a
    /// List<String> return). Domains only — never a credential.
    override fun listSites(): String = JSONArray(sites()).toString()

    // --- device identity (used by MainActivity + the pairing screen) ---

    fun deviceToken(): String? = prefs.getString(K_DEVICE_TOKEN, null)
    fun deviceKeyB64(): String? = prefs.getString(K_DEVICE_KEY, null)

    /// The server's ping-signing PUBLIC key from pairing, or null on a device paired
    /// before. Mobile.start requires it — a device without one can verify no
    /// ping and must re-pair. (Mirrors iOS's Keychain.serverPubkeyB64().)
    fun serverPubkeyB64(): String? = prefs.getString(K_SERVER_PUBKEY, null)

    /// Persist the whole pairing result in ONE commit: the hub bearer token, this
    /// device's private key, and the server's ping-signing public key. All three are
    /// load-bearing — a token saved without the server pubkey yields a device that
    /// declines every credential release.
    fun saveIdentity(token: String, deviceKeyB64: String, serverPubkeyB64: String) {
        prefs.edit()
            .putString(K_DEVICE_TOKEN, token)
            .putString(K_DEVICE_KEY, deviceKeyB64)
            .putString(K_SERVER_PUBKEY, serverPubkeyB64)
            .apply()
    }

    // --- enrollment (writes) — native side owns this; Go never writes ---

    /// Persist a credential record for a site (the JSON contract above), driven by
    /// the EnrollScreen. The site is added to the index so HOME can list it.
    fun enroll(site: String, json: String) {
        val updated = (sites() + site).distinct()
        prefs.edit()
            .putString(site, json)
            .putString(K_SITE_INDEX, JSONArray(updated).toString())
            .apply()
    }

    /// Remove a site's stored credential and drop it from the index. The plaintext
    /// is erased from the encrypted store; there is nothing left to relay for it.
    fun delete(site: String) {
        val updated = sites().filterNot { it == site }
        prefs.edit()
            .remove(site)
            .putString(K_SITE_INDEX, JSONArray(updated).toString())
            .apply()
    }

    /// The sites with a stored credential (drives the HOME list). Kept as an
    /// explicit index because EncryptedSharedPreferences.getAll() is unreliable.
    fun sites(): List<String> {
        val raw = prefs.getString(K_SITE_INDEX, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrDefault(emptyList())
    }
}

/** The durable device-egress token + tunnel gateway. Held only in
 *  EncryptedSharedPreferences; authorizes only this user's own egress. */
data class EgressCredentials(
    val token: String,
    val gateway: String,
)
