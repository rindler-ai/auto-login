// HomeView — the post-pair landing screen of the custody shell, ported from
// the Compose `ui/HomeScreen.kt`.
//
// Layout mirror: a FIXED brand header, a SCROLLING middle that consumes the
// remaining height (so the trust line pins to the bottom, no dead lower half), and
// a PINNED TrustFooter. The middle holds the Active/Paused StatusCard, the
// Saved-logins list (or an empty-state card), and an "Add a login" action.
//
// Pure UI: no Go / Keychain touch here. HomeView READS relay state + the site list
// from the injected CustodyModel (passed as `running` / `sites`) and reports intent
// back through closures — the nav shell (CustodyApp) does the actual wiring:
//
//   HomeView(
//     running:    model.running,                         // Active when true
//     sites:      model.sites,                           // Keychain.sites() list
//     onToggle:   { model.running ? model.stop() : model.start() },
//     onRemove:   { site in Keychain.delete(site); model.refreshSites() },
//     onAddLogin: { go(.enroll) },
//     onSettings: { go(.settings) }
//   )
//
// This file paints NO screen background — the nav shell provides
// AutoLoginColors.background edge-to-edge (matches Kotlin: HomeScreen paints none).
// The only local state is the remove-confirmation target (`pendingDelete`) and the
// StatusCard's breathing animation. Compiles for BOTH iOS and macOS.
//
// iOS DIVERGENCE (see PARITY.md): the Active/Paused toggle drives the IN-PROCESS
// session via CustodyModel.start()/stop() (over MobileStart / MobileSession.stop())
// instead of Android's foreground RelayService; and there is no BootReceiver
// always-on persistence yet — "Active" here means running in-app only. The copy is
// mirrored verbatim regardless.
//
// Manual-entry fallback: a persistent "Enter a code" affordance + a
// capture-status chip, owned locally (a `showingManualEntry` sheet flag) rather
// than threaded through the nav shell's closure list — CustodyApp's HomeView(...)
// call and closure signature are UNCHANGED. The chip reads ONLY the local
// Keychain.smsRelayWebhook() flag (set by SMSRelaySetupView) — no networking here,
// so it can never distinguish
// "webhook configured and healthy" from "configured but silently broken"; that
// finer "needs unlock" distinction is explicitly out of scope (would need a
// last-seen network read + staleness heuristic). "Enter a code" is reachable
// regardless of the chip's state — it is the reliability FLOOR, not conditional on
// auto-fill being set up.

import SwiftUI

struct HomeView: View {
    /// Relay state — true when the custody session is running ("Active"). Source of
    /// truth is `CustodyModel.running`; the nav shell passes it in.
    let running: Bool
    /// Enrolled site hosts, from `Keychain.sites()` via `CustodyModel.sites`.
    let sites: [String]
    /// Toggle the relay. Nav wires this to `model.running ? model.stop() : model.start()`.
    let onToggle: () -> Void
    /// Remove a stored login. Nav wires this to `Keychain.delete(site)` + `model.refreshSites()`.
    let onRemove: (String) -> Void
    /// Navigate to Enroll ("Add a login").
    let onAddLogin: () -> Void
    /// Navigate to Settings.
    let onSettings: () -> Void

    /// Exact init the nav shell targets. Only the six props above are public;
    /// `pendingDelete` stays private @State (a hand-written init keeps it out of the
    /// public surface — the synthesized memberwise init would be private and leak it).
    init(
        running: Bool,
        sites: [String],
        onToggle: @escaping () -> Void,
        onRemove: @escaping (String) -> Void,
        onAddLogin: @escaping () -> Void,
        onSettings: @escaping () -> Void
    ) {
        self.running = running
        self.sites = sites
        self.onToggle = onToggle
        self.onRemove = onRemove
        self.onAddLogin = onAddLogin
        self.onSettings = onSettings
    }

    // Tapping a saved login opens a remove confirmation. We never reveal the stored
    // credential — the only management action is to delete it. nil = dialog closed.
    @State private var pendingDelete: String?

    // The manual-entry sheet, owned locally — CustodyApp's HomeView(...)
    // call is untouched; this is presentation state, not navigation the nav shell
    // needs to know about.
    @State private var showingManualEntry = false

    var body: some View {
        ZStack {
            VStack(spacing: 0) {
                header
                scrollingMiddle
                TrustFooter()
            }
            .padding(.horizontal, 20)
            .frame(maxWidth: .infinity, maxHeight: .infinity)

            if let site = pendingDelete {
                removeConfirmation(site: site)
                    .transition(.opacity)
                    .zIndex(1)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .animation(.easeInOut(duration: 0.2), value: pendingDelete)
        .sheet(isPresented: $showingManualEntry) {
            ManualCodeView(onDone: { showingManualEntry = false })
        }
    }

    // MARK: - Brand header (fixed above the scroll region)

    private var header: some View {
        HStack(spacing: 0) {
            // 32pt chip: small/12pt corner, primary@0.13 fill, 18pt shield tinted primary.
            Image(systemName: "checkmark.shield.fill")
                .font(.system(size: 18))
                .foregroundStyle(AutoLoginColors.primary)
                .frame(width: 32, height: 32)
                .background(AutoLoginColors.primary.opacity(0.13), in: AutoLoginShape.sm)

            Spacer().frame(width: 10)

            Text("Auto Login")
                .autoLoginText(.titleLarge)
                .foregroundStyle(AutoLoginColors.onBackground)

            Spacer()

            Button(action: onSettings) {
                Image(systemName: "gearshape")
                    .font(.system(size: 22))
                    .foregroundStyle(AutoLoginColors.onSurfaceVariant)
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Settings")
        }
        .frame(height: 64)
    }

    // MARK: - Scrolling middle (expands to fill; pins the footer to the bottom)

    private var scrollingMiddle: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                Spacer().frame(height: 8)

                StatusCard(active: running, onToggle: onToggle)

                Spacer().frame(height: 14)

                // The reliability floor. Always reachable regardless of
                // whether SMS auto-fill is set up — the chip reports auto-fill's
                // local on/off state, "Enter a code" works either way.
                HStack(spacing: 0) {
                    CaptureStatusChip(active: Keychain.smsRelayWebhook() != nil)
                    Spacer(minLength: 0)
                }

                Spacer().frame(height: 10)

                SecondaryButton(
                    "Enter a code",
                    leading: {
                        Image(systemName: "keyboard")
                            .font(.system(size: 16))
                            .foregroundStyle(AutoLoginColors.onSurface)
                    },
                    action: { showingManualEntry = true }
                )

                Spacer().frame(height: 28)

                HStack(spacing: 0) {
                    Text("Saved logins")
                        .autoLoginText(.titleMedium)
                        .foregroundStyle(AutoLoginColors.onBackground)
                    Spacer()
                    if !sites.isEmpty {
                        Text("\(sites.count)")
                            .autoLoginText(.labelMedium)
                            .foregroundStyle(AutoLoginColors.onSurfaceVariant)
                    }
                }

                Spacer().frame(height: 12)

                if sites.isEmpty {
                    // The empty state owns the single call-to-action (no separate
                    // button beneath it repeating the same ask).
                    EmptyLogins(onAdd: onAddLogin)
                } else {
                    AppCard {
                        VStack(spacing: 0) {
                            ForEach(Array(sites.enumerated()), id: \.element) { entry in
                                LoginRow(site: entry.element) { pendingDelete = entry.element }
                                if entry.offset != sites.count - 1 {
                                    Rectangle()
                                        .fill(AutoLoginColors.outlineVariant)
                                        .frame(height: 1)
                                        .padding(.leading, 68)
                                }
                            }
                        }
                    }

                    Spacer().frame(height: 16)

                    SecondaryButton(
                        "Add a login",
                        leading: {
                            Image(systemName: "plus")
                                .font(.system(size: 20))
                                .foregroundStyle(AutoLoginColors.onSurface)
                        },
                        action: onAddLogin
                    )
                }

                Spacer().frame(height: 24)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .frame(maxHeight: .infinity)
    }

    // MARK: - Remove-confirmation overlay

    // SwiftUI's `.alert` can't host the SiteAvatar icon or the large/22pt corner, so
    // this is a faithful custom card: scrim + centered rounded panel with a 40pt
    // avatar, title, body, and a Remove(red)/Cancel text-button row.
    private func removeConfirmation(site: String) -> some View {
        ZStack {
            AutoLoginColors.scrim.opacity(0.45)
                .ignoresSafeArea()
                .contentShape(Rectangle())
                .onTapGesture { pendingDelete = nil }

            VStack(spacing: 0) {
                SiteAvatar(site: site, size: 40)

                Spacer().frame(height: 16)

                Text("Remove this login?")
                    .autoLoginText(.titleLarge)
                    .foregroundStyle(AutoLoginColors.onSurface)
                    .multilineTextAlignment(.center)

                Spacer().frame(height: 8)

                Text("Auto Login won't be able to sign you in to \(site) until you add it again. The stored login is erased from this device.")
                    .autoLoginText(.bodyMedium)
                    .foregroundStyle(AutoLoginColors.onSurfaceVariant)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: .infinity, alignment: .center)
                    .fixedSize(horizontal: false, vertical: true)

                Spacer().frame(height: 24)

                HStack(spacing: 0) {
                    Spacer()
                    Button { pendingDelete = nil } label: {
                        Text("Cancel")
                            .autoLoginText(.labelLarge)
                            .foregroundStyle(AutoLoginColors.primary)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 10)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)

                    Spacer().frame(width: 8)

                    Button {
                        onRemove(site)
                        pendingDelete = nil
                    } label: {
                        Text("Remove")
                            .autoLoginText(.labelLarge)
                            .foregroundStyle(AutoLoginColors.error)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 10)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(24)
            .frame(maxWidth: 360)
            .background(AutoLoginColors.surface, in: AutoLoginShape.lg)
            .overlay(AutoLoginShape.lg.stroke(AutoLoginColors.outline, lineWidth: 1))
            .padding(.horizontal, 28)
        }
    }
}

// MARK: - StatusCard

/// The hero card: an Active/Paused badge (breathing only when active) + a title/
/// subtitle + the toggle button (SecondaryButton to pause / PrimaryButton to resume).
private struct StatusCard: View {
    let active: Bool
    let onToggle: () -> Void

    // A slow breath on the active badge — a "live" signal that isn't distracting
    // (Compose: infiniteRepeatable(tween(1500), Reverse) scaling 1.0 -> 1.06).
    @State private var pulse = false

    var body: some View {
        AppCard {
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 0) {
                    // 48pt badge: fill animates primary@0.13 (active) / surfaceContainerHigh
                    // (paused); icon shield (active) / pause (paused).
                    Image(systemName: active ? "checkmark.shield.fill" : "pause.fill")
                        .font(.system(size: 24))
                        .foregroundStyle(active ? AutoLoginColors.primary : AutoLoginColors.onSurfaceVariant)
                        .frame(width: 48, height: 48)
                        .background(
                            active ? AutoLoginColors.primary.opacity(0.13) : AutoLoginColors.surfaceContainerHigh,
                            in: Circle()
                        )
                        .scaleEffect(pulse ? 1.06 : 1.0)
                        .animation(.easeInOut(duration: 0.3), value: active)
                        .onAppear { updatePulse() }
                        .onChange(of: active) { _ in updatePulse() }

                    Spacer().frame(width: 14)

                    VStack(alignment: .leading, spacing: 2) {
                        Text(active ? "Active" : "Paused")
                            .autoLoginText(.titleLarge)
                            .foregroundStyle(AutoLoginColors.onSurface)
                        Text(active
                             ? "Your logins are ready when the hub needs them"
                             : "Sign-ins are paused until you resume")
                            .autoLoginText(.bodyMedium)
                            .foregroundStyle(AutoLoginColors.onSurfaceVariant)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }

                Spacer().frame(height: 16)

                if active {
                    SecondaryButton("Pause protection", action: onToggle)
                } else {
                    PrimaryButton("Resume protection", action: onToggle)
                }
            }
            .padding(20)
        }
    }

    private func updatePulse() {
        if active {
            withAnimation(.easeInOut(duration: 1.5).repeatForever(autoreverses: true)) {
                pulse = true
            }
        } else {
            withAnimation(.easeInOut(duration: 0.25)) {
                pulse = false
            }
        }
    }
}

// MARK: - CaptureStatusChip

/// A small pill reporting SMS auto-fill's LOCAL on/off state — driven purely by
/// `Keychain.smsRelayWebhook() != nil` (no networking; HomeView reads that flag
/// fresh on every render and passes it in). Deliberately simple: this can say
/// "on" for a webhook that is configured but has gone quiet server-side — that
/// finer "needs unlock" distinction needs a last-seen network read + staleness
/// heuristic and is out of scope here. Emerald + a breathing
/// PulseDot when set; a muted static dot + hint when not, pointing at Settings
/// implicitly (the row itself doesn't navigate — "Enter a code" below it always
/// works regardless of this chip's state).
private struct CaptureStatusChip: View {
    let active: Bool

    var body: some View {
        HStack(spacing: 8) {
            if active {
                PulseDot(color: AutoLoginColors.primary)
            } else {
                Circle()
                    .fill(AutoLoginColors.onSurfaceVariant.opacity(0.35))
                    .frame(width: 9, height: 9)
            }
            Text(active ? "SMS auto-fill on" : "SMS auto-fill not set up")
                .autoLoginText(.labelMedium)
                .foregroundStyle(active ? AutoLoginColors.primary : AutoLoginColors.onSurfaceVariant)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 7)
        .background(
            (active ? AutoLoginColors.primary : AutoLoginColors.onSurfaceVariant).opacity(0.10),
            in: Capsule()
        )
    }
}

// MARK: - LoginRow

/// One saved login: avatar + host + "Stored on this device" + a lock hint. The whole
/// row is tappable and opens the remove confirmation (never reveals the credential).
private struct LoginRow: View {
    let site: String
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 0) {
                SiteAvatar(site: site)

                Spacer().frame(width: 14)

                VStack(alignment: .leading, spacing: 2) {
                    Text(site)
                        .autoLoginText(.titleMedium)
                        .foregroundStyle(AutoLoginColors.onSurface)
                    Text("Stored on this device")
                        .autoLoginText(.labelMedium)
                        .foregroundStyle(AutoLoginColors.onSurfaceVariant)
                }

                Spacer(minLength: 0)

                Image(systemName: "lock.fill")
                    .font(.system(size: 16))
                    .foregroundStyle(AutoLoginColors.onSurfaceVariant.opacity(0.6))
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .frame(maxWidth: .infinity)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

// MARK: - EmptyLogins

/// Zero-state card: a globe chip, a short explainer (with an NBSP bonding the last
/// two words so "ask." never orphans), and the single "Add a login" CTA.
private struct EmptyLogins: View {
    let onAdd: () -> Void

    var body: some View {
        AppCard {
            VStack(spacing: 0) {
                IconChip("globe", size: 48)

                Spacer().frame(height: 14)

                Text("No logins yet")
                    .autoLoginText(.titleMedium)
                    .foregroundStyle(AutoLoginColors.onSurface)

                Spacer().frame(height: 4)

                Text("Add a site and Auto Login can sign you in whenever you\u{00A0}ask.")
                    .autoLoginText(.bodyMedium)
                    .foregroundStyle(AutoLoginColors.onSurfaceVariant)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: .infinity, alignment: .center)
                    .fixedSize(horizontal: false, vertical: true)

                Spacer().frame(height: 20)

                PrimaryButton("Add a login", action: onAdd)
            }
            .frame(maxWidth: .infinity)
            .padding(.horizontal, 24)
            .padding(.vertical, 28)
        }
    }
}
