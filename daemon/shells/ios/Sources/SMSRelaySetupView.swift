// SMSRelaySetupView — the guided "SMS auto-fill" setup wizard.
//
// The custody shell holds no web session, only a device bearer token (minted at
// MobilePair time), so this wizard authenticates with that token and calls the
// DEVICE-authed sms-relay routes on the hub server (mounted alongside the device
// routes):
//
//   POST https://your-hub.example/devices/sms-relay/token       Authorization: Bearer <deviceToken>
//     -> 200 {"token":"...","ingest_url":"..."}
//   GET  https://your-hub.example/devices/sms-relay/last-seen   Authorization: Bearer <deviceToken>
//     -> 200 {"last_seen_at":"<RFC3339 UTC>"|null}
//
// Both 401 uniformly on any auth failure (missing/unknown/revoked device — no
// oracle). `Keychain.deviceToken()` is the bearer; if it's nil, this device hasn't
// been paired yet and the wizard shows an error state pointing at Settings instead
// of a screen with nothing to configure.
//
// REUSE, NEVER RE-MINT: `POST /devices/sms-relay/token` ROTATES the user's ingest
// token every time it's called. If `Keychain.smsRelayWebhook()` already holds a
// value, this wizard reuses it as-is — minting again would silently break an
// already-working automation (its stored webhook token would stop matching the
// server) and reset the last-seen baseline the "Test it" flow depends on. The mint
// POST only ever fires once, the first time this device sets up SMS auto-fill.
//
// The wizard cannot create the Shortcuts automation itself — iOS exposes no API for
// that. `shortcuts://create-automation` (no arguments; Shortcuts doesn't support
// pre-filling a trigger/action via URL) opens the automation creator, and the
// numbered steps below carry the user the rest of the way by hand: New Automation
// -> Message (any sender, "Message Contains" `code`) -> Run Immediately -> add
// action "Forward message to Auto-Login" (the app's App Intent, referenced here by its
// exact title) -> set that action's Message to the automation's Shortcut Input ->
// Allow Running While Locked. "Test it" then polls last-seen for up to ~90s after
// prompting the user to text themselves a `code`-containing message, confirming the
// whole pipeline actually fired.
//
// Pure UI over the shared Theme.swift / Components.swift tokens + Keychain; the
// only state this file owns is the mint/test wizard progress. It handles NO SMS
// content itself (that's ForwardMessageIntent's job) and never logs the webhook
// token, the device bearer, or any message text.

import SwiftUI
import Foundation
#if os(iOS)
import UIKit
#endif

// Same host base as PairingView's `pairURL` — the device-authed sms-relay routes
// mounted alongside `/devices/pair/complete`.
private let smsRelayMintURL = Config.smsRelayMintURL
private let smsRelayLastSeenURL = Config.smsRelayLastSeenURL

struct SMSRelaySetupView: View {
    /// Dismisses the wizard (the Settings sheet's `isPresented` flip). Fired by the
    /// back arrow, the "not paired" state's CTA, and the final "Done" after a
    /// successful test.
    var onDone: () -> Void

    private enum MintState {
        case loading
        case ready
        case notPaired
        case error(String)
    }

    private enum TestState {
        case idle
        case capturingBaseline
        case waiting
        case success
        case timedOut
        case failed(String)
    }

    @State private var mintState: MintState
    @State private var testState: TestState = .idle
    @State private var pollTask: Task<Void, Never>?

    /// Resolve the starting state synchronously (no network flash) when a webhook
    /// is already stored — see the REUSE, NEVER RE-MINT note above.
    init(onDone: @escaping () -> Void) {
        self.onDone = onDone
        if Keychain.smsRelayWebhook() != nil {
            _mintState = State(initialValue: .ready)
        } else if Keychain.deviceToken() == nil {
            _mintState = State(initialValue: .notPaired)
        } else {
            _mintState = State(initialValue: .loading)
        }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                TopBar("SMS auto-fill", onBack: onDone)

                Spacer().frame(height: 12)

                Text("Set this up once and Auto-Login can read a text's verification code automatically, instead of you copy-pasting a 2FA code mid-login. Takes about 2 minutes — iOS doesn't let apps create the automation for you, so you'll build a small one yourself with a few taps below.")
                    .autoLoginText(.bodyMedium)
                    .foregroundStyle(AutoLoginColors.onSurfaceVariant)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .fixedSize(horizontal: false, vertical: true)

                Spacer().frame(height: 20)

                statusSection

                Spacer().frame(height: 8)
                TrustFooter()
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 24)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(AutoLoginColors.background)
        .onAppear { ensureWebhook() }
        .onDisappear { pollTask?.cancel() }
    }

    // MARK: - Mint status

    @ViewBuilder
    private var statusSection: some View {
        switch mintState {
        case .loading:
            statusCard(icon: "arrow.triangle.2.circlepath", message: "Setting up your secure webhook…", spinner: true)

        case .notPaired:
            VStack(alignment: .leading, spacing: 14) {
                statusCard(
                    icon: "exclamationmark.triangle.fill",
                    message: "This device isn't paired yet. Pair it in Settings first, then reopen SMS auto-fill setup.",
                    tint: AutoLoginColors.error
                )
                PrimaryButton("Got it", action: onDone)
            }

        case .error(let message):
            VStack(alignment: .leading, spacing: 14) {
                statusCard(icon: "exclamationmark.triangle.fill", message: message, tint: AutoLoginColors.error)
                PrimaryButton("Retry") { retryMint() }
            }

        case .ready:
            readyContent
        }
    }

    private func statusCard(icon: String, message: String, tint: Color = AutoLoginColors.onSurfaceVariant, spinner: Bool = false) -> some View {
        AppCard {
            HStack(alignment: .top, spacing: 14) {
                if spinner {
                    ProgressView()
                        .progressViewStyle(.circular)
                        .tint(AutoLoginColors.primary)
                        .frame(width: 40, height: 40)
                } else {
                    IconChip(icon, tint: tint, bg: tint.opacity(0.13))
                }
                Text(message)
                    .autoLoginText(.bodyMedium)
                    .foregroundStyle(AutoLoginColors.onSurface)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(16)
        }
    }

    // MARK: - Ready: guided steps + verification test

    private var readyContent: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("SET UP THE AUTOMATION")
                .autoLoginText(.labelSmall)
                .foregroundStyle(AutoLoginColors.onSurfaceVariant)
                .padding(.leading, 4)
                .padding(.bottom, 8)

            AppCard {
                VStack(alignment: .leading, spacing: 16) {
                    ForEach(Array(Self.guideSteps.enumerated()), id: \.offset) { index, step in
                        StepRow(number: index + 1, text: step)
                    }
                }
                .padding(16)
            }

            Spacer().frame(height: 14)

            SecondaryButton(
                "Open Shortcuts",
                leading: {
                    Image(systemName: "arrow.up.forward.app")
                        .font(.system(size: 16))
                        .foregroundStyle(AutoLoginColors.onSurface)
                },
                action: openShortcuts
            )

            Spacer().frame(height: 24)

            Text("TEST IT")
                .autoLoginText(.labelSmall)
                .foregroundStyle(AutoLoginColors.onSurfaceVariant)
                .padding(.leading, 4)
                .padding(.bottom, 8)

            AppCard {
                VStack(alignment: .leading, spacing: 14) {
                    Text("Once the automation above is set up, text yourself a message containing the word \"code\" — for example \"your code is 123456\" — and Auto-Login will confirm the capture here.")
                        .autoLoginText(.bodyMedium)
                        .foregroundStyle(AutoLoginColors.onSurfaceVariant)
                        .fixedSize(horizontal: false, vertical: true)

                    Text("Background capture is best-effort — if it ever misses one, just enter the code by hand as usual.")
                        .autoLoginText(.labelMedium)
                        .foregroundStyle(AutoLoginColors.onSurfaceVariant)
                        .fixedSize(horizontal: false, vertical: true)

                    testStatusRow

                    testActionButton
                }
                .padding(16)
            }

            if case .success = testState {
                Spacer().frame(height: 14)
                PrimaryButton("Done", action: onDone)
            }
        }
    }

    @ViewBuilder
    private var testStatusRow: some View {
        switch testState {
        case .idle:
            EmptyView()
        case .capturingBaseline:
            inlineStatus(icon: "arrow.triangle.2.circlepath", text: "Checking your current status…", tint: AutoLoginColors.onSurfaceVariant)
        case .waiting:
            inlineStatus(icon: "antenna.radiowaves.left.and.right", text: "Waiting for your test text — this can take a minute or two…", tint: AutoLoginColors.onSurfaceVariant)
        case .success:
            inlineStatus(icon: "checkmark.circle.fill", text: "Capture working ✅", tint: AutoLoginColors.primary)
        case .timedOut:
            inlineStatus(icon: "exclamationmark.triangle.fill", text: "Didn't see it. Check the automation is enabled and \"Run Immediately\" is on, then retry.", tint: AutoLoginColors.error)
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

    @ViewBuilder
    private var testActionButton: some View {
        switch testState {
        case .idle:
            PrimaryButton("Test it") { startTest() }
        case .capturingBaseline, .waiting:
            PrimaryButton("Testing…", enabled: false, loading: true) {}
        case .timedOut, .failed:
            SecondaryButton("Retry") { startTest() }
        case .success:
            EmptyView()
        }
    }

    // Comment-style guided steps (verbatim flow from the plan): New Automation ->
    // Message (any sender, "Message Contains" `code`) -> Run Immediately -> add
    // action "Forward message to Auto-Login" -> set its Message to the automation's
    // Shortcut Input -> Allow Running While Locked.
    private static let guideSteps: [String] = [
        "Tap New Automation.",
        "Pick Message. Leave the sender as Any, then turn on \"Message Contains\" and enter code.",
        "Turn off \"Ask Before Running\" so it runs immediately.",
        "Tap Next, add an action, and search for \"Auto-Login.\" Add Forward message to Auto-Login.",
        "Tap that action's Message field and choose the automation's Shortcut Input as the variable.",
        "Turn on \"Allow Running While Locked,\" then tap Done.",
    ]

    // MARK: - Deep link

    /// Opens the Shortcuts automation creator. `shortcuts://create-automation`
    /// accepts no arguments (Shortcuts has no URL scheme for pre-filling a
    /// trigger/action), so the numbered steps above carry the user the rest of the
    /// way. iOS-only: UIApplication.open has no macOS equivalent, and the Shortcuts
    /// "Message" automation trigger this sets up doesn't exist on macOS (mirrors
    /// ForwardMessageIntent's iOS-only scope) — a no-op there.
    private func openShortcuts() {
        #if os(iOS)
        guard let url = URL(string: "shortcuts://create-automation") else { return }
        UIApplication.shared.open(url)
        #endif
    }

    // MARK: - Mint (fetch-or-reuse the webhook)

    private func retryMint() {
        mintState = .loading
        ensureWebhook()
    }

    /// Only mints when `mintState == .loading`, which the init already reserves for
    /// "no stored webhook, but a device token exists" — a stored webhook resolves
    /// straight to `.ready` in init and never reaches here, so this never re-mints
    /// (rotates) an already-working setup.
    private func ensureWebhook() {
        guard case .loading = mintState else { return }
        guard let deviceToken = Keychain.deviceToken() else {
            mintState = .notPaired
            return
        }
        Task.detached {
            do {
                let webhook = try await Self.mintWebhook(deviceToken: deviceToken)
                Keychain.saveSMSRelayWebhook(url: webhook.url, token: webhook.token)
                await MainActor.run { mintState = .ready }
            } catch {
                await MainActor.run { mintState = .error(Self.friendlyNetworkError(error)) }
            }
        }
    }

    // MARK: - Verification test

    /// Captures the current last-seen baseline, prompts the user to send
    /// themselves a `code` text, then polls every ~3s for up to ~90s until
    /// `last_seen_at` advances past the baseline (or times out). Runs entirely off
    /// the main thread; every `@State` write hops back via `MainActor.run`.
    private func startTest() {
        guard let deviceToken = Keychain.deviceToken() else {
            testState = .failed("This device isn't paired anymore. Re-pair in Settings, then retry.")
            return
        }
        pollTask?.cancel()
        testState = .capturingBaseline
        pollTask = Task.detached {
            do {
                let baseline = try await Self.fetchLastSeen(deviceToken: deviceToken)
                await MainActor.run { testState = .waiting }

                let deadline = Date().addingTimeInterval(90)
                while Date() < deadline {
                    try Task.checkCancellation()
                    try await Task.sleep(for: .seconds(3))
                    try Task.checkCancellation()

                    // A single poll failing (transient network blip) shouldn't abort
                    // the whole test — only the deadline or cancellation does.
                    if let seen = try? await Self.fetchLastSeen(deviceToken: deviceToken),
                       Self.advanced(seen, past: baseline) {
                        await MainActor.run { testState = .success }
                        return
                    }
                }
                await MainActor.run { testState = .timedOut }
            } catch is CancellationError {
                // View disappeared, or a newer test superseded this one — no state write.
            } catch {
                await MainActor.run { testState = .failed(Self.friendlyNetworkError(error)) }
            }
        }
    }

    // `nonisolated`: SMSRelaySetupView conforms to `View`, which infers MainActor
    // isolation on its members by default. These helpers are pure/networking code
    // meant to run on the Task.detached background executor, not the main actor —
    // without `nonisolated`, calling a synchronous one (`advanced`) from inside
    // Task.detached is a Swift 6 language-mode error (seen as a warning under this
    // project's current SWIFT_VERSION = 5.0).
    nonisolated private static func advanced(_ new: Date?, past baseline: Date?) -> Bool {
        guard let new else { return false }
        guard let baseline else { return true }
        return new > baseline
    }

    // MARK: - Networking (device-authed; see the header note)

    nonisolated private static func mintWebhook(deviceToken: String) async throws -> (url: String, token: String) {
        guard let url = URL(string: smsRelayMintURL) else { throw SMSRelayNetworkError.unreachable }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("Bearer \(deviceToken)", forHTTPHeaderField: "Authorization")

        let (data, response) = try await URLSession.shared.data(for: request)
        try Self.checkOK(response)
        let decoded = try JSONDecoder().decode(MintTokenResponse.self, from: data)
        return (decoded.ingestURL, decoded.token)
    }

    nonisolated private static func fetchLastSeen(deviceToken: String) async throws -> Date? {
        guard let url = URL(string: smsRelayLastSeenURL) else { throw SMSRelayNetworkError.unreachable }
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue("Bearer \(deviceToken)", forHTTPHeaderField: "Authorization")

        let (data, response) = try await URLSession.shared.data(for: request)
        try Self.checkOK(response)
        let decoded = try JSONDecoder().decode(LastSeenResponse.self, from: data)
        guard let raw = decoded.lastSeenAt else { return nil }
        return ISO8601DateFormatter().date(from: raw)
    }

    nonisolated private static func checkOK(_ response: URLResponse) throws {
        guard let http = response as? HTTPURLResponse else { throw SMSRelayNetworkError.unreachable }
        switch http.statusCode {
        case 200: return
        case 401: throw SMSRelayNetworkError.unauthorized
        default: throw SMSRelayNetworkError.server(http.statusCode)
        }
    }

    nonisolated private static func friendlyNetworkError(_ error: Error) -> String {
        (error as? LocalizedError)?.errorDescription ?? "Something went wrong. Try again."
    }
}

// MARK: - Response shapes (device-authed sms-relay routes)

/// `POST /devices/sms-relay/token` -> `{"token":"...","ingest_url":"..."}`.
private struct MintTokenResponse: Decodable {
    let token: String
    let ingestURL: String

    private enum CodingKeys: String, CodingKey {
        case token
        case ingestURL = "ingest_url"
    }
}

/// `GET /devices/sms-relay/last-seen` -> `{"last_seen_at":"<RFC3339 UTC>"|null}`.
private struct LastSeenResponse: Decodable {
    let lastSeenAt: String?

    private enum CodingKeys: String, CodingKey {
        case lastSeenAt = "last_seen_at"
    }
}

private enum SMSRelayNetworkError: LocalizedError {
    case unreachable
    case unauthorized
    case server(Int)

    var errorDescription: String? {
        switch self {
        case .unreachable:
            return "Couldn't reach the hub. Check your connection and try again."
        case .unauthorized:
            return "This device's pairing looks invalid. Re-pair in Settings, then try again."
        case .server:
            return "The hub couldn't complete that request. Try again in a moment."
        }
    }
}

// MARK: - StepRow

/// One numbered guidance row: a numeric SF Symbol chip ("1.circle.fill", …) + the
/// step's instruction text. Private to this wizard.
private struct StepRow: View {
    let number: Int
    let text: String

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            IconChip("\(number).circle.fill", tint: AutoLoginColors.primary)
            Text(text)
                .autoLoginText(.bodyLarge)
                .foregroundStyle(AutoLoginColors.onSurface)
                .frame(maxWidth: .infinity, alignment: .leading)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}
