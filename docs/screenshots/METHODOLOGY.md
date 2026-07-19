# Screenshot methodology

The images in this folder are used in the top-level `README.md`. They are captured
from the **Android app running in an emulator with demo data** — never from a real
device or a real account. Follow this process to regenerate or add screenshots so
they stay consistent, and so no real data ever lands in a public image.

## The one hard rule: demo data only

Every value visible in a screenshot must be **fake**. Never type a real password,
a real email, a real 2FA code, or a real hub hostname into the UI while capturing.
The current set uses `example.com` and `demo@example.com` with a throwaway
password. The only non-entered string is the emulator's own generic device name
(`Google sdk_gphone64_x86_64`), which is not sensitive.

## How the current set was produced

1. **Build a debug APK** from source:
   ```sh
   cd daemon && make android            # gomobile bind -> shells/android/app/libs/custody.aar
   cd shells/android && ./gradlew assembleDebug \
     -PhubUrl='wss://<your test hub>/v1/devices/connect' \
     -PcatalogUrl='https://<your test hub>/api/configs/catalog'
   ```
   The `-P` overrides point the debug build at a hub you control, so the Pair and
   enroll screens work. They are build-time only and never appear in a screenshot.

2. **Boot a headless emulator, install, launch** (android-34 x86_64):
   ```sh
   emulator -avd <name> -no-window -no-audio -gpu swiftshader_indirect &
   adb wait-for-device
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   adb shell am start -n ai.rindler.autologin/.MainActivity
   ```

3. **Temporarily allow screenshots.** The app sets `FLAG_SECURE` app-wide
   (`MainActivity.kt`) — a deliberate safety measure that blocks screenshots, the
   Recents thumbnail, and screen recording of credential surfaces. Capture is
   impossible while it is on, so for a **local debug build only**, gate it off
   (e.g. wrap the `FLAG_SECURE` call in `if (!BuildConfig.DEBUG) { ... }`), rebuild,
   capture, then **revert the change**. Never commit a build with `FLAG_SECURE`
   weakened.

4. **Reach the post-pair screens with a throwaway account.** Pairing needs a code
   from the hub. Use a disposable/test account and a test hub, and **delete any
   test data (pairing token, device row) afterward.** Do not pair a real account.

5. **Capture and verify each screen.** Enter only demo data, then:
   ```sh
   adb exec-out screencap -p > docs/screenshots/<NN>-<name>.png
   ```
   Open every PNG and confirm it (a) renders the intended screen with no keyboard
   covering it, (b) shows only fake/empty values, and (c) shows the **Auto Login**
   name. Retake anything blank or mid-transition.

## Naming + which screens

Keep the numbered order so the README gallery stays stable: onboarding
(`01-onboarding-*`), pairing (`02-pair`), home empty (`03-home`), add-a-login
(`04-add-login`), home with a saved login (`05-home-with-login`), settings
(`06-settings`). If you add a screen, extend the numbering and update the gallery
table in `README.md`.

## Keep them current

Any change to the UI (the screens under
`daemon/shells/android/app/src/main/java/ai/rindler/autologin/ui/`) can make these
stale. The CI **`screenshots-current`** check reminds you to refresh the affected
images (or to note that a change had no visible effect) — see
[`.github/workflows/ci.yml`](../../.github/workflows/ci.yml).
