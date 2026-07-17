#!/usr/bin/env bash
# build-doctor: report which custody-app build targets are ready on THIS machine
# and print the exact install step for anything missing. Never fails — it is a
# report, so it always exits 0.
set -u

ok()   { printf '  \033[32m✓\033[0m %s\n' "$1"; }
miss() { printf '  \033[31m✗\033[0m %s\n' "$1"; }
have() { command -v "$1" >/dev/null 2>&1; }

echo "Custody app — build toolchain report"
echo

echo "Desktop daemon (any OS) + Go bindings:"
if have go; then ok "go ($(go version | awk '{print $3}'))"; else miss "go — install from https://go.dev/dl (>=1.26)"; fi
echo

echo "Android (.aar):"
if have gomobile; then ok "gomobile"; else miss "gomobile — 'go install golang.org/x/mobile/cmd/gomobile@latest && gomobile init'"; fi
if [ -n "${ANDROID_HOME:-}${ANDROID_NDK_HOME:-}" ]; then ok "Android SDK/NDK env set"; else miss "Android SDK+NDK — install Android Studio, add the NDK, export ANDROID_HOME + ANDROID_NDK_HOME"; fi
echo

echo "iOS + macOS (.xcframework, app packaging, signing) — Mac only:"
case "$(uname -s)" in
  Darwin) ok "running on macOS";;
  *)      miss "not macOS — iOS/macOS packaging & signing MUST run on a Mac (see BUILD.md, and the tracked issue for the Apple-only steps)";;
esac
if have xcodebuild; then ok "xcodebuild ($(xcodebuild -version 2>/dev/null | head -1))"; else miss "Xcode — install from the App Store, then 'sudo xcode-select -s /Applications/Xcode.app'"; fi
if have xcrun && xcrun --find notarytool >/dev/null 2>&1; then ok "notarytool (for notarization)"; else miss "notarytool — ships with Xcode 13+"; fi
echo

echo "iOS simulator verification loop (verify-shell-ui.sh):"
if have xcodegen; then ok "xcodegen"; else miss "xcodegen — 'brew install xcodegen'"; fi
if have idb; then ok "idb (fb-idb)"; else miss "idb — 'brew install idb-companion && pipx install fb-idb'"; fi
if have xcrun && xcrun simctl list runtimes 2>/dev/null | grep -qi ios; then ok "iOS simulator runtime"; else miss "iOS simulator runtime — install via Xcode > Settings > Components"; fi
echo

echo "Next: 'make desktop' works now on this machine; 'make ios' / 'make android' need the items marked ✗ above. Full runbook: BUILD.md."
