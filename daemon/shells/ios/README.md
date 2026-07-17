# iOS + macOS shell (SwiftUI)

> **Parity contract:** shared vs divergent surfaces, canonical copy, and the
> confirmed gomobile symbol names live in [`../PARITY.md`](../PARITY.md). Change a
> shared surface on one platform and the parity drift check flags a mirror; divergent
> surfaces (SMS/push) evolve independently. Verify the UI with
> `scripts/verify-shell-ui.sh ios`.

Thin SwiftUI app that wraps the shared Go custody core. All crypto/relay lives in
`Custody.xcframework` (built from `../../mobile` by `make ios`); this shell only
owns the two things Go must not touch: **secure storage** (Keychain / Secure
Enclave access control) and **per-release authorization** — which is automatic:
`AutoApprover` approves every verified ping with no tap, keeping logins hands-free.

## Build the framework first (one command, on a Mac)

```sh
cd daemon
make ios     # gomobile bind -> shells/ios/Custody.xcframework (ios + iossimulator + macos slices)
```

`make ios` needs a Mac with Xcode. It produces `shells/ios/Custody.xcframework`,
a single xcframework carrying the device, simulator, **and** macOS slices — the
macOS menu-bar app (see `../macos`) imports the same framework and the same
Swift sources below.

## Files

| File | Role |
|---|---|
| `Sources/CustodyApp.swift` | `@main` SwiftUI app. Status screen + Start/Stop; `MenuBarExtra` on macOS, `WindowGroup` on iOS. Calls `MobileStart` / `MobileSession.stop()`. |
| `Sources/KeychainSecretSource.swift` | `MobileSecretSource` backed by the iOS/macOS Keychain. Returns the per-site credential JSON the Go core parses. Also the low-level `Keychain` helper the app uses for the device token + Ed25519 key. |
| `Sources/AutoApprover.swift` | `MobileApprover` that auto-approves every verified ping (no per-release user interaction — releases are authorized by the server signature the Go core already verified). |
| `Sources/PairingView.swift` | First-run pairing: `MobileGenerateDeviceKey` → `MobileDevicePublicKey` → `MobilePair`, stores the returned device token in the Keychain. |

The `Sources/*.swift` files use real framework APIs and build as soon as
`Custody.xcframework` exists and is linked.

## Bound symbol naming (gomobile)

Go package `mobile` → Swift module `Custody` (the framework name). Exported Go
symbols are prefixed `Mobile`: `MobileStart`, `MobileSession`,
`MobileGenerateDeviceKey`, `MobileDevicePublicKey`, `MobilePair`. **Confirmed by
`make ios-app` against the real framework (see `../PARITY.md` for the full
writeup) — two corrections vs. what you'd guess from the Go source:**

- The callback protocols are `MobileSecretSourceProtocol` / `MobileApproverProtocol`
  (NOT the bare `MobileSecretSource` / `MobileApprover` — gomobile's ObjC header
  also emits a same-named class for each, so Swift's importer suffixes the
  protocol with `Protocol` to disambiguate), with `Optional` (`String?`) params.
- Functions that return a Go `error` do **NOT** import as throwing Swift
  functions — gomobile emits plain C functions, not Objective-C methods, so
  Swift's automatic `NSError **` → `throws` bridging doesn't apply. Each takes
  an explicit trailing `NSErrorPointer`: `var err: NSError?; let session =
  MobileStart(hubURL, token, key, secretSource, approver, &err)`.

`import Custody` at the top of each file.

## One-time Xcode setup

1. **New App target.** Xcode → File → New → Project → App (SwiftUI lifecycle).
   Delete the generated `App.swift`; add the four files under `Sources/` to the
   target (drag them in, "Copy items if needed" off — reference in place).
2. **Link the framework.** Drag `Custody.xcframework` into the project navigator,
   then target → General → *Frameworks, Libraries, and Embedded Content* → set it
   to **Embed & Sign**.
3. **Capabilities / Info.plist.**
   - Keychain Sharing capability (default access group is fine).
   - (No `NSFaceIDUsageDescription` needed — releases are auto-approved, no biometrics.)
4. **Bundle id + signing.** target → Signing & Capabilities → set your Bundle
   Identifier (e.g. `ai.rindler.autologin`) and your Team. See `../../BUILD.md`
   §(d)/(e) for Archive, notarization, TestFlight.
5. **Run.** Pick a device/simulator, ⌘R. First launch shows `PairingView`; paste a
   code from your hub → /settings/devices.

Signing, notarization, and store submission require your Apple Developer account
and can only run on your Mac — the full command sequence is in `../../BUILD.md`.

## SMS auto-fill (App-Store review)

`ForwardMessageIntent.swift` is a first-party **App Intent**, not a distributed
`.shortcut` file — the user's own iOS Shortcuts "Message" automation calls it
directly, so there is no "Allow Untrusted Shortcuts" gate to cross. **This app
never reads SMS itself; only the user's own Shortcut touches the message**,
which is the App-Store-legal boundary — do not add any SMS-reading API here.
`SMSRelaySetupView.swift` guides the ~2-minute manual setup + a verification
test; `ManualCodeView.swift` (always reachable from Home) is the reliability
floor when background capture doesn't fire.

Full submission checklist — App Privacy nutrition label, privacy-policy URL,
the Guideline 5.1.1(v) account-deletion framing (device-side "Reset device"
wipes everything including this webhook; a real but non-blocking gap where
`DELETE /v1/sms-relay/token` has no caller yet), and the Guideline 4.2 generic
code-sink framing (no bank names anywhere).
