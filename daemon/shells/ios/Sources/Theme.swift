// Theme — the SOLE design-token source for the custody shell, ported from the
// Compose theme (`ui/theme/Color.kt` + `Theme.kt` + `Type.kt`).
// A hand-tuned "security you own" palette:
// warm stone neutrals (never clinical grey) + ONE pinned emerald trust accent.
//
// Pure tokens — no state, no bindings, no Go. Every screen/component imports these
// names instead of raw sizes/hexes. Compiles for BOTH iOS (WindowGroup) and macOS
// (MenuBarExtra): the light/dark swap uses a dynamic UIColor on iOS and a dynamic
// NSColor bridge on macOS, so callers never branch on the color scheme.

import SwiftUI
#if os(iOS)
import UIKit
#elseif os(macOS)
import AppKit
#endif

// MARK: - Color(hex:)

extension Color {
    /// Build a Color from a 0xAARRGGBB literal (ARGB, bytes / 255) — mirrors Compose's
    /// `Color(0xFFRRGGBB)`. Pass full alpha (0xFF…) for opaque swatches.
    init(hex: UInt32) {
        let a = Double((hex >> 24) & 0xFF) / 255
        let r = Double((hex >> 16) & 0xFF) / 255
        let g = Double((hex >> 8) & 0xFF) / 255
        let b = Double(hex & 0xFF) / 255
        self.init(.sRGB, red: r, green: g, blue: b, opacity: a)
    }
}

// MARK: - Platform ARGB bridges (private)

#if os(iOS)
private extension UIColor {
    convenience init(argb: UInt32) {
        self.init(
            red: CGFloat((argb >> 16) & 0xFF) / 255,
            green: CGFloat((argb >> 8) & 0xFF) / 255,
            blue: CGFloat(argb & 0xFF) / 255,
            alpha: CGFloat((argb >> 24) & 0xFF) / 255
        )
    }
}
#elseif os(macOS)
private extension NSColor {
    convenience init(argb: UInt32) {
        self.init(
            srgbRed: CGFloat((argb >> 16) & 0xFF) / 255,
            green: CGFloat((argb >> 8) & 0xFF) / 255,
            blue: CGFloat(argb & 0xFF) / 255,
            alpha: CGFloat((argb >> 24) & 0xFF) / 255
        )
    }
}
#endif

/// Resolve a role that auto-swaps on trait change WITHOUT branching `@Environment(\.colorScheme)`
/// at call sites (mirrors Compose's whole-scheme swap). iOS uses a dynamic UIColor; macOS a
/// dynamic NSColor; both bridged back to a SwiftUI Color.
private func dynamicColor(light: UInt32, dark: UInt32) -> Color {
    #if os(iOS)
    return Color(uiColor: UIColor { traits in
        traits.userInterfaceStyle == .dark ? UIColor(argb: dark) : UIColor(argb: light)
    })
    #elseif os(macOS)
    return Color(nsColor: NSColor(name: nil) { appearance in
        let isDark = appearance.bestMatch(from: [.aqua, .darkAqua]) == .darkAqua
        return isDark ? NSColor(argb: dark) : NSColor(argb: light)
    })
    #else
    // Fallback for any other platform slice: pick the light value.
    return Color(hex: light)
    #endif
}

// MARK: - Colors

/// Semantic color roles. Every role auto-resolves light/dark except `primary`/`onPrimary`,
/// which are PINNED identical in both schemes on purpose (the accent never drifts).
enum AutoLoginColors {

    // Raw palette (Color.kt) — UInt32 ARGB, the single numeric source of truth for the
    // roles below AND the raw swatches at the bottom. Full-alpha, mirrors Compose exactly.
    private enum Hex {
        // Brand / accent — emerald ("your keys, safe, go")
        static let emerald50: UInt32 = 0xFFE9F7F1
        static let emerald100: UInt32 = 0xFFC9EBDD
        static let emerald400: UInt32 = 0xFF34C79A
        static let emerald500: UInt32 = 0xFF13A97D
        static let emerald600: UInt32 = 0xFF0E8466 // brand accent — pinned in BOTH schemes
        static let emerald700: UInt32 = 0xFF0B6A52
        static let emerald900: UInt32 = 0xFF083F31
        // Warm stone neutrals (light)
        static let stone0: UInt32 = 0xFFFFFFFF
        static let paper: UInt32 = 0xFFF6F6F2 // light bg — warmth, not flat white
        static let stone100: UInt32 = 0xFFECECE6
        static let stone200: UInt32 = 0xFFDEDED6
        static let stone300: UInt32 = 0xFFC9C9BF
        static let stone500: UInt32 = 0xFF6C6F68 // declared-but-unused in either scheme
        static let stone600: UInt32 = 0xFF52554F
        static let ink: UInt32 = 0xFF171A17 // primary text on light
        // Dark scheme neutrals (green-undertoned near-black)
        static let night: UInt32 = 0xFF0B0E0D
        static let nightSurface: UInt32 = 0xFF171C1A
        static let nightSurface2: UInt32 = 0xFF272E2A // field fill / elevated
        static let nightOutline: UInt32 = 0xFF49534C // card + field borders
        static let nightHair: UInt32 = 0xFF2A312D // subtle dividers only
        static let mist: UInt32 = 0xFFE9ECEA // primary text on dark
        static let mistDim: UInt32 = 0xFFA6AFA9 // secondary text / placeholders
        // Semantic
        static let amber: UInt32 = 0xFFC97A16
        static let amberDark: UInt32 = 0xFFE0A044
        static let danger: UInt32 = 0xFFD6473F
        static let dangerDark: UInt32 = 0xFFF08078
    }

    // --- Core roles (light | dark) ---
    static let background = dynamicColor(light: Hex.paper, dark: Hex.night)
    static let onBackground = dynamicColor(light: Hex.ink, dark: Hex.mist)

    static let surface = dynamicColor(light: Hex.stone0, dark: Hex.nightSurface)
    static let onSurface = dynamicColor(light: Hex.ink, dark: Hex.mist)
    static let surfaceVariant = dynamicColor(light: Hex.stone100, dark: Hex.nightSurface2)
    static let onSurfaceVariant = dynamicColor(light: Hex.stone600, dark: Hex.mistDim)

    // Surface-container ladder (Theme.kt).
    static let surfaceContainerLowest = dynamicColor(light: Hex.stone0, dark: Hex.night)
    static let surfaceContainerLow = dynamicColor(light: Hex.stone0, dark: Hex.nightSurface)
    static let surfaceContainer = dynamicColor(light: Hex.stone0, dark: Hex.nightSurface)
    static let surfaceContainerHigh = dynamicColor(light: Hex.stone100, dark: Hex.nightSurface2) // field fill (dark), paused badge
    static let surfaceContainerHighest = dynamicColor(light: Hex.stone200, dark: Hex.nightSurface2)

    static let outline = dynamicColor(light: Hex.stone300, dark: Hex.nightOutline) // card / field borders
    static let outlineVariant = dynamicColor(light: Hex.stone200, dark: Hex.nightHair) // hairline dividers

    // Accent — PINNED identical in both schemes (the accent never drifts).
    static let primary = Color(hex: Hex.emerald600)
    static let onPrimary = Color(hex: Hex.stone0)
    static let primaryContainer = dynamicColor(light: Hex.emerald50, dark: Hex.emerald900)
    static let onPrimaryContainer = dynamicColor(light: Hex.emerald900, dark: Hex.emerald100)

    static let secondary = dynamicColor(light: Hex.emerald700, dark: Hex.emerald600)
    static let onSecondary = Color(hex: Hex.stone0)
    static let secondaryContainer = dynamicColor(light: Hex.emerald100, dark: Hex.emerald900)
    static let onSecondaryContainer = dynamicColor(light: Hex.emerald900, dark: Hex.emerald100)

    static let tertiary = dynamicColor(light: Hex.stone600, dark: Hex.mistDim)
    static let onTertiary = dynamicColor(light: Hex.stone0, dark: Hex.night)

    static let error = dynamicColor(light: Hex.danger, dark: Hex.dangerDark)
    static let onError = dynamicColor(light: Hex.stone0, dark: Hex.night)

    static let scrim = dynamicColor(light: Hex.ink, dark: Hex.night)

    // --- Raw swatches, mapped to NO semantic role today (kept for completeness /
    // future use, per Color.kt). Stone500 / Emerald400 / Emerald500 are the explicitly
    // declared-but-unused ones; Amber* are semantic-but-unmapped. ---
    static let emerald400 = Color(hex: Hex.emerald400)
    static let emerald500 = Color(hex: Hex.emerald500)
    static let stone500 = Color(hex: Hex.stone500)
    static let amber = Color(hex: Hex.amber)
    static let amberDark = Color(hex: Hex.amberDark)
}

// MARK: - Type

/// The 10-step type scale (Type.kt) on the system grotesque (SF Pro), plus a monospace
/// helper for the pairing code / secret-shaped values. SwiftUI splits font from letter
/// spacing, so each style carries a companion ABSOLUTE tracking (points = em × size) and
/// extra line spacing (Compose lineHeight − fontSize); apply all three at once with
/// `.autoLoginText(_:)`.
enum AutoLoginType {
    // 10 static Font helpers — reference by name, not raw size.
    static let displaySmall = Font.system(size: 30, weight: .bold)
    static let headlineMedium = Font.system(size: 24, weight: .bold)
    static let headlineSmall = Font.system(size: 20, weight: .semibold)
    static let titleLarge = Font.system(size: 18, weight: .semibold)
    static let titleMedium = Font.system(size: 15, weight: .semibold)
    static let bodyLarge = Font.system(size: 16, weight: .regular)
    static let bodyMedium = Font.system(size: 14, weight: .regular)
    static let labelLarge = Font.system(size: 15, weight: .semibold)
    static let labelMedium = Font.system(size: 12, weight: .medium)
    static let labelSmall = Font.system(size: 11, weight: .medium)

    // Monospace — pairing code + any secret-shaped value (reads intentional).
    static let mono = Font.system(size: 16, weight: .regular, design: .monospaced)

    /// Descriptor consumed by `.autoLoginText(_:)`: a font paired with its absolute tracking
    /// (negative on display/headline/title — the "makes large type feel crafted" trick) and
    /// extra line spacing.
    enum Style {
        case displaySmall, headlineMedium, headlineSmall, titleLarge, titleMedium
        case bodyLarge, bodyMedium, labelLarge, labelMedium, labelSmall

        var font: Font {
            switch self {
            case .displaySmall: return AutoLoginType.displaySmall
            case .headlineMedium: return AutoLoginType.headlineMedium
            case .headlineSmall: return AutoLoginType.headlineSmall
            case .titleLarge: return AutoLoginType.titleLarge
            case .titleMedium: return AutoLoginType.titleMedium
            case .bodyLarge: return AutoLoginType.bodyLarge
            case .bodyMedium: return AutoLoginType.bodyMedium
            case .labelLarge: return AutoLoginType.labelLarge
            case .labelMedium: return AutoLoginType.labelMedium
            case .labelSmall: return AutoLoginType.labelSmall
            }
        }

        /// Absolute letter spacing in points (Compose em × fontSize).
        var tracking: CGFloat {
            switch self {
            case .displaySmall: return -0.60  // -0.02em × 30
            case .headlineMedium: return -0.48 // -0.02em × 24
            case .headlineSmall: return -0.20  // -0.01em × 20
            case .titleLarge: return -0.18     // -0.01em × 18
            case .titleMedium: return 0
            case .bodyLarge: return 0
            case .bodyMedium: return 0
            case .labelLarge: return 0
            case .labelMedium: return 0.24     // +0.02em × 12
            case .labelSmall: return 0.66      // +0.06em × 11
            }
        }

        /// Extra leading on top of the font's intrinsic line height (Compose lineHeight − fontSize).
        var lineSpacing: CGFloat {
            switch self {
            case .displaySmall: return 6   // 36 − 30
            case .headlineMedium: return 6 // 30 − 24
            case .headlineSmall: return 6  // 26 − 20
            case .titleLarge: return 6     // 24 − 18
            case .titleMedium: return 5    // 20 − 15
            case .bodyLarge: return 8      // 24 − 16
            case .bodyMedium: return 7     // 21 − 14
            case .labelLarge: return 3     // 18 − 15
            case .labelMedium: return 4    // 16 − 12
            case .labelSmall: return 3     // 14 − 11
            }
        }
    }
}

extension View {
    /// Apply a named type style: font + absolute tracking + extra line spacing.
    func autoLoginText(_ style: AutoLoginType.Style) -> some View {
        self
            .font(style.font)
            .tracking(style.tracking)
            .lineSpacing(style.lineSpacing)
    }
}

// MARK: - Shape

/// Corner-radius scale (Theme.kt AppShapes) as continuous (Apple squircle) rounded
/// rectangles — the native-feel equivalent of Material's corners. Also exposes the raw
/// radii for `.cornerRadius(_:)` / inset math.
enum AutoLoginShape {
    static let xsRadius: CGFloat = 8
    static let smRadius: CGFloat = 12
    static let mdRadius: CGFloat = 16
    static let lgRadius: CGFloat = 22
    static let xlRadius: CGFloat = 28

    static let xs = RoundedRectangle(cornerRadius: xsRadius, style: .continuous)
    static let sm = RoundedRectangle(cornerRadius: smRadius, style: .continuous)
    static let md = RoundedRectangle(cornerRadius: mdRadius, style: .continuous)
    static let lg = RoundedRectangle(cornerRadius: lgRadius, style: .continuous)
    static let xl = RoundedRectangle(cornerRadius: xlRadius, style: .continuous)
}
