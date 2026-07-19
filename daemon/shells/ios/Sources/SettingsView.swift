// SettingsView — the device/settings leaf screen reached from Home's gear action,
// ported from the Compose `ui/SettingsScreen.kt`.
//
// It grounds the user with a "this device" identity card, then offers two
// management actions: re-pair (route to Pair, switch account) and reset
// (wipe all logins + unpair, guarded by a confirmation alert). A quiet trust
// footer anchors the bottom; back returns to Home.
//
// Pure UI over the shared Theme/Components + Keychain. Callbacks are plain
// closures wired by the nav shell; this file does NOT touch navigation itself.
//
// PARITY: like Android's reset-confirm (RelayService.stop(ctx) FIRST, then wipe),
// the destructive reset here delegates the whole teardown to the nav shell via
// onReset, which stops the live relay session BEFORE erasing the Keychain — so no
// authenticated session outlives an "erase everything". This screen only confirms
// and delegates; it does not touch the session or the store itself.
// The device-identity string also differs by necessity: Android reads
// Build.MANUFACTURER/Build.MODEL; iOS substitutes UIDevice.model (no manufacturer
// field) / macOS Host.localizedName — a presentation substitution, not a feature
// divergence.

import SwiftUI
import Foundation
#if os(iOS)
import UIKit
#endif

/// The app-specific privacy policy. App Store Guideline 5.1.1(i) requires this
/// link to be reachable from INSIDE the app, not just from the store listing —
/// so this row is a submission requirement, not a nicety. Google Play wants the
/// same URL in its Data safety declaration, so both shells point here (the row
/// is a PARITY shared surface; keep the copy identical to Android's).
private let privacyPolicyURL = Config.privacyPolicyURL

struct SettingsView: View {
    let onBack: () -> Void
    let onRepair: () -> Void
    let onReset: () -> Void

    @Environment(\.openURL) private var openURL

    @State private var confirmReset = false
    @State private var showingSMSSetup = false

    // Device identity string. Android uses Build.MANUFACTURER + Build.MODEL; iOS has
    // no manufacturer field so we substitute UIDevice.model (e.g. "iPhone") /
    // macOS Host.localizedName. UIDevice.name needs an entitlement on iOS 16+ — avoid.
    private var deviceName: String {
        #if os(iOS)
        return UIDevice.current.model
        #elseif os(macOS)
        return Host.current().localizedName ?? "This Mac"
        #else
        return "This device"
        #endif
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            TopBar("Settings", onBack: onBack)

            Spacer().frame(height: 16)

            // This device — grounds the screen with real context.
            AppCard {
                HStack(spacing: 0) {
                    IconChip("iphone")
                    Spacer().frame(width: 14)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(deviceName)
                            .autoLoginText(.titleMedium)
                            .foregroundStyle(AutoLoginColors.onSurface)
                        Text("Paired · credentials stored on this device")
                            .autoLoginText(.labelMedium)
                            .foregroundStyle(AutoLoginColors.onSurfaceVariant)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    Spacer(minLength: 0)
                }
                .padding(16)
            }

            Spacer().frame(height: 20)

            Text("MANAGE")
                .autoLoginText(.labelSmall)
                .foregroundStyle(AutoLoginColors.onSurfaceVariant)
                .padding(.leading, 4)
                .padding(.bottom, 8)

            AppCard {
                VStack(spacing: 0) {
                    SettingRow(
                        icon: "arrow.clockwise",
                        title: "Re-pair this device",
                        subtitle: "Connect to a different account",
                        action: onRepair
                    )
                    InsetDivider()
                    SettingRow(
                        icon: "message.fill",
                        title: "Set up SMS auto-fill",
                        subtitle: "Auto-capture 2FA codes texted to this phone",
                        action: { showingSMSSetup = true }
                    )
                    InsetDivider()
                    SettingRow(
                        icon: "lock.shield",
                        title: "Privacy Policy",
                        subtitle: "How your logins and codes are handled",
                        action: { openURL(privacyPolicyURL) }
                    )
                    InsetDivider()
                    SettingRow(
                        icon: "trash",
                        title: "Reset device",
                        subtitle: "Erase all logins and start over",
                        danger: true,
                        action: { confirmReset = true }
                    )
                }
            }

            Spacer()

            Text("Auto Login · Encrypted on-device storage")
                .autoLoginText(.labelMedium)
                .foregroundStyle(AutoLoginColors.onSurfaceVariant)
                .frame(maxWidth: .infinity, alignment: .center)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.bottom, 24)
        }
        .padding(.horizontal, 20)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(AutoLoginColors.background)
        .alert("Reset this device?", isPresented: $confirmReset) {
            Button("Erase everything", role: .destructive) {
                // The nav shell owns the teardown order: stop the live relay session,
                // then Keychain.resetAll(), then route to onboarding (see CustodyApp's
                // .settings onReset). This screen just confirms and delegates.
                onReset()
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This erases every saved login and unpairs the device. It can't be undone.")
        }
        .sheet(isPresented: $showingSMSSetup) {
            SMSRelaySetupView(onDone: { showingSMSSetup = false })
        }
    }
}

// MARK: - SettingRow

/// One tappable management row: tinted IconChip + title/subtitle + trailing
/// disclosure chevron. The WHOLE row (its 16pt-padded bounds) is the hit target.
/// `danger` recolors the title + icon to the error red (Android's `danger` flag).
private struct SettingRow: View {
    let icon: String
    let title: String
    let subtitle: String
    var danger: Bool = false
    let action: () -> Void

    var body: some View {
        let tint = danger ? AutoLoginColors.error : AutoLoginColors.primary
        Button(action: action) {
            HStack(spacing: 0) {
                IconChip(icon, tint: tint, bg: tint.opacity(0.13))
                Spacer().frame(width: 14)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .autoLoginText(.titleMedium)
                        .foregroundStyle(danger ? AutoLoginColors.error : AutoLoginColors.onSurface)
                    Text(subtitle)
                        .autoLoginText(.bodyMedium)
                        .foregroundStyle(AutoLoginColors.onSurfaceVariant)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Image(systemName: "chevron.right")
                    .font(.system(size: 18))
                    .foregroundStyle(AutoLoginColors.onSurfaceVariant)
            }
            .padding(16)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

// MARK: - InsetDivider

/// The hairline between MANAGE rows, inset 68pt from the leading edge so it
/// starts under the row text rather than the icon (mirrors Compose's Divider).
private struct InsetDivider: View {
    var body: some View {
        Rectangle()
            .fill(AutoLoginColors.outlineVariant)
            .frame(height: 1)
            .frame(maxWidth: .infinity)
            .padding(.leading, 68)
    }
}
