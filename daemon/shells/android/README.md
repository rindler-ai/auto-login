# Android app (Jetpack Compose)

> **Parity contract:** shared vs divergent surfaces, canonical copy, and the
> gomobile symbol names live in [`../PARITY.md`](../PARITY.md). Change a shared
> surface on one platform and the parity drift check flags a mirror; divergent
> surfaces (SMS/push) evolve independently. Verify the UI with
> `scripts/verify-shell-ui.sh android`.

A **complete, installable** Compose app that wraps the shared Go custody core.
All crypto/relay lives in `custody.aar` (built from `../../mobile` by
`make android`); this app owns **secure storage** (Android Keystore +
EncryptedSharedPreferences) and **verified auto-release** (`AutoApprover` — every
relay that passes the Go core's verification is served hands-free, no tap, no
setting). Built + run + driven on an emulator — see "Verified" below.

## Build the APK (two commands, any OS — no Mac)

```sh
cd daemon
make android                 # gomobile bind -> shells/android/app/libs/custody.aar
cd shells/android && ./gradlew assembleDebug
#   -> app/build/outputs/apk/debug/app-debug.apk
```

Requires the Android SDK + NDK (`ANDROID_HOME` / `ANDROID_NDK_HOME`) and a JDK 17+
(`make doctor` checks; install steps in `../../BUILD.md` §(c)). Install on a plugged-in
device with USB debugging on:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then in the app: **Pair** (paste a code from your hub → Settings → Devices)
→ **Add a login** → **Start relay**.

## Install warnings & a release-signed APK

Sideloading (installing outside Google Play) shows warnings in three layers, with
different fixes:

1. **"Install unknown apps / not allowed from this source"** — the OS blocks any
   non-Play install until you grant that source (your browser or Files app) the
   permission, once, in Settings. **Inherent to sideloading** — only Google Play (or
   a preinstalled/MDM app) removes it; no app-side fix.
2. **Play Protect "unrecognized app" + a debug-certificate warning** — `assembleDebug`
   signs with the throwaway debug keystore, which Play Protect flags hardest. Build a
   **release-signed** APK instead to drop the debug-cert warning:

   ```sh
   # one-time: create a keystore (keep release.jks + its passwords safe and private)
   keytool -genkeypair -v -keystore release.jks -alias autologin \
     -keyalg RSA -keysize 2048 -validity 10000
   # build release, signed (properties can also live in ~/.gradle/gradle.properties)
   ./gradlew assembleRelease \
     -PRELEASE_STORE_FILE=$PWD/release.jks -PRELEASE_STORE_PASSWORD=… \
     -PRELEASE_KEY_ALIAS=autologin -PRELEASE_KEY_PASSWORD=…
   #   -> app/build/outputs/apk/release/app-release.apk   (v2/v3 signed, non-debuggable)
   adb install -r app/build/outputs/apk/release/app-release.apk
   ```

   (Without the `RELEASE_STORE_FILE` property the release APK is **unsigned** and won't
   install until signed with `apksigner`.) The **unknown-developer** flag and the
   `RECEIVE_SMS` flag below still apply on sideload regardless of signing.
3. **`RECEIVE_SMS` permission** — the biggest Play Protect trigger, and a *restricted*
   permission on the Play Store (limited to the default SMS handler). The permission-free
   fix is the **SMS User Consent API** (a one-time per-message system consent sheet, no
   `RECEIVE_SMS`), which is also the path to a Play Store listing.

**Bottom line:** a release-signed APK removes the debug-cert warning; the "unknown
sources" prompt and Play Protect's unknown-developer/SMS flags are only fully removed by
distributing through **Google Play** (which additionally needs the `RECEIVE_SMS` →
SMS-User-Consent migration).

## Files

| File | Role |
|---|---|
| `settings.gradle.kts`, `build.gradle.kts`, `app/build.gradle.kts`, `gradle.properties`, `gradlew` | The Gradle project (AGP 8.5, Kotlin 2.0, Compose BOM). |
| `app/src/main/AndroidManifest.xml`, `res/values/*` | Manifest (INTERNET, foreground-service, SMS, boot) + theme/strings. |
| `MainActivity.kt` | Compose UI: **PAIR** (redeem a code → `Mobile.pair`), **ENROLL** (save a site's credential JSON), **HOME** (start/stop the relay, list stored logins). |
| `KeystoreSecretSource.kt` | `ai.rindler.mobile.SecretSource` backed by EncryptedSharedPreferences (Keystore master key). Returns the per-site credential JSON the Go core parses; owns enrollment writes + the site index. |
| `AutoApprover.kt` | `ai.rindler.mobile.Approver` that approves every ping unconditionally. Safe because the Go core verifies each ping (server signature, device auth, account/site, replay) and HPKE-seals to the worker key *before* the approver runs — verification is the authorization, so no human tap. |

## Bound symbol naming (gomobile)

`make android` binds with `-javapkg=ai.rindler`, so the Go package `mobile`
generates Java package **`ai.rindler.mobile`**: class `Mobile`
(`Mobile.start/pair/generateDeviceKey/devicePublicKey`), struct `Session`
(`.stop()`), and interfaces `SecretSource` / `Approver` that the shell implements.
Go `error` returns throw a checked `Exception`.

## Verified (emulator, android-34 x86_64)

Built the APK, installed on a headless emulator, and drove the UI end-to-end:
- **PAIR** renders, accepts input, and tapping Pair runs the Go core over JNI
  (`generateDeviceKey` → `devicePublicKey` → `pair`) making a **real HTTPS POST**
  to the live hub — a bogus code surfaces the server's 401 cleanly (the Go error
  string propagates through gomobile → Kotlin → Compose). No crash.
- **ENROLL** saves a login to the Keystore-backed store; **HOME** then lists it.

### Debugging the UI (the Playwright-equivalent loop)

No Playwright for native Android; the equivalent is an **emulator + adb/uiautomator**:
```sh
adb exec-out screencap -p > screen.png            # screenshot (like a snapshot)
adb exec-out uiautomator dump /dev/tty            # element tree: text, ids, bounds (the "DOM")
adb shell input tap <x> <y> ; adb shell input text "…"   # interact (click / fill)
```
(Appium wraps this same layer in the WebDriver protocol if you want a scripted harness.)

Signing a release APK/AAB + Play internal testing: `../../BUILD.md` §(e).
