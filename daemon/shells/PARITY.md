# Shell parity contract

The single source both custody shells implement. The parity drift check and the
`scripts/verify-shell-ui.sh` helper both read the machine block at the bottom.
Model: **parity on the shared custody core; automated 2FA capture divergent by
design**.

## File map (shared surfaces only)

| Android | iOS/macOS |
|---|---|
| `MainActivity.kt` (+ screen composables) | `CustodyApp.swift` (+ screen views) |
| `KeystoreSecretSource.kt` | `KeychainSecretSource.swift` |
| `AutoApprover.kt` | `AutoApprover.swift` |

SMS-capture files are platform-specific and intentionally **not** mapped.

## Confirmed gomobile symbol names

- **Android** (`-javapkg=ai.rindler`): `ai.rindler.mobile.Mobile.{start,pair,generateDeviceKey,devicePublicKey,extractOTPCode}`, structs `Session` + `PairResult`, interfaces `SecretSource`/`Approver`/`CodeExpectationSink`. `start` takes the sink as a trailing arg; it fires `onExpectingSMSCode(site, ttlSeconds)` on an authenticated `sms_otp_code` ping so the Android background SMS reader arms only while a login awaits a code. iOS passes it `nil` (no SMS reading).
- **iOS/macOS** (confirmed by `make ios` on Xcode 26.6 / gomobile+gobind current as of 2026-07-13, inspecting `Custody.xcframework/*/Custody.framework/Headers/Mobile.objc.h`): Swift module **`Custody`** (`import Custody`). Free functions **`MobileStart(hubURL:deviceToken:deviceKeyB64:serverPubkeyB64:src:appr:codeSink:error:)`** (`codeSink` is a `MobileCodeExpectationSinkProtocol?`; iOS passes `nil`), **`MobileGenerateDeviceKey(error:)`**, **`MobileDevicePublicKey(_:error:)`**, **`MobilePair(pairURL:pairingCode:deviceName:platform:devicePubkeyB64:error:)`**; classes **`MobileSession`** (`.stop()`) and **`MobilePairResult`** (`.deviceToken` / `.serverPubkey`). The assumed symbol names (`MobileStart`, `MobileSession`, `MobileGenerateDeviceKey`, `MobileDevicePublicKey`, `MobilePair`) were all **correct as written**. Two things needed correcting, found only by compiling against the real framework:
  1. **Protocol name needs a `Protocol` suffix.** gomobile's ObjC header emits BOTH a `@protocol MobileApprover`/`@protocol MobileSecretSource` (for native-side conformance) AND a same-named `@interface MobileApprover : NSObject <...>`/`@interface MobileSecretSource : NSObject <...>` class (the Go-side wrapper, used when a Go interface value crosses back to native — harmless in ObjC's separate class/protocol namespaces, but Swift imports both into one namespace). Swift's Clang importer disambiguates by importing the **protocol** as `MobileApproverProtocol` / `MobileSecretSourceProtocol`, leaving the bare name for the class. `final class AutoApprover: NSObject, MobileApprover` therefore fails with *"multiple inheritance from classes 'NSObject' and 'MobileApprover'"* — native conformances must read `NSObject, MobileApproverProtocol` / `NSObject, MobileSecretSourceProtocol`. Applies to `Sources/AutoApprover.swift` + `Sources/KeychainSecretSource.swift`.
  2. **Protocol method params are `Optional`, matching the ObjC `_Nullable`.** `approve(_ site: String?, kind: String?) -> Bool` and `lookup(_ site: String?) -> String`, not the non-optional `String` first assumed. Fixed alongside (1).
  3. **These are plain C functions, not Objective-C methods, so Swift's automatic `NSError **` → `throws` bridging does NOT apply** — there is no `swift_error` attribute anywhere in the generated headers. `MobileStart`/`MobileGenerateDeviceKey`/`MobileDevicePublicKey`/`MobilePair` import into Swift as ordinary functions with a trailing `NSErrorPointer` parameter, NOT as `throws`. A `try MobileStart(...)` / `try MobileGenerateDeviceKey()` call fails to compile (`error: missing argument for parameter #6/#1 in call`, Xcode's own fix-it inserts `, <#NSErrorPointer#>`). The real call shape is `var err: NSError?; let session = MobileStart(hubURL, token, key, KeychainSecretSource(), AutoApprover(), &err)` (check `err` afterward), used at the call sites in `Sources/CustodyApp.swift` and `Sources/PairingView.swift`.

## Shared behavior rules

- Approval is **uniform and automatic on every shell** (`AutoApprover` on iOS/macOS/Android): a release is authorized with NO per-release user interaction (a server-signed ping is authorized by construction; the frictionless, never-reopen-the-app, autonomous-login design). There is no biometric/tap gate on any platform. The real gate is cryptographic: every release requires a `SecretPing` verified against the paired server pubkey, which the Go core checks before it ever calls the approver.
- A missing Approver always declines. Android's always-on service auto-approves verified pings whether or not its activity is foreground; the headless desktop binary has no approval surface, so it (Approve=nil) releases nothing.
- The bound mobile Approver is boolean-only, so device pings currently support
  `username`, `password`, and `totp_code`. `email_otp_code`, `sms_otp_code`, and
  `manual_code` decline at the client; the iOS manual/SMS submit routes are a
  separate rendezvous and do not supply a `SecretPing` callback value.
- `approve(site,kind)` / `lookup(site)` bridges **block** the Go caller thread until answered.
- Credential-JSON contract: `{"username","password","totp":{"Secret":"<base64 of the RAW, already base32-decoded, seed>","Digits":6,"Period":30,"Algorithm":"SHA1"}}` — `totp` optional.
- The secret source owns **all writes** and the site index; Go never writes.
- Device-identity keys are reserved names (`rindler-meta:*`), never a site host.
- **Pairing persists THREE things** (`rindler-meta:device-token`, `rindler-meta:device-key`,
  `rindler-meta:server-pubkey`), and "Reset device" wipes all three. The server pubkey
  from `pair()` is what the Go core verifies every `SecretPing` against before sealing a
  credential to the worker key the ping names. **Fail-closed:** a device holding
  no server pubkey declines every ping, so `start()` refuses to run without one and each
  shell routes the user to re-pair. There is no "unsigned pings still allowed" path.

## Shared surfaces — MUST mirror

Android (`MainActivity.kt`) is the lead shell; its copy below is canonical and iOS mirrors it verbatim, native look.

- **PAIR** — title "Pair this device"; subtitle "Mint a code at your hub → Settings → Devices, then enter it here."; field placeholder "Pairing code"; button "Pair"; error prefix "Pairing failed: ".
- **ENROLL** — title "Add a login"; fields "Site (e.g. instacart.com)", "Username / email", "Password"; button "Save to this device".
- **HOME** — header "Auto-Login"; relay button "Pause protection"/"Resume protection"; status "Active"/"Paused" with subtitle "Your logins are ready when the hub needs them"; saved-logins section header "Saved logins" (with a separate count); empty-list text "No logins yet" + "Add a site and Auto-Login can sign you in whenever you ask."; list item prefix "• " (bullet + space before the site). (There is NO approval status — releases are automatic; see "Shared behavior rules".)
- **Manual code entry** — title "Enter code"; field "2FA code"; button "Submit".
- **Privacy Policy (Settings row)** — title "Privacy Policy"; subtitle "How your logins and codes are handled"; opens `https://your-hub.example/privacy`. Not cosmetic: Apple Guideline 5.1.1(i) requires the policy to be reachable from inside the app, and Play wants the same URL in its Data safety declaration. Do not remove it from either shell.

## Known reconciliation gaps

- **TOTP enrollment.** iOS's ENROLL screen has a working "TOTP secret (optional)" field (base32 decode + storage); Android's `EnrollScreen` has none. iOS is intentionally ahead here — Android needs to add it (a mirror nudge should fire on `MainActivity.kt` until it does). Verified still open 2026-07-14.

## Divergent-by-design surfaces — NEVER nudged

When a path matches both a shared and a divergent glob, the divergent glob wins — the change is treated as divergent and never nudged.

- **Automated SMS-2FA capture.** iOS: Shortcuts-setup wizard + per-user webhook + App Intent + automation-health/needs-unlock status (`shells/ios/**/sms/**`). Android: native capture (`shells/android/**/sms/**`). No screen mapping between them.

  > Android uses the **SMS User Consent API** (`SmsRetriever.startSmsUserConsent`), which requires **no permission**. This is now true; it was false until. The app previously declared `RECEIVE_SMS` with a `BroadcastReceiver` on `SMS_RECEIVED_ACTION`, which Google Play names an **invalid** use case for account verification — a guaranteed rejection. Do not reintroduce any SMS permission: the shipped APK must declare none. (The silent SMS **Retriever** API is not available to us — it only delivers messages carrying an 11-char hash of our own app, and the sending bank controls the body.)
  >
  > **Consent cannot be silent, and its dialog needs an Activity.** Android 10+ blocks background activity launches, so when a code arrives with the app backgrounded we post a high-importance notification carrying the consent Intent (`SmsConsentActivity`); foreground, we launch it directly. The 5-minute listener window is re-armed continuously, not armed once.
  >
  > The correction that matters beyond Android: **neither platform permits silent third-party OTP reading.** iOS shows a non-suppressible Shortcuts banner per message; Android shows a system consent sheet per message. They are symmetric, not "clean Android vs. compromised iOS", and **manual code entry is the reliability floor on both**.
- **Impl divergences** (behavior shared, code not): Keystore/EncryptedSharedPreferences ↔ Keychain; gomobile naming; push-wake APNs ↔ FCM (phase 2). (Approval is NOT divergent — both `AutoApprover.kt` and `AutoApprover.swift` auto-approve a verified ping with no user interaction.)

## Machine block (consumed by tooling — keep in sync with the lists above)

```json auto-login-parity
{
  "sharedGlobs": [
    "daemon/shells/android/app/src/main/java/ai/rindler/autologin/MainActivity.kt",
    "daemon/shells/android/app/src/main/java/ai/rindler/autologin/KeystoreSecretSource.kt",
    "daemon/shells/android/app/src/main/java/ai/rindler/autologin/AutoApprover.kt",
    "daemon/shells/ios/Sources/**"
  ],
  "divergentGlobs": [
    "daemon/shells/android/**/sms/**",
    "daemon/shells/ios/**/sms/**",
    "daemon/shells/**/push/**"
  ]
}
```
