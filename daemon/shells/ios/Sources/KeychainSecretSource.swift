// KeychainSecretSource — the native secure store behind the Go core.
//
// Conforms to `MobileSecretSourceProtocol` (gomobile-bound; the ObjC
// header also emits a same-named `MobileSecretSource` CLASS as the Go-interface
// wrapper, so Swift's Clang importer disambiguates the protocol with a
// `Protocol` suffix — see PARITY.md). The Go core calls `lookup(site)` for each
// approved ping and expects the credential record as a JSON STRING, or "" when
// the device holds nothing for the site:
//
//   {"username":"…","password":"…"}
//
// Go parses one record after approval, seals only the requested value to the
// login worker, and leaves the durable source of truth in this store.

import Foundation
import Security
import Custody // for the MobileSecretSourceProtocol protocol

// MARK: - Low-level Keychain helper (also used for the device token + key)

enum Keychain {
    // One generic-password service namespace for the whole app.
    static let service = "ai.rindler.autologin"

    // Reserved accounts for this device's identity (never a real site host).
    private static let acctDeviceToken = "rindler-meta:device-token"
    private static let acctDeviceKey   = "rindler-meta:device-key" // base64 Ed25519 private key

    // The SERVER's base64 Ed25519 PUBLIC key, handed to us once at pairing.
    // The Go core verifies every SecretPing against it before sealing anything: the
    // signature covers the worker's ephemeral key, so an on-path party that
    // substitutes its own recipient key is caught. Lose this and the device declines
    // every ping (fail-closed, by design — it must then re-pair). Public key only:
    // nothing here is a secret, but it IS integrity-critical, so it lives in the
    // Keychain with the identity rather than in UserDefaults.
    private static let acctServerPubkey = "rindler-meta:server-pubkey"

    // JSON array of enrolled site hosts (drives the HOME list). Mirrors Android's
    // K_SITE_INDEX; kept explicit because there is no reliable "list all" for our keys.
    private static let acctSiteIndex = "rindler-meta:sites"

    // "1" once the first-run intro has been seen. Mirrors Android's K_ONBOARDED.
    private static let acctOnboarded = "rindler-meta:onboarded"

    // The per-user SMS-relay webhook (URL + token), minted server-side by
    // `POST /v1/sms-relay/token` and stored here by the SMS-relay setup wizard.
    // `ForwardMessageIntent` reads this to know where/how to POST a forwarded
    // text. Holds no SMS content — just the destination + bearer token.
    private static let acctSMSWebhook = "rindler-meta:sms-webhook"

    /// Read a generic-password item's UTF-8 value, or nil if absent.
    static func read(account: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var out: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &out) == errSecSuccess,
              let data = out as? Data,
              let s = String(data: data, encoding: .utf8) else { return nil }
        return s
    }

    /// Upsert a generic-password item, marked device-only so it never syncs to
    /// iCloud or migrates to another device.
    @discardableResult
    static func write(account: String, value: String) -> Bool {
        let base: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        let data = Data(value.utf8)
        SecItemDelete(base as CFDictionary) // upsert = delete then add
        var add = base
        add[kSecValueData as String] = data
        add[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        return SecItemAdd(add as CFDictionary, nil) == errSecSuccess
    }

    /// Delete a generic-password item; treats "not found" as success (no-op).
    @discardableResult
    static func deleteItem(account: String) -> Bool {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        let status = SecItemDelete(query as CFDictionary)
        return status == errSecSuccess || status == errSecItemNotFound
    }

    // Convenience accessors for the paired identity.
    static func deviceToken() -> String? { read(account: acctDeviceToken) }
    static func deviceKeyB64() -> String? { read(account: acctDeviceKey) }

    /// The server's ping-signing PUBLIC key from pairing, or nil on a device paired
    /// before. `MobileStart` requires it — a device without one can verify no
    /// ping and must re-pair. (Mirrors Android's serverPubkeyB64().)
    static func serverPubkeyB64() -> String? { read(account: acctServerPubkey) }

    /// Store the Ed25519 private key (base64) with device-only, this-device-only
    /// access control (kSecAttrAccessibleWhenUnlockedThisDeviceOnly — no biometric
    /// gate, so the relay stays hands-free). NOTE: the Secure Enclave itself only
    /// stores its own P-256 keys, so an externally-generated Ed25519 key lives in
    /// the Keychain, not inside the Enclave.
    static func saveDeviceKey(_ b64: String) { write(account: acctDeviceKey, value: b64) }
    static func saveDeviceToken(_ token: String) { write(account: acctDeviceToken, value: token) }

    /// Persist the server's ping-signing public key from `MobilePair`. Must
    /// be saved with the token: a token without it yields a device that declines
    /// every credential release.
    static func saveServerPubkey(_ b64: String) { write(account: acctServerPubkey, value: b64) }

    // MARK: - Site index (drives the HOME "enrolled sites" list)

    /// The enrolled site hosts, or [] if the index is missing/corrupt.
    static func sites() -> [String] {
        guard let raw = read(account: acctSiteIndex),
              let data = raw.data(using: .utf8),
              let arr = try? JSONSerialization.jsonObject(with: data) as? [String] else { return [] }
        return arr
    }

    /// Add a site host to the index, deduping against an existing entry.
    static func addSite(_ site: String) {
        var s = sites()
        guard !s.contains(site) else { return }
        s.append(site)
        if let data = try? JSONSerialization.data(withJSONObject: s),
           let json = String(data: data, encoding: .utf8) {
            write(account: acctSiteIndex, value: json)
        }
    }

    /// Remove a site's stored credential and drop it from the index. The plaintext
    /// is erased; there is nothing left to relay for it. Mirrors Android's delete().
    static func delete(_ site: String) {
        deleteItem(account: site)
        let remaining = sites().filter { $0 != site }
        if let data = try? JSONSerialization.data(withJSONObject: remaining),
           let json = String(data: data, encoding: .utf8) {
            write(account: acctSiteIndex, value: json)
        }
    }

    // MARK: - Onboarding flag (drives the first-run intro)

    /// True once the intro has been seen. Mirrors Android's isOnboarded().
    static func isOnboarded() -> Bool { read(account: acctOnboarded) == "1" }

    /// Mark the intro as seen (device-only, via `write`). Mirrors setOnboarded().
    static func setOnboarded() { write(account: acctOnboarded, value: "1") }

    // MARK: - SMS-relay webhook (setup wizard writes; ForwardMessageIntent reads)

    /// Store the per-user SMS-relay webhook as JSON `{"url":...,"token":...}`
    /// (device-only, via `write`). Called by the setup wizard once it mints a
    /// token from `POST /v1/sms-relay/token`.
    static func saveSMSRelayWebhook(url: String, token: String) {
        let payload: [String: String] = ["url": url, "token": token]
        guard let data = try? JSONSerialization.data(withJSONObject: payload),
              let json = String(data: data, encoding: .utf8) else { return }
        write(account: acctSMSWebhook, value: json)
    }

    /// The stored webhook (URL + token), or nil if unset or malformed.
    /// `ForwardMessageIntent.perform()` reads this on every invocation.
    static func smsRelayWebhook() -> (url: String, token: String)? {
        guard let raw = read(account: acctSMSWebhook),
              let data = raw.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: String],
              let url = obj["url"], let token = obj["token"] else { return nil }
        return (url, token)
    }

    /// Remove the stored webhook (e.g. the wizard re-running, or a future
    /// "disable SMS auto-fill" action). `resetAll()` also calls this.
    static func deleteSMSRelayWebhook() {
        deleteItem(account: acctSMSWebhook)
    }

    // MARK: - Reset

    /// Wipe everything on this device — identity (token + device key + the server's
    /// ping-signing pubkey), every enrolled site credential, the site index, the
    /// onboarding flag, and the SMS-relay webhook — so the next launch starts fresh
    /// at the intro. Leaving the server pubkey behind would leave a stale key for a
    /// device that is no longer paired. Mirrors Android's Keystore.reset().
    static func resetAll() {
        for site in sites() { deleteItem(account: site) }
        deleteItem(account: acctDeviceToken)
        deleteItem(account: acctDeviceKey)
        deleteItem(account: acctServerPubkey)
        deleteItem(account: acctSiteIndex)
        deleteItem(account: acctOnboarded)
        deleteItem(account: acctSMSWebhook)
    }
}

// MARK: - MobileSecretSource

final class KeychainSecretSource: NSObject, MobileSecretSourceProtocol {
    /// Called by the Go core per approved ping. Returns the site's credential JSON
    /// (the contract above) or "" if we hold nothing for it.
    func lookup(_ site: String?) -> String {
        // Credentials are stored as the ready-to-parse JSON string, keyed by site.
        guard let site else { return "" }
        return Keychain.read(account: site) ?? ""
    }

    /// ListSites returns the domains the device holds a login for, as a JSON array
    /// string (e.g. `["a.com","b.com"]`) — gomobile cannot bind a []string return,
    /// so, like `lookup`, this returns a JSON string the Go core parses. Domains
    /// ONLY, never a secret. "[]" means the device holds nothing. Mirrors the
    /// Android shell's ListSites and satisfies the MobileSecretSource protocol
    /// requirement the Go core added (the iOS shell had not yet implemented it).
    func listSites() -> String {
        let sites = Keychain.sites()
        guard let data = try? JSONSerialization.data(withJSONObject: sites),
              let json = String(data: data, encoding: .utf8) else {
            return "[]"
        }
        return json
    }

    // MARK: enrollment (writes) — native side owns this; Go never writes.

    /// Persist a credential record for a site. Build `json` as the contract above.
    /// The "Add login" sheet (EnrollView) captures username/password and calls this.
    func enroll(site: String, json: String) {
        Keychain.write(account: site, value: json)
        Keychain.addSite(site)   // so HOME can list it (mirrors Android)
    }
}
