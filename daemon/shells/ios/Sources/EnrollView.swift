// EnrollView — the "Add a login" form, ported from the Compose `ui/EnrollScreen.kt`.
// This is the ONLY screen that WRITES a
// credential: it captures site + username + password (+ an iOS-kept TOTP secret), builds
// the credential-JSON contract, and hands it to the native `KeychainSecretSource.enroll`
// (the Go core never writes). On save it calls `onDone()`, which the nav shell also wires
// to the back arrow.
//
// Two deliberate divergences from the redesign (both recorded in PARITY.md):
//   • CATALOG STRIPPED — Android's live site-catalog autocomplete (CatalogClient /
//     fetchSupportedSites / SupportedSite, the suggestion dropdown, the six catalog-only
//     supportingText strings, and the isSupported-gated saveEnabled) is Android-only and
//     dropped. iOS ships a PLAIN Website field: no suggestions, no supporting text, and
//     saveEnabled = site & username non-empty (trimmed) — exactly Android's catalog-failed
//     fallback branch, with the enroll key = site.trim().
//   • TOTP RETAINED — the redesign has no TOTP field; the existing iOS shell already
//     captures + validates a TOTP secret and the credential-JSON contract supports it, so
//     iOS KEEPS it as an intentional superset (field + base32DecodeToBase64 + the
//     {username,password,totp:{Secret,Digits,Period,Algorithm}} object).
//
// Pure SwiftUI over the shared Theme.swift / Components.swift tokens; compiles for BOTH
// iOS (WindowGroup) and macOS (MenuBarExtra) — no UIKit-only APIs. Navigation is wired by
// the nav shell via the `onDone` callback; this view never routes itself.

import SwiftUI
import Foundation

struct EnrollView: View {
    /// Invoked for BOTH the back arrow and a successful save — the nav shell re-reads the
    /// stored-logins list and returns Home.
    var onDone: () -> Void

    @State private var site = ""
    @State private var username = ""
    @State private var password = ""
    @State private var totp = ""
    @State private var pwVisible = false
    @State private var saveError: String?

    /// Android's catalog-failed fallback rule, used as iOS's ONLY rule: site + username
    /// non-blank (both trimmed). Password is NOT required; TOTP is optional.
    private var saveEnabled: Bool {
        !site.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && !username.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                TopBar("Add a login", onBack: onDone)

                Spacer().frame(height: 8)

                Text("Stored encrypted on this phone. When one of your logins needs it, the requested credential is released automatically, end-to-end encrypted for that login.")
                    .autoLoginText(.bodyMedium)
                    .foregroundStyle(AutoLoginColors.onSurfaceVariant)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .fixedSize(horizontal: false, vertical: true)

                Spacer().frame(height: 24)

                AppTextField("Website", text: $site)

                Spacer().frame(height: 14)

                AppTextField("Username or email", text: $username)

                Spacer().frame(height: 14)

                AppTextField("Password", text: $password, secure: !pwVisible) {
                    Button {
                        pwVisible.toggle()
                    } label: {
                        Image(systemName: pwVisible ? "eye.slash" : "eye")
                            .font(.system(size: 18))
                            .foregroundStyle(AutoLoginColors.onSurfaceVariant)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(pwVisible ? "Hide password" : "Show password")
                }

                Spacer().frame(height: 14)

                // iOS-kept superset (not in the redesign): an optional TOTP secret.
                // Its supporting caption doubles as the inline validation error.
                AppTextField(
                    "TOTP secret (optional)",
                    text: $totp,
                    isError: saveError != nil,
                    font: AutoLoginType.mono,
                    supportingText: saveError
                )
                .onChange(of: totp) { _ in saveError = nil }

                Spacer().frame(height: 28)

                PrimaryButton("Save to this device", enabled: saveEnabled) {
                    save()
                }

                Spacer().frame(height: 12)

                // Bespoke inline footer (NOT the shared TrustFooter): left-aligned lock line.
                HStack(spacing: 6) {
                    Image(systemName: "lock.fill")
                        .font(.system(size: 14))
                        .foregroundStyle(AutoLoginColors.onSurfaceVariant)
                    Text("Encrypted with this phone's secure hardware")
                        .autoLoginText(.labelMedium)
                        .foregroundStyle(AutoLoginColors.onSurfaceVariant)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 24)
        }
    }

    private func save() {
        let siteTrimmed = site.trimmingCharacters(in: .whitespacesAndNewlines)
        let usernameTrimmed = username.trimmingCharacters(in: .whitespacesAndNewlines)
        let totpTrimmed = totp.trimmingCharacters(in: .whitespacesAndNewlines)

        // Validate TOTP if provided: a non-empty field must decode successfully.
        if !totpTrimmed.isEmpty && base32DecodeToBase64(totpTrimmed) == nil {
            saveError = "Couldn't read that TOTP secret — check for typos or extra spaces."
            return
        }

        // Credential-JSON contract (see PARITY.md): {username, password[, totp]}. Password
        // is stored raw/untrimmed; the raw base32 TOTP secret is decoded to raw bytes then
        // base64'd before storage. TOTP omitted when blank.
        var dict: [String: Any] = ["username": usernameTrimmed, "password": password]
        if !totpTrimmed.isEmpty, let seed = base32DecodeToBase64(totpTrimmed) {
            dict["totp"] = ["Secret": seed, "Digits": 6, "Period": 30, "Algorithm": "SHA1"]
        }
        if let data = try? JSONSerialization.data(withJSONObject: dict),
           let json = String(data: data, encoding: .utf8) {
            // enroll key = site.trim() (Android's catalog-failed fallback branch).
            KeychainSecretSource().enroll(site: siteTrimmed, json: json)
        }
        onDone()
    }
}

/// RFC 4648 base32 decode -> raw bytes -> base64.
/// A TOTP secret is stored as base64 of the RAW base32-decoded seed, per shells/PARITY.md.
func base32DecodeToBase64(_ s: String) -> String? {
    let alphabet = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZ234567")
    let map = Dictionary(uniqueKeysWithValues: alphabet.enumerated().map { ($1, $0) })
    var bits = 0, value = 0
    var out = [UInt8]()
    for c in s.uppercased() where c != "=" {
        guard let v = map[c] else { return nil }
        value = (value << 5) | v; bits += 5
        if bits >= 8 { out.append(UInt8((value >> (bits - 8)) & 0xFF)); bits -= 8 }
    }
    return Data(out).base64EncodedString()
}
