# Custody app — build & ship runbook

One shared Go core (`./agent`, wrapped for mobile by `./mobile`), four native
shells. **Everything but the final iOS/macOS packaging + Apple code-signing builds
on any machine.** `make doctor` reports exactly what's installed and what to
install for each target.

```sh
cd daemon
make doctor
```

## (a) Targets at a glance

| `make` target | Output | Needs | Machine |
|---|---|---|---|
| `desktop` | `./custody-daemon` binary | Go ≥ 1.26 | **any OS** |
| `test` | race tests | Go | any OS |
| `android` | `shells/android/custody.aar` | Go + gomobile + Android SDK/NDK | **any OS** (Linux/macOS/Windows) |
| `ios` | `shells/ios/Custody.xcframework` (ios + iossimulator + **macos** slices) | Go + gomobile + Xcode | **Mac only** |

`make android` / `make ios` fail with an explicit "install X" message if the
toolchain is missing — nothing silently no-ops. App packaging & signing:
Android → any OS with Android Studio; **iOS/macOS → your Mac + Apple Developer
account** (marked below).

## (b) Desktop (any OS)

```sh
make desktop        # -> ./custody-daemon
```

Pair once, then run; identity persists to the OS keychain:

```sh
RINDLER_PAIRING_CODE=<code from your hub → Settings → Devices> ./custody-daemon
```

Install as a background service (systemd user unit on Linux, launchd plist on
macOS) — sample units in `shells/desktop/README.md`.

## (c) Android

```sh
# 1. Install the toolchain (once):
go install golang.org/x/mobile/cmd/gomobile@latest && gomobile init
#    Install Android Studio, then SDK Manager → SDK Tools → check "NDK (Side by side)".
export ANDROID_HOME="$HOME/Android/Sdk"                       # Linux; macOS: ~/Library/Android/sdk
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/<version>"

# 2. Build the .aar, then the APK (the Gradle project already references the .aar):
make android        # -> shells/android/app/libs/custody.aar
cd shells/android && ./gradlew assembleDebug
#   -> app/build/outputs/apk/debug/app-debug.apk

# 3. Install on a plugged-in device (USB debugging on), or open shells/android/ in
#    Android Studio and press Run:
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The app project is complete (Compose UI: pair / enroll / start-relay) and was
built + driven on an emulator — see `shells/android/README.md` (Verified).

## (d) iOS + macOS — **requires your Mac + Xcode**

```sh
# 1. Install the toolchain (once, on the Mac):
xcode-select --install                                         # or install Xcode from the App Store
sudo xcode-select -s /Applications/Xcode.app
go install golang.org/x/mobile/cmd/gomobile@latest && gomobile init

# 2. Build the xcframework (carries ios + simulator + macOS slices):
make ios            # -> shells/ios/Custody.xcframework

# 3. Open in Xcode:
#    Create/open the App target, add shells/ios/Sources/*.swift, and Embed & Sign
#    Custody.xcframework (full steps: shells/ios/README.md, macOS: shells/macos/README.md).
```

**Requires your Apple Developer account + Mac** — set once in Xcode:

- target → Signing & Capabilities → **Team** = your Apple Developer team.
- target → General → **Bundle Identifier** = `ai.rindler.autologin` (or your own).

Then **Product → Archive** → Organizer opens.

## (e) Signing & distribution

### macOS (notarized Developer-ID app) — **requires your Apple Developer account + Mac**

```sh
# Export a signed .app/.dmg from the Archive (Organizer → Distribute → Developer ID),
# then notarize and staple:
xcrun notarytool submit Custody.dmg \
  --apple-id "you@example.com" --team-id "<TEAMID>" --password "<app-specific-password>" \
  --wait
xcrun stapler staple Custody.dmg
```

Store the notarytool creds once with `xcrun notarytool store-credentials` and use
`--keychain-profile <name>` instead of the inline `--apple-id/--password`.

### iOS / App Store — **requires your Apple Developer account + Mac**

- Xcode Organizer → **Distribute App → App Store Connect → Upload** (Xcode signs &
  uploads). Or CLI: `xcrun altool --upload-app -f Custody.ipa -t ios -u <apple-id> -p <app-specific-password>`.
- App Store Connect → your app → **TestFlight** to invite testers; submit for
  review to ship to the App Store.

### Android (Play internal testing / signed APK) — any OS

```sh
# Signed release AAB (Android Studio: Build → Generate Signed Bundle/APK), or:
./gradlew :app:bundleRelease           # app/build/outputs/bundle/release/app-release.aab
```

Upload the `.aab` to **Play Console → your app → Testing → Internal testing** for a
tester track, or distribute a signed `.apk` directly. First release: create the app
in Play Console and enroll in Play App Signing.

---

Remaining Apple-only work (native binaries not producible in CI/Linux) is the
final iOS/macOS packaging + Apple code-signing — see §(d)/(e); it needs a Mac and
an Apple Developer account.
