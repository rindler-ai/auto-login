# macOS shell (menu-bar agent)

macOS reuses the **exact same SwiftUI sources** as iOS (`../ios/Sources/*.swift`)
and the **`macos` slice** of `Custody.xcframework` — both are produced by the one
`make ios` command (`gomobile bind -target=ios,iossimulator,macos`). There is no
separate build.

```sh
cd daemon
make ios     # same xcframework carries the macOS slice
```

What differs from iOS is only the app *shape*: `CustodyApp.swift` renders a
`MenuBarExtra` (a background menu-bar agent) under `#if os(macOS)` instead of a
`WindowGroup`. Storage is the login Keychain; release authorization is automatic
via the same `AutoApprover` (no Touch ID / tap — a verified ping is authorized by
the server signature the Go core already checked).

## One-time setup

1. Add a **macOS App** target to the same Xcode project (or a multiplatform target)
   and include `../ios/Sources/*.swift`.
2. Embed & Sign `Custody.xcframework` (it already contains the macOS slice).
3. Signing & Capabilities → Keychain Sharing; enable **App Sandbox** with outgoing
   network (client) for the hub wss. (No `NSFaceIDUsageDescription` — no biometrics.)
4. To run headless at login, mark it a **LSUIElement / menu-bar agent** (no dock
   icon) and add it to Login Items — or ship the desktop daemon instead
   (`../desktop`), which needs no Xcode.

Code, pairing, and the Approver/SecretSource all live in `../ios` — read that
README for the full file guide. Signing/notarization: `../../BUILD.md` §(d)/(e).
