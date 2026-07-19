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
import ai.rindler.autologin.email.EmailMailbox
import ai.rindler.autologin.email.normalizeEmail
import ai.rindler.mobile.SecretSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore

class KeystoreSecretSource(context: Context) : SecretSource {

    // Master key in the Android Keystore (AES-256-GCM, StrongBox where present).
    // Built through a recovery path (§4e): EncryptedSharedPreferences.create THROWS when the
    // keyset is corrupted or the master key was invalidated (a lock-screen/biometric change
    // can invalidate it). Because this store is constructed from the Activity, the
    // RelayService AND the SmsReceiver, an uncaught throw in this initializer fires on every
    // entry point — an unrecoverable crash LOOP that bricks the app. So on failure we reset
    // the store and rebuild once; losing saved logins to a reset (the user re-pairs) is
    // strictly better than a brick.
    private val prefs: SharedPreferences = openOrResetPrefs(context)

    // Build the encrypted store with the canonical config. AES256_GCM master key +
    // AES256_SIV keys / AES256_GCM values — kept identical between the create and the
    // post-reset rebuild so a recovered store is byte-for-byte the same kind of store.
    private fun createEncryptedPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    // Open the store, or — if the keyset/master key is unrecoverable — reset it and rebuild
    // ONCE. AEADBadTagException (a decrypt failure) is a GeneralSecurityException, so those
    // two catches cover the corrupted-keyset and invalidated-key cases. A recovery drops
    // every saved login AND this device's pairing identity, so the user must RE-PAIR and
    // re-add logins after one — the deliberate, documented cost of not bricking.
    private fun openOrResetPrefs(context: Context): SharedPreferences =
        try {
            createEncryptedPrefs(context)
        } catch (e: GeneralSecurityException) {
            resetAndRebuild(context, e)
        } catch (e: IOException) {
            resetAndRebuild(context, e)
        }

    // Delete the corrupted prefs file and the master-key alias from the AndroidKeyStore,
    // then rebuild. If the rebuild ALSO fails, fail loud with a clear cause rather than
    // looping — a keystore that cannot even be reset is a real device fault, not corruption.
    private fun resetAndRebuild(context: Context, cause: Exception): SharedPreferences {
        runCatching { context.deleteSharedPreferences(PREFS_FILE) }
        runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                .deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
        }
        return try {
            createEncryptedPrefs(context)
        } catch (e: Exception) {
            throw IllegalStateException(
                "Auto Login could not open OR reset its secure store; the device keystore is unusable.",
                e,
            )
        }
    }

    init {
        migrateSiteKeys()
    }

    /// Repair rows older builds keyed by the RAW typed string ("www.chase.com",
    /// "https://chase.com/login") instead of the normalized domain a server ping is
    /// matched against — every such row looked saved on Home but could never serve a
    /// login (this hit EVERY save made while the catalog fetch was down). Runs on
    /// every construction; a clean index is a no-op. When a raw row duplicates an
    /// already-normalized one, the normalized row — the only one lookup() could ever
    /// have served — is kept and the unreachable duplicate is deleted.
    private fun migrateSiteKeys() {
        val stored = sites()
        val plan = planSiteKeyMigration(stored)
        if (plan.moves.isEmpty() && plan.drops.isEmpty()) return
        val e = prefs.edit()
        for ((from, to) in plan.moves) {
            prefs.getString(from, null)?.let { e.putString(to, it) }
            e.remove(from)
        }
        for (key in plan.drops) e.remove(key)
        val rekeyed = stored.map { key -> normalizeSiteKey(key).ifEmpty { key } }.distinct()
        e.putString(K_SITE_INDEX, JSONArray(rekeyed).toString())
        e.apply()
    }

    // Reserved keys for this device's identity (never a real site host).
    private companion object {
        // The encrypted prefs file — the single source of truth shared by createEncryptedPrefs
        // and the recovery reset, so the file that is opened is exactly the file that is
        // deleted (§4e). The master-key alias lives in MasterKey.DEFAULT_MASTER_KEY_ALIAS.
        const val PREFS_FILE = "rindler_custody_secrets"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"

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

        // Email auto-read opt-in + the DURABLE on-device mailboxes. K_EMAIL_MAILBOXES is a
        // JSON array of {address, host, appPassword, needsAttention} — each app-password is a
        // durable mailbox credential, encrypted at rest like a saved site password, that NEVER
        // transits the server (the Go core dials IMAP directly; only the extracted code is
        // relayed). Keyed by ADDRESS so multiple mailboxes coexist. K_EMAIL_AUTOREAD is the
        // global opt-in the reader gates on; set on the first link, cleared when the last
        // mailbox is removed. Wiped wholesale by reset()/signOut() (they are secrets).
        const val K_EMAIL_AUTOREAD = "rindler-meta:email-autoread"
        const val K_EMAIL_MAILBOXES = "rindler-meta:email-mailboxes"

        // Post-pairing setup checklist state. Both are EDUCATION state about the human,
        // not account state: once someone has been walked through the reliability
        // switches, re-showing the interstitial (or the Home nudge) on every re-sign-in is
        // exactly the nag that makes people distrust the app. signOut() therefore
        // preserves both alongside K_ONBOARDED; only reset() ("Reset device") wipes them.
        const val K_SETUP_SEEN = "rindler-meta:setup-seen"
        const val K_SETUP_NUDGE_DISMISSED = "rindler-meta:setup-nudge-dismissed"
        const val K_HUB_URL = "rindler-meta:hub-url"        // the hub this device pairs + connects to

        // Device-egress proxy opt-in + the durable egress token. When ON, the paired
        // device runs a tunnel egress so the user's OWN agent sessions exit through THIS
        // device's IP. The token is a per-user tunnel token minted server-side; the
        // gateway is the tunnel wss:// endpoint. clear()/reset() wipes it.
        const val K_EGRESS_ENABLED = "rindler-meta:egress-enabled"
        const val K_EGRESS_TOKEN = "rindler-meta:egress-token"     // rt_live_ per-user tunnel token
        const val K_EGRESS_GATEWAY = "rindler-meta:egress-gateway" // wss:// tunnel gateway URL

        // Rindler account identity, captured at sign-in enroll completion (from Clerk)
        // and shown by AccountHeader. Not credentials — just who this device is linked
        // as. Written once at sign-in; wiped by reset()/clear() on sign-out.
        const val K_ACCOUNT_EMAIL = "rindler-meta:account-email"
        const val K_ACCOUNT_AVATAR = "rindler-meta:account-avatar" // Google avatar URL
    }

    // --- Rindler account identity (drives AccountHeader; Phase C wires the writes) ---

    /// The signed-in Rindler account email, or null if not stored (legacy pairing).
    fun accountEmail(): String? = prefs.getString(K_ACCOUNT_EMAIL, null)?.takeIf { it.isNotBlank() }

    /// The signed-in account's avatar URL (Google photo), or null. Coil renders it in
    /// AccountHeader; a null falls back to initials.
    fun avatarUrl(): String? = prefs.getString(K_ACCOUNT_AVATAR, null)?.takeIf { it.isNotBlank() }

    /// Persist (or clear, on null/blank) the account email. Cleared wholesale by reset().
    fun setAccountEmail(email: String?) {
        prefs.edit().apply {
            if (email.isNullOrBlank()) remove(K_ACCOUNT_EMAIL) else putString(K_ACCOUNT_EMAIL, email.trim())
        }.apply()
    }

    /// Persist (or clear, on null/blank) the avatar URL. Cleared wholesale by reset().
    fun setAvatarUrl(url: String?) {
        prefs.edit().apply {
            if (url.isNullOrBlank()) remove(K_ACCOUNT_AVATAR) else putString(K_ACCOUNT_AVATAR, url.trim())
        }.apply()
    }

    // --- hub URL (which hub this device pairs + connects to) ---

    /// The hub WebSocket URL this device last SUCCESSFULLY paired against, or null
    /// if never paired. A release APK ships with a BuildConfig.HUB_URL default (a real
    /// hub for a branded build, or a placeholder for a self-host build); the Advanced
    /// screen lets the user pair against their own, and the stored value wins from
    /// then on so the relay reconnects to the SAME hub that minted the pairing token.
    /// Written ONLY by saveIdentity() — atomically with a successful pairing — so a
    /// failed or hostile pairing attempt can never re-point the relay (or any later
    /// bearer-token call) at a server this device never paired with. Not a secret —
    /// just a hostname — but kept in the same store so "Reset device" clears it too.
    fun hubUrl(): String? = prefs.getString(K_HUB_URL, null)?.takeIf { it.isNotBlank() }

    // --- SMS auto-read opt-in (the user's choice; no OS permission is involved) ---

    /// Whether the user turned ON "fill in codes from a text". This is only the user's
    /// INTENT to arm the SMS User Consent listener (SmsAutoRead); even armed, the app
    /// sees a message only if the user allows Android's per-message consent prompt.
    /// Default OFF — nothing watches for a text until asked.
    fun isSmsAutoReadEnabled(): Boolean = prefs.getBoolean(K_SMS_AUTOREAD, false)

    fun setSmsAutoReadEnabled(on: Boolean) {
        prefs.edit().putBoolean(K_SMS_AUTOREAD, on).apply()
    }

    // --- Email auto-read opt-in + durable on-device mailboxes (never leave the device) ---

    /// Whether email auto-read is on. Set true on the first linkEmail, cleared when the last
    /// mailbox is unlinked. The reader polls only when this is on AND a mailbox is linked AND
    /// a verified email_otp_code ping opened the window (fail-closed, three gates).
    fun isEmailAutoReadEnabled(): Boolean = prefs.getBoolean(K_EMAIL_AUTOREAD, false)

    fun setEmailAutoReadEnabled(on: Boolean) {
        prefs.edit().putBoolean(K_EMAIL_AUTOREAD, on).apply()
    }

    /// Every linked mailbox (durable on-device credentials). Empty when none / unreadable.
    fun linkedEmails(): List<EmailMailbox> {
        val raw = prefs.getString(K_EMAIL_MAILBOXES, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val address = o.optString("address").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val host = o.optString("host").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val appPassword = o.optString("appPassword").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                EmailMailbox(address, host, appPassword, o.optBoolean("needsAttention", false))
            }
        }.getOrDefault(emptyList())
    }

    /// The linked addresses only (for username autocomplete). Addresses, NEVER a secret.
    fun linkedEmailAddresses(): List<String> = linkedEmails().map { it.address }

    /// True once at least one mailbox is linked.
    fun isEmailLinked(): Boolean = linkedEmails().isNotEmpty()

    /// The mailbox linked under [address] (case/space-insensitive), or null.
    fun emailMailbox(address: String): EmailMailbox? {
        val key = normalizeEmail(address)
        return linkedEmails().firstOrNull { normalizeEmail(it.address) == key }
    }

    /// Any mailbox currently broken (a revoked/wrong app-password) — drives the warning badge.
    fun emailNeedsAttention(): Boolean = linkedEmails().any { it.needsAttention }

    /// The addresses of the broken mailboxes (for the badge text).
    fun brokenEmailAddresses(): List<String> = linkedEmails().filter { it.needsAttention }.map { it.address }

    /// Add or replace a mailbox, keyed by ADDRESS (never a single fixed slot — that would
    /// wrongly overwrite the other mailboxes once plurality exists). A re-link of a broken
    /// mailbox clears its needsAttention. Turns email auto-read on. One commit.
    fun linkEmail(address: String, host: String, appPassword: String) {
        val addr = normalizeEmail(address)
        val updated = linkedEmails().filterNot { normalizeEmail(it.address) == addr } +
            EmailMailbox(addr, host.trim(), appPassword, needsAttention = false)
        prefs.edit()
            .putString(K_EMAIL_MAILBOXES, mailboxesToJson(updated))
            .putBoolean(K_EMAIL_AUTOREAD, true)
            .apply()
    }

    /// Remove the mailbox under [address]. Its app-password is erased from the encrypted
    /// store; there is nothing left to dial for it. When the last mailbox goes, email
    /// auto-read is turned off. The user should also revoke the app-password in their
    /// provider settings — removing it here stops THIS device using it.
    fun unlinkEmail(address: String) {
        val addr = normalizeEmail(address)
        val updated = linkedEmails().filterNot { normalizeEmail(it.address) == addr }
        prefs.edit().apply {
            putString(K_EMAIL_MAILBOXES, mailboxesToJson(updated))
            if (updated.isEmpty()) putBoolean(K_EMAIL_AUTOREAD, false)
        }.apply()
    }

    /// Flag [address] as broken (an IMAP auth failure). Returns true IFF it flipped from
    /// healthy to broken, so the caller fires the one-shot "mailbox stopped working" notice
    /// exactly once per breakage — never on every poll tick.
    fun markEmailNeedsAttention(address: String): Boolean {
        val addr = normalizeEmail(address)
        val list = linkedEmails()
        val idx = list.indexOfFirst { normalizeEmail(it.address) == addr }
        if (idx < 0 || list[idx].needsAttention) return false
        val updated = list.toMutableList().also { it[idx] = it[idx].copy(needsAttention = true) }
        prefs.edit().putString(K_EMAIL_MAILBOXES, mailboxesToJson(updated)).apply()
        return true
    }

    /// Clear the broken flag on [address] (a successful read, or a re-link). No-op if healthy.
    fun clearEmailNeedsAttention(address: String) {
        val addr = normalizeEmail(address)
        val list = linkedEmails()
        val idx = list.indexOfFirst { normalizeEmail(it.address) == addr }
        if (idx < 0 || !list[idx].needsAttention) return
        val updated = list.toMutableList().also { it[idx] = it[idx].copy(needsAttention = false) }
        prefs.edit().putString(K_EMAIL_MAILBOXES, mailboxesToJson(updated)).apply()
    }

    private fun mailboxesToJson(list: List<EmailMailbox>): String {
        val arr = JSONArray()
        for (m in list) {
            arr.put(
                JSONObject()
                    .put("address", m.address)
                    .put("host", m.host)
                    .put("appPassword", m.appPassword)
                    .put("needsAttention", m.needsAttention),
            )
        }
        return arr.toString()
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

    // --- post-pairing setup checklist (the "Finish setting up" interstitial + Home nudge) ---

    /// Whether the setup checklist has been shown once (by any exit: Done, "Not now", back).
    /// Every post-pairing route to Home consults this, so the interstitial can never loop.
    fun isSetupSeen(): Boolean = prefs.getBoolean(K_SETUP_SEEN, false)

    fun setSetupSeen() {
        prefs.edit().putBoolean(K_SETUP_SEEN, true).apply()
    }

    /// Whether the user pressed "Don't remind me" on the checklist reached from Home.
    /// Once set, the quiet Home nudge row is gone for good on this device.
    fun isSetupNudgeDismissed(): Boolean = prefs.getBoolean(K_SETUP_NUDGE_DISMISSED, false)

    fun setSetupNudgeDismissed() {
        prefs.edit().putBoolean(K_SETUP_NUDGE_DISMISSED, true).apply()
    }

    /// Wipe everything on this device (identity — token + device key + the server's
    /// ping-signing pubkey — plus all credentials + onboarding). clear() drops every
    /// key in this store, so the server pubkey goes with it: no stale key survives a
    /// device that is no longer paired. Used by "Reset device"; the next launch starts
    /// fresh at the intro. Mirrors iOS's Keychain.resetAll().
    fun reset() {
        prefs.edit().clear().apply()
    }

    /// Sign out: unlink this phone from the Rindler account. Wipes local identity
    /// (device token + key + server ping-signing pubkey), every saved login, the egress
    /// token, and the account email/avatar — but KEEPS the onboarding flag so the next
    /// launch lands on the Sign-in (Pair) screen, not the intro. The relay must be stopped
    /// by the caller first (it holds a live reference).
    ///
    /// PHASE-0 HOOK — server-side device revoke: before wiping, a later phase POSTs the
    /// current deviceToken() to the hub's revoke endpoint (/devices/revoke) so the server
    /// invalidates the token too (defense in depth; today the local wipe is sufficient to
    /// stop this device releasing anything). Capture deviceToken() ABOVE this call when
    /// wiring it, since signOut() clears it. The 30-day-inactivity unlink lands on this
    /// same code path when the relay reports the device revoked.
    fun signOut() {
        val onboarded = isOnboarded()
        // Education state, NOT account state: someone who has already been through the
        // intro and the setup checklist must not be re-taught them just because they
        // signed back in. reset() ("Reset device") is the one path that wipes these.
        val setupSeen = isSetupSeen()
        val nudgeDismissed = isSetupNudgeDismissed()
        prefs.edit().clear().apply()
        if (onboarded) setOnboarded()
        if (setupSeen) setSetupSeen()
        if (nudgeDismissed) setSetupNudgeDismissed()
    }

    /// Called by the Go core per approved ping. Returns the site's credential JSON
    /// (the contract above) or "" if we hold nothing for it.
    override fun lookup(site: String): String {
        // Credentials are stored as the ready-to-parse JSON string, keyed by the
        // NORMALIZED site (see SiteKey.kt). Try the ping's exact string first, then
        // its normalized form, so a ping phrased as "www.chase.com" still resolves
        // the row stored under "chase.com".
        prefs.getString(site, null)?.let { return it }
        val key = normalizeSiteKey(site)
        if (key.isEmpty() || key == site) return ""
        return prefs.getString(key, "") ?: ""
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
    /// device's private key, the server's ping-signing public key, AND the hub the
    /// pairing succeeded against. All are load-bearing — a token saved without the
    /// server pubkey yields a device that declines every credential release, and the
    /// hub committed here (nowhere else) is what keeps a failed pairing from ever
    /// re-pointing the relay at a server this device did not pair with.
    fun saveIdentity(token: String, deviceKeyB64: String, serverPubkeyB64: String, hubUrl: String) {
        prefs.edit()
            .putString(K_DEVICE_TOKEN, token)
            .putString(K_DEVICE_KEY, deviceKeyB64)
            .putString(K_SERVER_PUBKEY, serverPubkeyB64)
            .putString(K_HUB_URL, hubUrl.trim())
            .apply()
    }

    // --- enrollment (writes) — native side owns this; Go never writes ---

    /// Persist a credential record for a site (the JSON contract above), driven by
    /// the EnrollScreen. The row is keyed by the NORMALIZED site — the exact string
    /// a server ping is matched against — never the raw typed text, which would
    /// create a row lookup() can never serve ("www.chase.com" vs a ping for
    /// "chase.com"). The key is added to the index so HOME can list it.
    fun enroll(site: String, json: String) {
        val key = normalizeSiteKey(site).ifEmpty { site.trim() }
        val updated = (sites() + key).distinct()
        prefs.edit()
            .putString(key, json)
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

/**
 * The truthful relay connection state AccountHeader shows on its status line (the
 * Switch reflects the *requested* state instead — the switch must never lie).
 *
 * Baseline mapping today is enabled/paused only; OFFLINE_RETRYING and ACTION_NEEDED
 * are wired in Phase C once the daemon surfaces socket liveness / errors.
 */
enum class ConnectionStatus { CONNECTED, CONNECTING, PAUSED, OFFLINE_RETRYING, ACTION_NEEDED }

/**
 * Derive the header status from the service enabled/paused state. While a toggle is in
 * flight (requested ≠ actual) we show CONNECTING; else enabled → CONNECTED, disabled →
 * PAUSED. Socket-liveness (OFFLINE_RETRYING) and error (ACTION_NEEDED) states are layered
 * on in Phase C when the relay exposes them.
 */
fun deriveConnectionStatus(enabled: Boolean, toggleInFlight: Boolean): ConnectionStatus =
    when {
        toggleInFlight -> ConnectionStatus.CONNECTING
        enabled -> ConnectionStatus.CONNECTED
        else -> ConnectionStatus.PAUSED
    }
