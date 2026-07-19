// Config — build-time hub configuration for the Auto Login iOS/macOS shells.
//
// The device hub host is NOT baked into the source tree. It defaults to a
// placeholder and is overridden per build by setting the `AutoLoginHubHost`
// key in Info.plist (e.g. from an xcconfig, a build setting, or CI). Point it
// at YOUR OWN hub — the server that mints pairing codes and relays sealed
// secrets. Every hub URL the app talks to is derived from this one host.

import Foundation

enum Config {
    /// The hub host (no scheme, e.g. `hub.example.com`). Override at build time
    /// via the Info.plist `AutoLoginHubHost` key; defaults to a placeholder so no
    /// real deployment host ships in the open-source tree.
    static let hubHost: String = {
        if let h = Bundle.main.object(forInfoDictionaryKey: "AutoLoginHubHost") as? String,
           !h.isEmpty {
            return h
        }
        return "your-hub.example"
    }()

    /// wss://<host>/v1/devices/connect — the relay WebSocket the agent connects to.
    static var hubWebSocketURL: String { "wss://\(hubHost)/v1/devices/connect" }

    /// https://<host>/devices/pair/complete — first-run pairing enrollment.
    static var pairURL: String { "https://\(hubHost)/devices/pair/complete" }

    /// Device-authed SMS-relay routes (Bearer <deviceToken>).
    static var smsRelayMintURL: String { "https://\(hubHost)/devices/sms-relay/token" }
    static var smsRelayLastSeenURL: String { "https://\(hubHost)/devices/sms-relay/last-seen" }
    static var manualSubmitURL: String { "https://\(hubHost)/devices/sms-relay/manual" }

    /// Where the user mints a single-use pairing code (Settings -> Devices).
    static var hubConsoleURL: String { "https://\(hubHost)/settings/devices" }

    /// The in-app privacy-policy page. App Store Guideline 5.1.1(i) and Play
    /// Data-safety require a reachable in-app link. Override via the Info.plist
    /// `AutoLoginPrivacyPolicyURL` key; defaults to a placeholder and never
    /// crashes on a missing/malformed value.
    static var privacyPolicyURL: URL {
        if let s = Bundle.main.object(forInfoDictionaryKey: "AutoLoginPrivacyPolicyURL") as? String,
           let u = URL(string: s) {
            return u
        }
        return URL(string: "https://your-hub.example/privacy")!
    }
}
