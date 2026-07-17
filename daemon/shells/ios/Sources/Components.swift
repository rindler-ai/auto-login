// Components — the shared SwiftUI view library for the custody shell, ported from
// the Compose component set (`ui/Components.kt`). Nine reusable, PURE-UI views that
// every screen (Pair/Enroll/Home/Settings/Onboarding) consumes, plus the shared
// press-scale ButtonStyle and the deterministic string-hash the site avatar needs.
//
// No store / Go / Keychain touch here — presentation only. Every visual token comes
// from Theme.swift (AutoLoginColors / AutoLoginType / AutoLoginShape). Compiles for BOTH
// iOS (WindowGroup) and macOS (MenuBarExtra): no UIKit-only APIs in the public
// surface; the only scheme branches are where Compose itself branched
// (`isSystemInDarkTheme()`) — the disabled-button affordance and the field fill.

import SwiftUI

// MARK: - ScaleButtonStyle

/// Gentle press feedback shared by both buttons: 0.98 scale while pressed. A
/// disabled `Button` never reports `isPressed`, so a disabled PrimaryButton simply
/// doesn't scale — which is exactly the Compose "press-scale only when enabled".
struct ScaleButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.98 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

// MARK: - PrimaryButton

/// Filled emerald CTA: full-width, 54pt, bold label, inline spinner while loading.
/// Disabled affordance is THEME-SPLIT (preserved from Compose): in LIGHT a dimmed
/// pale-emerald reads as "the action, not yet ready"; over near-black that tint
/// reads as an enabled-but-dim button, so DARK uses a neutral low-opacity veil +
/// clearly-dim label — obviously inactive.
struct PrimaryButton: View {
    let text: String
    let enabled: Bool
    let loading: Bool
    let action: () -> Void

    init(_ text: String, enabled: Bool = true, loading: Bool = false, action: @escaping () -> Void) {
        self.text = text
        self.enabled = enabled
        self.loading = loading
        self.action = action
    }

    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let dark = scheme == .dark
        let disabledBg = dark ? AutoLoginColors.onSurface.opacity(0.10) : AutoLoginColors.primary.opacity(0.30)
        let disabledContent = dark ? AutoLoginColors.onSurface.opacity(0.45) : AutoLoginColors.onPrimary.opacity(0.80)
        // Keyed on `enabled` (not `enabled && !loading`) so a loading button keeps the
        // active emerald look while it spins; interaction is gated separately below.
        let bg = enabled ? AutoLoginColors.primary : disabledBg
        let content = enabled ? AutoLoginColors.onPrimary : disabledContent

        Button {
            if !loading { action() }
        } label: {
            ZStack {
                if loading {
                    ProgressView()
                        .progressViewStyle(.circular)
                        .tint(AutoLoginColors.onPrimary)
                        .frame(width: 22, height: 22)
                } else {
                    Text(text).autoLoginText(.labelLarge)
                }
            }
            .frame(maxWidth: .infinity)
            .frame(height: 54)
            .foregroundStyle(content)
            .background(bg, in: AutoLoginShape.lg)
            .contentShape(AutoLoginShape.lg)
        }
        .buttonStyle(ScaleButtonStyle())
        .disabled(!enabled || loading)
    }
}

// MARK: - SecondaryButton

/// Quiet outlined action for secondary choices: full-width, 54pt, transparent with
/// a 1pt outline border and an optional leading view before the label.
struct SecondaryButton<Leading: View>: View {
    let text: String
    let leading: Leading
    let action: () -> Void

    init(_ text: String, @ViewBuilder leading: () -> Leading = { EmptyView() }, action: @escaping () -> Void) {
        self.text = text
        self.leading = leading()
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                leading
                Text(text).autoLoginText(.labelLarge)
            }
            .padding(.horizontal, 20)
            .frame(maxWidth: .infinity)
            .frame(height: 54)
            .foregroundStyle(AutoLoginColors.onSurface)
            .overlay(AutoLoginShape.lg.stroke(AutoLoginColors.outline, lineWidth: 1))
            .contentShape(AutoLoginShape.lg)
        }
        .buttonStyle(ScaleButtonStyle())
    }
}

// MARK: - AppTextField

/// Outlined field restyled to the theme: rounded (medium/16pt), a floating label
/// that lifts onto the top border when focused or filled, an emerald focus border,
/// red-on-error, and an optional trailing slot + supporting caption. Fill is white
/// in light and a raised container in dark so fields stand out from the near-black bg.
struct AppTextField<Trailing: View>: View {
    let label: String
    @Binding var text: String
    let isError: Bool
    let secure: Bool
    let font: Font
    let supportingText: String?
    let trailing: Trailing

    init(
        _ label: String,
        text: Binding<String>,
        isError: Bool = false,
        secure: Bool = false,
        font: Font = AutoLoginType.bodyLarge,
        supportingText: String? = nil,
        @ViewBuilder trailing: () -> Trailing = { EmptyView() }
    ) {
        self.label = label
        self._text = text
        self.isError = isError
        self.secure = secure
        self.font = font
        self.supportingText = supportingText
        self.trailing = trailing()
    }

    @Environment(\.colorScheme) private var scheme
    @FocusState private var focused: Bool

    private var floating: Bool { focused || !text.isEmpty }

    var body: some View {
        // Border brightens to emerald on focus, red on error; label follows the same rule.
        let borderColor: Color = isError ? AutoLoginColors.error : (focused ? AutoLoginColors.primary : AutoLoginColors.outline)
        let labelColor: Color = isError ? AutoLoginColors.error : (focused ? AutoLoginColors.primary : AutoLoginColors.onSurfaceVariant)
        let fill = scheme == .dark ? AutoLoginColors.surfaceContainerHigh : AutoLoginColors.surface

        VStack(alignment: .leading, spacing: 6) {
            ZStack(alignment: .topLeading) {
                HStack(spacing: 8) {
                    Group {
                        if secure {
                            SecureField("", text: $text)
                        } else {
                            TextField("", text: $text)
                        }
                    }
                    .textFieldStyle(.plain)
                    .font(font)
                    .foregroundStyle(AutoLoginColors.onSurface)
                    .tint(AutoLoginColors.primary)
                    .focused($focused)

                    trailing
                }
                .padding(.horizontal, 16)
                .frame(height: 56)
                .frame(maxWidth: .infinity)
                .background(fill, in: AutoLoginShape.md)
                .overlay(AutoLoginShape.md.stroke(borderColor, lineWidth: focused ? 2 : 1))

                // Floating / resting label. Drawn ABOVE the field so its fill-colored
                // knockout background hides the border stroke behind it when floating.
                Text(label)
                    .font(floating ? AutoLoginType.labelSmall : font)
                    .foregroundStyle(labelColor)
                    .lineLimit(1)
                    .padding(.horizontal, floating ? 4 : 0)
                    .background(floating ? fill : Color.clear)
                    .padding(.leading, floating ? 12 : 16)
                    .offset(y: floating ? -7 : 18)
                    .allowsHitTesting(false)
            }
            .animation(.easeInOut(duration: 0.15), value: floating)
            .animation(.easeInOut(duration: 0.15), value: focused)

            if let supportingText {
                Text(supportingText)
                    .autoLoginText(.labelMedium)
                    .foregroundStyle(isError ? AutoLoginColors.error : AutoLoginColors.onSurfaceVariant)
                    .padding(.horizontal, 4)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}

// MARK: - AppCard

/// A soft, bordered surface panel: full-width, large/22pt corner, 1pt outline, no
/// shadow. Wraps arbitrary content (callers supply their own inner padding).
struct AppCard<Content: View>: View {
    let content: Content

    init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    var body: some View {
        content
            .frame(maxWidth: .infinity)
            .background(AutoLoginColors.surface, in: AutoLoginShape.lg)
            .overlay(AutoLoginShape.lg.stroke(AutoLoginColors.outline, lineWidth: 1))
    }
}

// MARK: - PulseDot

/// A softly-pulsing status dot (the "live" signal). A 9pt circle whose opacity ramps
/// 0.35 -> 1.0 then RESTARTS (autoreverses:false — matches Compose's default
/// RepeatMode.Restart; autoreverses:true would be a subtle behavior drift).
struct PulseDot: View {
    let color: Color

    init(color: Color) {
        self.color = color
    }

    @State private var on = false

    var body: some View {
        Circle()
            .fill(color)
            .frame(width: 9, height: 9)
            .opacity(on ? 1.0 : 0.35)
            .animation(.easeInOut(duration: 1.1).repeatForever(autoreverses: false), value: on)
            .onAppear { on = true }
    }
}

// MARK: - SiteAvatar

/// A distinct per-site avatar: the domain's first letter over a rounded-square
/// (medium/16pt) tile tinted at 16% alpha, tint chosen deterministically from the
/// domain so every site is visually different. The tint index uses a stable
/// Java-`String.hashCode()` reimpl — NOT Swift `hashValue` (per-launch randomized,
/// which would reshuffle every site's color each app start).
struct SiteAvatar: View {
    let site: String
    let size: CGFloat

    init(site: String, size: CGFloat = 44) {
        self.site = site
        self.size = size
    }

    // A muted, premium palette (theme-independent literals; not in the color scheme).
    private static let tints: [Color] = [
        Color(hex: 0xFF0E8466), // emerald
        Color(hex: 0xFF2563C9), // blue
        Color(hex: 0xFF7C4DD6), // violet
        Color(hex: 0xFFC2410C), // rust
        Color(hex: 0xFFB4315E), // rose
        Color(hex: 0xFF0E7490), // teal
        Color(hex: 0xFF9A7A0E), // gold
    ]

    var body: some View {
        var clean = site.trimmingCharacters(in: .whitespacesAndNewlines)
        if clean.hasPrefix("www.") { clean.removeFirst(4) }
        let letter = clean.first(where: { $0.isLetter || $0.isNumber }).map { String($0).uppercased() } ?? "?"
        let count = Int32(Self.tints.count)
        let idx = Int(((javaHashCode(clean) % count) + count) % count)
        let tint = Self.tints[idx]

        return Text(letter)
            .autoLoginText(.titleMedium)
            .foregroundStyle(tint)
            .frame(width: size, height: size)
            .background(tint.opacity(0.16), in: AutoLoginShape.md)
    }
}

/// Java's `String.hashCode()` reimplemented: `h = 31*h + charValue` over UTF-16 code
/// units with Int32 overflow — a stable per-string hash (unlike Swift's `hashValue`,
/// which is seeded per process launch). Used by SiteAvatar to pick a stable tint.
func javaHashCode(_ s: String) -> Int32 {
    var h: Int32 = 0
    for u in s.utf16 {
        h = 31 &* h &+ Int32(u)
    }
    return h
}

// MARK: - TrustFooter

/// A quiet, centered trust line — anchors the bottom of a screen and reinforces the
/// one promise that matters for this app.
struct TrustFooter: View {
    var body: some View {
        HStack(spacing: 7) {
            Image(systemName: "lock.fill")
                .font(.system(size: 13))
                .foregroundStyle(AutoLoginColors.onSurfaceVariant)
            Text("Encrypted at rest · approved releases are end-to-end encrypted")
                .autoLoginText(.labelMedium)
                .foregroundStyle(AutoLoginColors.onSurfaceVariant)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 18)
    }
}

// MARK: - TopBar

/// A minimal top bar: optional back button, weight-1 title, optional trailing action.
struct TopBar<Action: View>: View {
    let title: String
    let onBack: (() -> Void)?
    let action: Action

    init(_ title: String, onBack: (() -> Void)? = nil, @ViewBuilder action: () -> Action = { EmptyView() }) {
        self.title = title
        self.onBack = onBack
        self.action = action()
    }

    var body: some View {
        HStack(spacing: 0) {
            if let onBack {
                Button(action: onBack) {
                    Image(systemName: "chevron.backward")
                        .font(.system(size: 20))
                        .foregroundStyle(AutoLoginColors.onSurface)
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Back")
            } else {
                Spacer().frame(width: 6)
            }

            Text(title)
                .autoLoginText(.titleLarge)
                .foregroundStyle(AutoLoginColors.onSurface)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.leading, onBack != nil ? 2 : 6)

            action
        }
        .frame(height: 56)
    }
}

// MARK: - IconChip

/// Small tinted icon chip used in list rows / headers: a rounded-square (medium/16pt)
/// tile with an SF Symbol centered at half the tile size. The default background is
/// the tint at 13% alpha (so the chip is the SAME hue in light + dark — the accent
/// never drifts between themes).
struct IconChip: View {
    let systemName: String
    let tint: Color
    let bg: Color?
    let size: CGFloat

    init(_ systemName: String, tint: Color = AutoLoginColors.primary, bg: Color? = nil, size: CGFloat = 40) {
        self.systemName = systemName
        self.tint = tint
        self.bg = bg
        self.size = size
    }

    var body: some View {
        Image(systemName: systemName)
            .font(.system(size: size * 0.5))
            .foregroundStyle(tint)
            .frame(width: size, height: size)
            .background(bg ?? tint.opacity(0.13), in: AutoLoginShape.md)
    }
}
