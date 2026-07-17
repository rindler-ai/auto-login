// OnboardingView — the first-run intro shown before Pair, ported from the
// Compose `ui/OnboardingScreen.kt`.
// A 3-slide horizontal pager that states the custody promise: credentials are
// stored on-device, every release is approved, and Auto-Login retains no vault copy.
//
// Pure UI: the ONLY input is `onDone: () -> Void`, fired by Skip and by the CTA on
// the last slide. The parent (CustodyApp's nav shell) owns the onboarded flag and
// the advance to Pair/Home — this view never touches the store, Go, or Keychain.
// Every visual token comes from Theme.swift; the shared PrimaryButton from
// Components.swift.
//
// Compiles for BOTH iOS (WindowGroup) and macOS (MenuBarExtra) from ONE code path:
// the pager is a cross-fading ZStack of one slide (not a `.page` TabView, which
// collapses the page height and clips the slide), advanced by the Continue CTA on
// every platform and by a horizontal swipe on iOS. The Android hardware-back that
// stepped the pager is divergent-by-design on iOS.

import SwiftUI

struct OnboardingView: View {
    /// Marks the device onboarded and advances the flow (Pair, or Home if paired).
    /// Fired by "Skip" and by the CTA on the last slide — the same terminal action.
    let onDone: () -> Void

    @State private var page = 0

    private var last: Bool { page == Self.slides.count - 1 }

    var body: some View {
        VStack(spacing: 0) {
            // Header — a single right-aligned "Skip" that ends onboarding immediately.
            HStack {
                Spacer()
                Button(action: onDone) {
                    Text("Skip")
                        .autoLoginText(.labelLarge)
                        .foregroundStyle(AutoLoginColors.onSurfaceVariant)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 8)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
            .padding(.top, 8)

            // Pager — fills the remaining height (Compose weight(1f)).
            pager
                .frame(maxWidth: .infinity, maxHeight: .infinity)

            // Capsule page indicator — active dot widens to 22pt in primary, the rest
            // are 7pt in outline; width + color animate on page change.
            HStack(spacing: 6) {
                ForEach(0..<Self.slides.count, id: \.self) { i in
                    Capsule()
                        .fill(page == i ? AutoLoginColors.primary : AutoLoginColors.outline)
                        .frame(width: page == i ? 22 : 7, height: 7)
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 16)
            .animation(.easeInOut(duration: 0.25), value: page)

            // CTA — advances to the next slide, or finishes on the last one.
            PrimaryButton(last ? "Get started" : "Continue") {
                if last {
                    onDone()
                } else {
                    withAnimation(.easeInOut(duration: 0.32)) { page += 1 }
                }
            }
            .padding(.bottom, 20)
        }
        .padding(.horizontal, 28)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(AutoLoginColors.background)
    }

    // MARK: Pager (platform-split)

    // The slide pager. Deliberately NOT a `.page` TabView: on iOS that style collapses
    // the page to a short intrinsic height that does not honor maxHeight:.infinity, so
    // the slide's emblem (top) and body (tail) get clipped. Instead — matching the other
    // screens' proven full-height pattern (PairingView: a VStack of Spacers under
    // maxHeight:.infinity) — one slide fills the flexible region via its own Spacers and
    // cross-fades on change; a horizontal drag advances/retreats the page (the swipe the
    // TabView used to give), while the CTA and the page indicator drive `page` too. One
    // code path for iOS and macOS.
    private var pager: some View {
        ZStack {
            ForEach(Array(Self.slides.enumerated()), id: \.offset) { index, slide in
                if index == page {
                    slideView(slide)
                        .transition(.opacity)
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        #if os(iOS)
        .contentShape(Rectangle())
        .gesture(
            DragGesture(minimumDistance: 24)
                .onEnded { value in
                    let dx = value.translation.width
                    if dx <= -48, page < Self.slides.count - 1 {
                        withAnimation(.easeInOut(duration: 0.3)) { page += 1 }
                    } else if dx >= 48, page > 0 {
                        withAnimation(.easeInOut(duration: 0.3)) { page -= 1 }
                    }
                }
        )
        #endif
    }

    // MARK: One slide

    /// Emblem + title + body, vertically centered by the two flexible Spacers. Fills the
    /// flexible region the parent VStack allots (maxHeight:.infinity), so the Spacers
    /// balance the block at center — the same fill pattern PairingView uses.
    private func slideView(_ slide: Slide) -> some View {
        VStack(spacing: 0) {
            Spacer(minLength: 0)
            OnboardingArt(systemName: slide.icon)
            Spacer().frame(height: 22)
            Text(slide.title)
                .autoLoginText(.headlineMedium)
                .foregroundStyle(AutoLoginColors.onBackground)
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity, alignment: .center)
                .fixedSize(horizontal: false, vertical: true)
            Spacer().frame(height: 14)
            Text(slide.body)
                .autoLoginText(.bodyLarge)
                .foregroundStyle(AutoLoginColors.onSurfaceVariant)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 4)
                .frame(maxWidth: .infinity, alignment: .center)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    // MARK: Slides

    private struct Slide {
        let icon: String
        let title: String
        let body: String
    }

    // Slide order + SF Symbol (Material rounded -> SF): Shield -> lock.shield.fill,
    // Bolt -> bolt.fill, CloudOff -> icloud.slash.fill. Copy is verbatim from Kotlin.
    private static let slides: [Slide] = [
        Slide(
            icon: "lock.shield.fill",
            title: "Stored on your phone",
            body: "Credentials are encrypted at rest in this device's secure storage. The hub doesn't keep a hosted credential copy."
        ),
        Slide(
            icon: "bolt.fill",
            title: "Hands-free by design",
            body: "When one of your logins needs a credential, the app releases it automatically — no tap required. It releases only the specific username, password or one-time code that login asked for."
        ),
        Slide(
            icon: "icloud.slash.fill",
            title: "Encrypted for one login",
            body: "Each approved release is sealed end-to-end to that login's single-use key. It transits the hub in memory and isn't stored in its credential vault."
        ),
    ]
}

// MARK: - OnboardingArt

/// A crafted hero emblem: concentric emerald halos (soft glow) under a crisp defining
/// ring, with the slide's SF Symbol larger and centered. Reads as intentional art, not
/// a stock glyph dropped into a flat circle. Ported inline from OnboardingScreen.kt's
/// private OnboardingArt (Canvas + Icon).
private struct OnboardingArt: View {
    let systemName: String

    var body: some View {
        ZStack {
            Canvas { context, size in
                let c = min(size.width, size.height) / 2       // 92 at 184pt
                let center = CGPoint(x: size.width / 2, y: size.height / 2)
                func disc(_ radius: CGFloat) -> Path {
                    Path(ellipseIn: CGRect(
                        x: center.x - radius, y: center.y - radius,
                        width: radius * 2, height: radius * 2
                    ))
                }
                context.fill(disc(c), with: .color(AutoLoginColors.primary.opacity(0.05)))          // soft outer glow
                context.fill(disc(c * 0.72), with: .color(AutoLoginColors.primary.opacity(0.09)))   // mid glow
                context.fill(disc(c * 0.46), with: .color(AutoLoginColors.primary.opacity(0.14)))   // inner disc under icon
                context.stroke(                                                                    // crisp defining ring
                    disc(c * 0.72),
                    with: .color(AutoLoginColors.primary.opacity(0.22)),
                    lineWidth: 1.5
                )
            }
            Image(systemName: systemName)
                .font(.system(size: 50))
                .foregroundStyle(AutoLoginColors.primary)
        }
        .frame(width: 120, height: 120)
    }
}
