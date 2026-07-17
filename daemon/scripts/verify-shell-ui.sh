#!/usr/bin/env bash
# verify-shell-ui: build + run + SEE the custody shell in a simulator/emulator so
# an agent can screenshot and grade the UI against shells/PARITY.md. The pass/fail
# judgement is left to the caller; this provides the primitives.
# Never a merge gate.
#
#   verify-shell-ui.sh ios     [--evidence DIR]   # iOS simulator (simctl; idb optional)
#   verify-shell-ui.sh android [--evidence DIR]   # Android emulator (adb)
#
# The iOS lane needs only Xcode + the iOS simulator runtime (screenshots via
# `xcrun simctl`). Interactive DRIVING (type/tap to reach ENROLL/HOME/401) uses
# `idb` when available and is skipped with a note when it is not — screenshots of
# every reachable screen still work without it.
set -euo pipefail
PLAT="${1:-}"; shift || true
EVID="/tmp/verify-shell-ui"
[ "${1:-}" = "--evidence" ] && { EVID="${2:?--evidence requires a directory}"; shift 2; }
mkdir -p "$EVID"; EVID="$(cd "$EVID" && pwd)"
cd "$(dirname "$0")/.."   # -> daemon
say() { printf '\n=== %s ===\n' "$1"; }

# Pick a booted iPhone simulator, else the first available iPhone, and boot it.
# (Do NOT hardcode a device name — the available set changes across Xcode versions.)
pick_ios_sim() {
  xcrun simctl list devices available -j | python3 -c '
import json,sys
d=json.load(sys.stdin)["devices"]
phones=[x for rt in d for x in d[rt] if "iPhone" in x["name"]]
booted=[x for x in phones if x["state"]=="Booted"]
pick=(booted or phones)
print(pick[0]["udid"] if pick else "")
'
}

ios() {
  say "build (make ios + make ios-app)"; make ios >/dev/null && make ios-app | tail -1
  local app; app=$(find shells/ios/build -name 'CustodyiOS.app' -type d | head -1)
  [ -n "$app" ] || { echo "ERROR: CustodyiOS.app not found after build"; exit 1; }

  local dev; dev=$(pick_ios_sim)
  [ -n "$dev" ] || { echo "ERROR: no iPhone simulator available. Install a runtime via Xcode > Settings > Components (or 'xcodebuild -downloadPlatform iOS')."; exit 1; }
  say "boot $dev"; xcrun simctl boot "$dev" 2>/dev/null || true; xcrun simctl bootstatus "$dev" -b >/dev/null

  say "install + launch"; xcrun simctl install "$dev" "$app"
  xcrun simctl launch "$dev" ai.rindler.autologin >/dev/null || true; sleep 4

  say "screenshot -> $EVID/01-launch.png (the PAIR screen)"
  xcrun simctl io "$dev" screenshot "$EVID/01-launch.png"

  say "element tree / driving (idb — optional)"
  if command -v idb >/dev/null 2>&1 && idb list-targets >/dev/null 2>&1; then
    idb ui describe-all --udid "$dev" > "$EVID/describe-all.json" 2>/dev/null && echo "wrote describe-all.json (drive: idb ui tap <x> <y> ; idb ui text '<s>' --udid $dev)"
  else
    echo "idb unavailable — screenshot-only. Interactive driving (bogus-code->401, ENROLL, HOME) is a follow-up."
  fi
  echo "UDID=$dev  APP=$app  EVIDENCE=$EVID"
}

android() {
  command -v adb >/dev/null 2>&1 || { echo "ERROR: adb not found. Android SDK/NDK required (see BUILD.md); the android lane is not runnable without it."; exit 1; }
  say "build (make android + gradle)"; make android >/dev/null && (cd shells/android && ./gradlew --quiet assembleDebug)
  local apk; apk=$(find shells/android -name 'app-debug.apk' | head -1)
  [ -n "$apk" ] || { echo "ERROR: app-debug.apk not found after build"; exit 1; }
  say "install + launch"; adb install -r "$apk"; adb shell am start -n ai.rindler.autologin/.MainActivity || true; sleep 3
  say "screenshot -> $EVID/01-launch.png"; adb exec-out screencap -p > "$EVID/01-launch.png"
  say "element tree"; adb exec-out uiautomator dump /dev/tty > "$EVID/describe-all.xml" 2>/dev/null || true
  echo "drive: adb shell input tap <x> <y> ; adb shell input text '<s>'   EVIDENCE=$EVID"
}

case "$PLAT" in
  ios) ios ;;
  android) android ;;
  *) echo "usage: verify-shell-ui.sh <ios|android> [--evidence DIR]"; exit 2 ;;
esac
