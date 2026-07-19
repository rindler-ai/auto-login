// ManualCodeView — the manual 2FA code entry sheet:
// the ESSENTIAL reliability fallback. SMS auto-fill (SMSRelaySetupView) is
// best-effort — if the Shortcuts automation isn't set up yet, or it ever misses a
// text, this sheet is how the user still gets a paused login moving: type the code,
// tap Submit, done. Reachable any time from Home (HomeView's "Enter a code"
// affordance) regardless of whether auto-fill is configured — the floor under the
// whole feature, not an edge case.
//
// The custody shell holds no web session, only a device bearer token, so this posts
// to the device-authed manual-entry route on the hub server (mounted alongside the
// device routes):
//
//   POST https://your-hub.example/devices/sms-relay/manual
//     Authorization: Bearer <deviceToken>
//     {"code":"<typed code>"}
//     -> 200 {"status":"delivered"}            the code reached a paused login
//     -> 200 {"status":"no_pending_login"}     no login is currently waiting
//     -> 400                                    malformed body / empty code
//     -> 401                                    missing/unknown/revoked device (no oracle)
//
// Manual and automated (SMS-relay webhook) codes converge on the EXACT SAME
// server-side DeliverOTP rendezvous — from the paused login's perspective there is
// no difference between the two paths.
//
// Pure UI over the shared Theme.swift / Components.swift tokens + Keychain. Never
// logs the typed code (CONSUME-AND-FORGET applies on both ends of this wire, client
// included).

import SwiftUI
#if os(iOS)
import UIKit
#endif

// Same host base as SMSRelaySetupView's device-authed routes.
private let manualSubmitURL = Config.manualSubmitURL

struct ManualCodeView: View {
    /// Dismisses the sheet. Fired by the back arrow and, after a short delay so
    /// "Sent ✅" is visible for a beat, on a successful delivery.
    var onDone: () -> Void

    private enum SubmitState {
        case idle
        case submitting
        case success
        case noPendingLogin
        case failed(String)
    }

    @State private var code = ""
    @State private var submitState: SubmitState = .idle
    @State private var dismissTask: Task<Void, Never>?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                TopBar("Enter code", onBack: onDone)

                Spacer().frame(height: 12)

                Text("Type the code your login is waiting for — Auto Login will submit it.")
                    .autoLoginText(.bodyMedium)
                    .foregroundStyle(AutoLoginColors.onSurfaceVariant)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .fixedSize(horizontal: false, vertical: true)

                Spacer().frame(height: 20)

                AppTextField("2FA code", text: $code)
                    #if os(iOS)
                    .keyboardType(.numberPad)
                    #endif

                Spacer().frame(height: 10)

                statusRow

                Spacer().frame(height: 20)

                PrimaryButton("Submit", enabled: !trimmedCode.isEmpty, loading: isSubmitting) {
                    submit()
                }

                Spacer().frame(height: 8)
                TrustFooter()
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 24)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(AutoLoginColors.background)
        .onDisappear { dismissTask?.cancel() }
    }

    private var trimmedCode: String {
        code.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var isSubmitting: Bool {
        if case .submitting = submitState { return true }
        return false
    }

    // MARK: - Status row

    @ViewBuilder
    private var statusRow: some View {
        switch submitState {
        case .idle, .submitting:
            EmptyView()
        case .success:
            inlineStatus(icon: "checkmark.circle.fill", text: "Sent ✅", tint: AutoLoginColors.primary)
        case .noPendingLogin:
            inlineStatus(
                icon: "exclamationmark.circle",
                text: "No login is waiting for a code right now.",
                tint: AutoLoginColors.onSurfaceVariant
            )
        case .failed(let message):
            inlineStatus(icon: "exclamationmark.triangle.fill", text: message, tint: AutoLoginColors.error)
        }
    }

    private func inlineStatus(icon: String, text: String, tint: Color) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: icon)
                .font(.system(size: 16))
                .foregroundStyle(tint)
            Text(text)
                .autoLoginText(.bodyMedium)
                .foregroundStyle(tint)
                .frame(maxWidth: .infinity, alignment: .leading)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    // MARK: - Submit

    private func submit() {
        let submitted = trimmedCode
        guard !submitted.isEmpty else { return }
        guard let deviceToken = Keychain.deviceToken() else {
            submitState = .failed("This device isn't paired anymore. Re-pair in Settings, then retry.")
            return
        }
        submitState = .submitting
        Task.detached {
            do {
                let status = try await Self.submitCode(submitted, deviceToken: deviceToken)
                await MainActor.run {
                    switch status {
                    case .delivered:
                        submitState = .success
                        scheduleDismiss()
                    case .noPendingLogin:
                        submitState = .noPendingLogin
                    }
                }
            } catch {
                await MainActor.run { submitState = .failed(Self.friendlyNetworkError(error)) }
            }
        }
    }

    /// Dismisses ~1s after a successful submit so "Sent ✅" is visible for a beat
    /// before the sheet closes. Cancelled on `.onDisappear` so a delayed dismiss
    /// never fires `onDone()` after the view is already gone.
    private func scheduleDismiss() {
        dismissTask?.cancel()
        dismissTask = Task {
            try? await Task.sleep(for: .seconds(1))
            guard !Task.isCancelled else { return }
            onDone()
        }
    }

    // MARK: - Networking (device-authed; see the header note)

    private enum ManualStatus {
        case delivered
        case noPendingLogin
    }

    // `nonisolated`: ManualCodeView conforms to `View`, which infers MainActor
    // isolation on its members by default. This helper is pure networking code
    // meant to run on the Task.detached background executor, not the main actor —
    // mirrors SMSRelaySetupView's mintWebhook/fetchLastSeen.
    nonisolated private static func submitCode(_ code: String, deviceToken: String) async throws -> ManualStatus {
        guard let url = URL(string: manualSubmitURL) else { throw ManualCodeError.unreachable }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("Bearer \(deviceToken)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONEncoder().encode(ManualCodeRequest(code: code))

        let (data, response) = try await URLSession.shared.data(for: request)
        try Self.checkOK(response)
        let decoded = try JSONDecoder().decode(ManualCodeResponse.self, from: data)
        switch decoded.status {
        case "delivered": return .delivered
        case "no_pending_login": return .noPendingLogin
        default: throw ManualCodeError.unreachable
        }
    }

    nonisolated private static func checkOK(_ response: URLResponse) throws {
        guard let http = response as? HTTPURLResponse else { throw ManualCodeError.unreachable }
        switch http.statusCode {
        case 200: return
        case 401: throw ManualCodeError.unauthorized
        case 400: throw ManualCodeError.badRequest
        default: throw ManualCodeError.server(http.statusCode)
        }
    }

    nonisolated private static func friendlyNetworkError(_ error: Error) -> String {
        (error as? LocalizedError)?.errorDescription ?? "Something went wrong. Try again."
    }
}

// MARK: - Wire shapes (device-authed manual-entry route)

/// `POST /devices/sms-relay/manual` request body.
private struct ManualCodeRequest: Encodable {
    let code: String
}

/// `POST /devices/sms-relay/manual` -> `{"status":"delivered"|"no_pending_login"}`.
private struct ManualCodeResponse: Decodable {
    let status: String
}

private enum ManualCodeError: LocalizedError {
    case unreachable
    case unauthorized
    case badRequest
    case server(Int)

    var errorDescription: String? {
        switch self {
        case .unreachable:
            return "Couldn't reach the hub. Check your connection and try again."
        case .unauthorized:
            return "This device's pairing looks invalid. Re-pair in Settings, then try again."
        case .badRequest:
            return "That code didn't look right. Check it and try again."
        case .server:
            return "The hub couldn't complete that request. Try again in a moment."
        }
    }
}
