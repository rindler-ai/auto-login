// PairingView — first-run device enrollment (redesigned).
//
// The user mints a single-use pairing code at your hub → Settings →
// Devices → Add device, and types it here. This view generates a device key
// (private half stays in the Keychain forever), sends only the PUBLIC half to
// the hub, and stores the returned long-lived device token in the Keychain, then
// calls onPaired() so the parent flips to the status/home screen.
//
// Ported from the Compose PairScreen.kt:
// a hero-centered layout (72pt emerald-tinted shield → title → instruction →
// monospace code field → animated error row → CTA → trust footer). Same pairing
// logic as the Android shell; native layout + friendly error mapping.
//
// Flow (all gomobile-bound C functions with an NSError** out-param, not Swift
// `throws` — see the call sites below for the bridging pattern):
//   MobileGenerateDeviceKey()      -> base64 Ed25519 private key   (store in Keychain)
//   MobileDevicePublicKey(keyB64)  -> base64 public half           (sent when pairing)
//   MobilePair(pairURL, code, name, platform, pubB64, androidID) -> MobilePairResult
//        .deviceToken   long-lived hub bearer token  (store in Keychain)
//        .serverPubkey  the server's Ed25519 ping-signing PUBLIC key (store too!)
//
// Both halves of that result are load-bearing: the token authenticates the
// device TO the hub, and serverPubkey authenticates the hub's pings TO the device —
// the Go core verifies every SecretPing against it before sealing a credential, so a
// device that persisted only the token declines every login.

import SwiftUI
import Custody
#if os(iOS)
import UIKit
#endif

// Derived from the hub host: wss://host/v1/devices/connect -> https://host/devices/pair/complete
private let pairURL = Config.pairURL

struct PairingView: View {
    /// Called after a successful pair so the parent can flip to the status screen.
    var onPaired: () -> Void

    @State private var code = ""
    @State private var busy = false
    @State private var error: String?

    #if os(macOS)
    private let platform = "macos"
    #else
    private let platform = "ios"
    #endif

    // A human-friendly device name for the pairing record (Android sends
    // "$MANUFACTURER $MODEL"; iOS/macOS use the OS's own device name).
    #if os(iOS)
    private var deviceName: String { UIDevice.current.name }
    #elseif os(macOS)
    private var deviceName: String { Host.current().localizedName ?? "Mac" }
    #else
    private var deviceName: String { "Auto Login" }
    #endif

    private var trimmedCode: String {
        code.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    var body: some View {
        VStack(spacing: 0) {
            // Top flexible spacer. Paired with the bottom spacer + pinned footer,
            // this sits the content block slightly above center (no dead lower half).
            Spacer()

            // Hero: 72pt circle, primary@13%, 36pt shield. Bigger than IconChip's
            // 40pt default, so it's built inline rather than reusing the shared chip.
            Circle()
                .fill(AutoLoginColors.primary.opacity(0.13))
                .frame(width: 72, height: 72)
                .overlay(
                    Image(systemName: "shield.fill")
                        .font(.system(size: 36))
                        .foregroundStyle(AutoLoginColors.primary)
                )

            Spacer().frame(height: 28)

            Text("Pair this device")
                .autoLoginText(.headlineMedium)
                .foregroundStyle(AutoLoginColors.onBackground)

            Spacer().frame(height: 12)

            Text("In your hub's console, open Settings → Devices, tap Add device, and enter the code shown there.")
                .autoLoginText(.bodyMedium)
                .foregroundStyle(AutoLoginColors.onSurfaceVariant)
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity, alignment: .center)
                .fixedSize(horizontal: false, vertical: true)

            Spacer().frame(height: 36)

            // Monospace so a secret-shaped code reads intentionally (0/O, 1/l legible).
            // Field stays EDITABLE while busy (the CTA's loading state guards re-tap).
            AppTextField("Pairing code", text: $code, isError: error != nil, font: AutoLoginType.mono)

            // Animates in below the field only when there's an error.
            if let error {
                ErrorRow(message: error)
                    .transition(.opacity)
            }

            Spacer().frame(height: 28)

            PrimaryButton("Pair", enabled: !trimmedCode.isEmpty, loading: busy) { pair() }

            // Bottom flexible spacer, then the trust line anchored to the bottom edge.
            Spacer()

            TrustFooter()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(.horizontal, 28)
        .background(AutoLoginColors.background)
        .animation(.easeInOut(duration: 0.15), value: error)
        // Live-clear the error on any edit (Android does `error = null` per keystroke).
        .onChange(of: code) { _ in error = nil }
    }

    private func pair() {
        busy = true; error = nil
        // Snapshot the main-actor-derived args before hopping off-thread.
        let submittedCode = trimmedCode
        let name = deviceName
        let plat = platform
        // Off the main thread: MobilePair does a blocking network POST.
        Task.detached {
            do {
                // Mobile* are gomobile-bound C functions (NSError** out-param, not
                // Swift `throws`) — check `err` after each call and rethrow it so the
                // catch below can map it to a friendly message.
                var err: NSError?
                let keyB64 = MobileGenerateDeviceKey(&err)
                if let err { throw err }
                let pubB64 = MobileDevicePublicKey(keyB64, &err)
                if let err { throw err }
                // TODO(#4564): send the durable device fingerprint seed here in the same
                // android_id field — on iOS/macOS this should be
                // UIDevice.current.identifierForVendor?.uuidString (iOS) or an
                // equivalent stable per-vendor id, read fresh at pair and never
                // persisted. "" degrades the server to pubkey-only dedup for now.
                let result = MobilePair(pairURL, submittedCode, name, plat, pubB64, "", &err)
                if let err { throw err }
                guard let result else { throw pairFailed }

                // Persist identity: private key + token + the server's ping-signing
                // pubkey, all device-only. (Android's single
                // store.saveIdentity(token, keyB64, serverPubkey) maps to these three
                // writes.) The server pubkey is what lets the Go core verify a ping's
                // worker key before sealing to it — without it, every login is
                // declined, so it is saved on the same path as the token, never later.
                Keychain.saveDeviceKey(keyB64)
                Keychain.saveDeviceToken(result.deviceToken)
                Keychain.saveServerPubkey(result.serverPubkey)

                await MainActor.run { busy = false; onPaired() }
            } catch {
                await MainActor.run {
                    busy = false
                    self.error = friendlyPairError(error.localizedDescription)
                }
            }
        }
    }
}

// MARK: - ErrorRow (private)

/// A small inline error line under the field: a 16pt exclamation-circle icon +
/// message, both in the error color. Private to the pair screen.
private struct ErrorRow: View {
    let message: String

    var body: some View {
        HStack(spacing: 6) {
            Image(systemName: "exclamationmark.circle")
                .font(.system(size: 16))
                .foregroundStyle(AutoLoginColors.error)
            Text(message)
                .autoLoginText(.bodyMedium)
                .foregroundStyle(AutoLoginColors.error)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.top, 12)
    }
}

// MARK: - Errors

/// gomobile returns (result, error); a nil result with no error should be
/// impossible, but Swift must still take a branch, so it maps to the generic
/// "couldn't pair" message rather than force-unwrapping.
private let pairFailed = NSError(
    domain: "ai.rindler.autologin", code: 1,
    userInfo: [NSLocalizedDescriptionKey: "Pairing returned no result."]
)

// MARK: - Friendly error mapping

/// Turn the raw gomobile error string (the Go error text, incl. any HTTP "401")
/// into a calm, actionable message. Ported verbatim from Android's
/// friendlyPairError — turns a generic "Pairing failed: …" into actionable text.
private func friendlyPairError(_ raw: String?) -> String {
    switch raw {
    case .none:
        return "Something went wrong. Try again."
    case let .some(msg) where msg.contains("could not verify the hub's identity"):
        // Pairing-channel TOFU (follow-up): the server key at pair/complete
        // did not match the fingerprint in the code — a possible on-path MITM.
        return "This device couldn't verify the hub's identity. You may be on an untrusted network. Try again from a trusted connection."
    case let .some(msg) where msg.contains("401") || msg.contains("invalid"):
        return "That code didn't work. It may have expired, so generate a new one."
    case let .some(msg) where msg.contains("timeout") || msg.contains("connect"):
        return "Couldn't reach the hub. Check your connection and retry."
    default:
        return "Couldn't pair. Generate a fresh code and try again."
    }
}
