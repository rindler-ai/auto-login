// CustodyApp — SwiftUI entry point + navigation shell for Auto-Login credential custody.
//
// This is the Dest ROUTER, ported from the Compose `ui/AutoLoginApp.kt`.
// It drives a five-destination flow
// (Onboarding → Pair → Home → {Enroll, Settings}) from a single `dest`
// state plus a `forward` flag that picks the slide direction. The redesigned
// screens (OnboardingView / PairingView / HomeView / EnrollView / SettingsView)
// each live in their own file; this shell only wires them.
//
// The same file backs BOTH iOS (WindowGroup) and macOS (MenuBarExtra agent); the
// macOS shell reuses these exact sources plus the `macos` slice of
// `Custody.xcframework` (built by `make ios`). CustodyModel owns the in-process
// relay session (the iOS substitute for Android's foreground RelayService): its
// start()/stop() drive MobileStart / MobileSession.stop(), and refreshSites()
// re-reads the Keychain site index after an enroll/remove.
//
// Android divergences folded in here (see PARITY.md): RelayService.ensureRunning
// → CustodyModel.start(); the hardware-back BackHandler is DROPPED (no
// interceptable back on iOS); Home reads sites from Keychain.sites() via the
// model, not a Go ListSites.

import SwiftUI
import Custody // gomobile-bound module: MobileStart / MobileSession / Mobile*

// Deployment default. A real build reads this from a config / the paired hub.
private let hubURL = Config.hubWebSocketURL

/// Observable app state: owns the in-process relay session and surfaces the
/// running flag + enrolled-site list to the screens. The private key and the
/// secret store never cross into Go except one sealed secret per approved ping.
@MainActor
final class CustodyModel: ObservableObject {
    @Published var running = false
    @Published var status = "Idle"
    @Published var sites: [String] = Keychain.sites()

    // Retained so the background relay loop stays alive; nil when stopped.
    private var session: MobileSession?

    /// Re-read the HOME stored-logins list from the Keychain site index (called
    /// after enrollment and after a remove).
    func refreshSites() {
        sites = Keychain.sites()
    }

    /// Start the custody agent: hand the Go core the paired token + Ed25519 device
    /// key + the server's ping-signing pubkey, plus the native SecretSource
    /// (Keychain) and Approver (AutoApprover — releases are authorized by the
    /// verified server signature, no per-release tap). This is the iOS relay
    /// substitute for Android's foreground RelayService.
    func start() {
        guard let token = Keychain.deviceToken(), let key = Keychain.deviceKeyB64() else {
            status = "Not paired"; running = false; return
        }
        //: no server pubkey means the device can authenticate no ping and would
        // decline every login. Say so plainly instead of running a relay that can
        // never serve — a device paired before this change lands has none.
        guard let serverPubkey = Keychain.serverPubkeyB64(), !serverPubkey.isEmpty else {
            status = "Re-pair required — this device is missing the server key"
            running = false
            return
        }
        // MobileStart is a gomobile-bound C function (NSError** out-param, not
        // Swift `throws`); returns an optional MobileSession — nil means `err` is set.
        var err: NSError?
        // The trailing CodeExpectationSink arms the Android background SMS reader from
        // the sms_otp_code ping; iOS cannot read SMS (the code arrives via the manual /
        // Shortcut relay, SMSRelaySetupView), so pass nil — the ping is simply dropped.
        if let s = MobileStart(hubURL, token, key, serverPubkey,
                                KeychainSecretSource(),
                                AutoApprover(), nil, &err) {
            session = s
            running = true
            status = "Connected — auto-filling logins"
            refreshSites()
        } else {
            running = false
            status = "Start failed: \(err?.localizedDescription ?? "unknown error")"
        }
    }

    /// Stop tears down the hub connection (idempotent on the Go side).
    func stop() {
        session?.stop()
        session = nil
        running = false
        status = "Stopped"
    }
}

// MARK: - Navigation

/// The five destinations, ranked low→high so a move to a higher-ranked screen is
/// "forward" (slide in from the right) and a move back is "backward" (slide in
/// from the left). Mirrors AutoLoginApp.kt's `Dest`.
private enum Dest { case onboarding, pair, home, enroll, settings }

/// The nav shell: holds the CustodyModel + current destination and animates the
/// slide±fade between screens. Hosted by `@main CustodyApp` in a WindowGroup (iOS)
/// or a MenuBarExtra window (macOS).
struct RootView: View {
    @StateObject private var model = CustodyModel()

    // First screen: intro if never onboarded, else pair if there's no device token
    // yet, else the paired Home. Exact mirror of AutoLoginApp's initial-dest logic.
    @State private var dest: Dest =
        !Keychain.isOnboarded() ? .onboarding
        : (Keychain.deviceToken() == nil ? .pair : .home)

    // A forward move slides in from the right; a back move from the left. Purely
    // presentational — set by go() in the same transaction as the dest change so
    // the transition reads the new direction.
    @State private var forward = true

    /// Route to `next`, choosing the slide direction, and animate the swap. Mirrors
    /// AutoLoginApp's `fun go(next, isForward)`.
    private func go(_ next: Dest, forward: Bool = true) {
        self.forward = forward
        withAnimation(.easeInOut(duration: 0.32)) {
            dest = next
        }
    }

    // Compose's slideIn(dir·w/6)+fadeIn togetherWith slideOut(-dir·w/6)+fadeOut →
    // an asymmetric move+opacity whose entering/leaving edges flip with `forward`.
    private var navTransition: AnyTransition {
        .asymmetric(
            insertion: .move(edge: forward ? .trailing : .leading).combined(with: .opacity),
            removal: .move(edge: forward ? .leading : .trailing).combined(with: .opacity)
        )
    }

    var body: some View {
        ZStack {
            // Background bleeds edge-to-edge (behind the safe area); the screens inset
            // to the safe area by default, matching Compose's windowInsetsPadding.
            AutoLoginColors.background.ignoresSafeArea()
            screen
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        #if os(macOS)
        // The MenuBarExtra popover has no window to fill, so give the phone-shaped
        // layout a fixed size instead of collapsing to its intrinsic width.
        .frame(width: 360, height: 640)
        #endif
    }

    // Each case is a distinct _ConditionalContent branch, so changing `dest` inside
    // withAnimation removes the old screen and inserts the new one — each carrying
    // the shared slide±fade transition. Closures mirror AutoLoginApp's screen wiring.
    @ViewBuilder
    private var screen: some View {
        switch dest {
        case .onboarding:
            OnboardingView(onDone: {
                Keychain.setOnboarded()
                go(Keychain.deviceToken() == nil ? .pair : .home)
            })
            .transition(navTransition)

        case .pair:
            // model.start() is the iOS substitute for RelayService.ensureRunning(ctx).
            PairingView(onPaired: {
                model.start()
                go(.home)
            })
            .transition(navTransition)

        case .home:
            HomeView(
                running: model.running,
                sites: model.sites,
                onToggle: { model.running ? model.stop() : model.start() },
                onRemove: { site in
                    Keychain.delete(site)
                    model.refreshSites()
                },
                onAddLogin: { go(.enroll) },
                onSettings: { go(.settings) }
            )
            .transition(navTransition)

        case .enroll:
            // Re-read the site list on the way back so Home shows the new login.
            EnrollView(onDone: {
                model.refreshSites()
                go(.home, forward: false)
            })
            .transition(navTransition)

        case .settings:
            SettingsView(
                onBack: { go(.home, forward: false) },
                onRepair: { go(.pair) },
                onReset: {
                    // Destructive reset: stop the live relay session FIRST so the hub
                    // connection drops and no authenticated MobileSession outlives the
                    // wipe (Android parity: RelayService.stop before reset). Then erase
                    // the device identity + every stored credential, then start fresh.
                    model.stop()
                    Keychain.resetAll()
                    go(.onboarding, forward: false)
                }
            )
            .transition(navTransition)
        }
    }
}

@main
struct CustodyApp: App {
    var body: some Scene {
        #if os(macOS)
        // macOS runs as a background menu-bar agent (see ../macos/README.md).
        MenuBarExtra("Auto-Login", systemImage: "lock.shield") {
            RootView()
        }
        .menuBarExtraStyle(.window)
        #else
        WindowGroup {
            RootView()
        }
        #endif
    }
}
