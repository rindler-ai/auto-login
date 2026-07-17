// ForwardMessageIntent — the iOS SMS-2FA Shortcut-relay action.
//
// This is the POST action a user's own iOS Shortcuts "Message" automation
// calls, passing the incoming text as `message`. It is a genuine App Intent
// (not a distributed `.shortcut` file), so it appears directly in the
// Shortcuts app's action list under "Auto-Login" and the user's automation can
// add it with no "Allow Untrusted Shortcuts" gate.
//
// APP-STORE-LEGAL BOUNDARY: this file never reads SMS. The message text is
// handed to it by the user's own Shortcut. Extracting the one-time code from
// that already-handed-over string is NOT "reading SMS" — no SMS-reading API is
// used here or anywhere else in this app. Do not add one.
//
// PRIVACY — only the code leaves the device, never the message body (parity with
// Android's sms/OtpDelivery.kt). perform() runs the SAME on-device extractor the
// Go core exports (MobileExtractOTPCode, gomobile-bound from
// mobile.ExtractOTPCode) on `message`. A body that yields no code — a normal
// text the automation's keyword filter swept in, a promo, an order update —
// extracts to "" and is DROPPED right here: the run succeeds silently, forwarded
// nowhere and logged nowhere. Only when a genuine code is found does anything
// leave the device, and then ONLY the code.
//
// TRANSPORT: the extracted code is POSTed to the DEVICE-authed
// `POST /devices/sms-relay/manual` (Config.manualSubmitURL), authenticated with
// this device's paired bearer token from the Keychain — the SAME endpoint + auth
// the manual-entry sheet (ManualCodeView) uses. This replaces an earlier webhook
// path that re-extracted server-side and would DROP a pre-extracted ALPHANUMERIC
// code (a mixed code requires a nearby verification cue that a bare code no
// longer carries). The manual endpoint does no re-extraction, so a pre-extracted
// numeric OR alphanumeric code delivers straight into the same rendezvous.
// CONSUME-AND-FORGET: the intent holds the message/code only for the lifetime of
// one POST and never logs it or the token — a failure surfaces as a thrown,
// descriptive error the Shortcut renders, never a diagnostic that echoes the
// secret payload.
//
// iOS-only: AppIntents backs the SMS relay (the Shortcuts "Message" automation
// doesn't exist on macOS). The macOS target shares this same Sources/ directory,
// so the whole file is gated out of that build.
//
// CONTRACT-PARITY NOTE (iOS mirrors the Android-proven path): the on-device
// extract → code-only → /devices/sms-relay/manual flow is proven end-to-end on
// Android (sms/OtpDelivery.kt + device-token auth). This Swift mirror is
// validated at the seams by the Go unit tests (mobile.ExtractOTPCode; the manual
// handler), but its COMPILATION + xcframework link are verifiable only on a Mac
// with Xcode (`make ios`, then xcodebuild). Build-verify on macOS/CI before
// shipping. If the generated header types `MobileExtractOTPCode`'s return as
// `String?`/`String!` rather than a non-optional `String` (gomobile has emitted
// both depending on version), change the extract line to:
//     guard let code = MobileExtractOTPCode(message), !code.isEmpty else { return .result() }

#if os(iOS)

import AppIntents
import Foundation
import Custody // MobileExtractOTPCode — the on-device OTP extractor

struct ForwardMessageIntent: AppIntent {
    static var title: LocalizedStringResource = "Forward message to Auto-Login"
    static var description = IntentDescription(
        "Extracts a login code from an incoming text on-device and sends only that code to your hub so it can complete a login."
    )

    @Parameter(title: "Message") var message: String

    func perform() async throws -> some IntentResult {
        // Extract the one-time code ON DEVICE. A non-code text (the common case —
        // the automation's keyword filter is a catch-all) yields "" and is
        // dropped: nothing leaves the device. Mirrors Android's OtpDelivery.forward.
        let code = MobileExtractOTPCode(message)
        guard !code.isEmpty else { return .result() }

        // A real code was found: deliver ONLY the code, authenticated as the
        // paired DEVICE — the same bearer token + endpoint ManualCodeView uses.
        guard let deviceToken = Keychain.deviceToken(), let url = URL(string: Config.manualSubmitURL) else {
            throw ForwardMessageError.notConfigured
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("Bearer \(deviceToken)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        do {
            request.httpBody = try JSONEncoder().encode(ManualCodePayload(code: code))
        } catch {
            // Encoding one plain string practically never fails, but a thrown
            // encoder error is still a "couldn't send it" outcome.
            throw ForwardMessageError.unreachable
        }

        let response: URLResponse
        do {
            (_, response) = try await URLSession.shared.data(for: request)
        } catch {
            throw ForwardMessageError.unreachable
        }

        guard let http = response as? HTTPURLResponse else {
            throw ForwardMessageError.unreachable
        }
        switch http.statusCode {
        case 200:
            // 200 covers both "delivered" and "no_pending_login": a code with no
            // waiting login is a harmless server-side no-op (mirrors Android
            // treating no_pending_login as nothing-more-to-do), so both succeed.
            return .result()
        case 401:
            throw ForwardMessageError.notPaired
        default:
            throw ForwardMessageError.unreachable
        }
    }
}

/// The POST body shape the manual-entry endpoint decodes.
private struct ManualCodePayload: Encodable {
    let code: String
}

/// Errors surfaced back through the Shortcut when the POST doesn't succeed.
/// Kept generic and free of any secret — Shortcuts renders `errorDescription`
/// as the run's failure message, so none of these may include the forwarded
/// text or the extracted code.
enum ForwardMessageError: LocalizedError {
    /// This device isn't paired (no device token), so it can't authenticate the
    /// delivery. Only reached once a real code was found and had nowhere to go.
    case notConfigured
    /// The server rejected the device token (401) — the pairing was revoked/rotated.
    case notPaired
    /// Any other non-2xx response, or a transport-level failure.
    case unreachable

    var errorDescription: String? {
        switch self {
        case .notConfigured:
            return "Auto-Login SMS auto-fill isn't set up yet. Open Auto-Login and finish setup in Settings."
        case .notPaired:
            return "This device's pairing is no longer valid. Reopen Auto-Login and re-pair."
        case .unreachable:
            return "Couldn't reach your hub. Check your connection and try again."
        }
    }
}

#endif
