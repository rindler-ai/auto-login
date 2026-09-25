# The custody daemon's Android build (gomobile)

This is the Android build recipe for this repo's custody daemon: the Go core in
`daemon/mobile` bound to `custody.aar`, then the Gradle/Compose APK in
`daemon/shells/android`. In this repo `make -C daemon android` runs the bind
below (from `daemon/`, writing `daemon/shells/android/app/libs/custody.aar`),
and `.github/workflows/release.yml` does the same on a tag before it assembles
the APK. The notes below are for doing it by hand or debugging it.

Copied from the workspace pack's `android-app-emulator-debug` skill, sections 2
and 3, as they stood at `rindler-ai/workspace` 669c838f (lines 29-53), when the
pack review moved the custody-specific parts here (2026-09-25). Paths are
relative to `daemon/shells/android` unless stated; the bind's `./mobile` is
relative to `daemon/`.

## 2. Bind the Go core, then read the REAL Java names

```sh
gomobile bind -target=android -androidapi 26 -javapkg=ai.rindler -o app/libs/custody.aar ./mobile
```

- **Modern gomobile needs `golang.org/x/mobile` in the module graph** or `bind` errors with
  "missing golang.org/x/mobile dependency". Add it as a tool directive:
  `go get -tool golang.org/x/mobile/cmd/gobind`. This does NOT affect `go build/vet/test` (the tool
  isn't built by `./...`) — verify they still pass.
- **Do not guess the generated Java names.** `-javapkg=ai.rindler` makes Go package `mobile` bind to
  Java package `ai.rindler.mobile`; extract the API and confirm before writing Kotlin:
  ```sh
  cd /tmp && unzip -o app/libs/custody.aar classes.jar && unzip -o classes.jar
  javap ai/rindler/mobile/Mobile.class          # static funcs (start/pair/...)
  javap ai/rindler/mobile/SecretSource.class    # interface(s) the shell implements
  ```
  Package-level Go funcs land on class `Mobile`; structs become classes (`Session.stop()`);
  interfaces keep their bare name (`SecretSource`, `Approver`); Go `error` returns throw `Exception`.

## 3. Build the APK

Gradle project (AGP + Kotlin + Compose BOM) with `implementation(files("libs/custody.aar"))`, a
committed wrapper, and `local.properties` = `sdk.dir=$ANDROID_HOME` (gitignore it + the `.aar`/APK/
`build`/`.gradle`). Then: `./gradlew --no-daemon assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.
