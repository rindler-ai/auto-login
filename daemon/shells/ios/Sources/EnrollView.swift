// EnrollView — the "Add a login" form, ported from the Compose `ui/EnrollScreen.kt`.
// This is the ONLY screen that WRITES a
// credential: it captures site + username + password, builds
// the credential-JSON contract, and hands it to the native `KeychainSecretSource.enroll`
// (the Go core never writes). On save it calls `onDone()`, which the nav shell also wires
// to the back arrow.
//
// One deliberate divergence from the redesign (recorded in PARITY.md):
//   • CATALOG STRIPPED — Android's live site-catalog autocomplete (CatalogClient /
//     fetchSupportedSites / SupportedSite, the suggestion dropdown, the six catalog-only
//     supportingText strings, and the isSupported-gated saveEnabled) is Android-only and
//     dropped. iOS ships a PLAIN Website field: no suggestions, no supporting text, and
//     saveEnabled = site & username non-empty (trimmed) — exactly Android's catalog-failed
//     fallback branch, with the enroll key = site.trim().
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
    @State private var pwVisible = false

    /// Android's catalog-failed fallback rule, used as iOS's ONLY rule: site + username
    /// non-blank (both trimmed). Password is NOT required.
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

        // Credential-JSON contract (see PARITY.md): {username, password}. Password
        // is stored raw/untrimmed.
        let dict: [String: Any] = ["username": usernameTrimmed, "password": password]
        if let data = try? JSONSerialization.data(withJSONObject: dict),
           let json = String(data: data, encoding: .utf8) {
            // enroll key = site.trim() (Android's catalog-failed fallback branch).
            KeychainSecretSource().enroll(site: siteTrimmed, json: json)
        }
        onDone()
    }
}
