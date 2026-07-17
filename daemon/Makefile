# Custody app build — one entry point for every platform ().
#
# The shared Go core (./agent, wrapped for mobile by ./mobile) builds everywhere.
# The desktop binary and the Go bindings build on ANY machine; only the final
# iOS/macOS packaging + code-signing needs a Mac with Xcode (see BUILD.md).
#
#   make doctor     # what's installed / what to install for each target
#   make desktop    # headless daemon binary for THIS OS (no extra toolchain)
#   make test       # race tests for the whole module
#   make android    # gomobile bind -> shells/android/custody.aar   (needs Android NDK)
#   make ios        # gomobile bind -> shells/ios/Custody.xcframework (needs a Mac + Xcode)
#   make ios-app    # xcodegen + xcodebuild -> unsigned .app for the iOS simulator (needs `make ios` first)
#   make macos-app  # xcodegen + xcodebuild -> unsigned macOS menu-bar .app (needs `make ios` first)
#
# `make ios` / `make android` fail with an explicit "install X" message if the
# toolchain is missing — nothing here silently no-ops.

GO         ?= go
BIN        ?= custody-daemon
MOBILE_PKG := ./mobile
IOS_OUT    := shells/ios/Custody.xcframework
AAR_OUT    := shells/android/app/libs/custody.aar

.PHONY: all doctor desktop test fmt vet android ios ios-project ios-app macos-app clean

all: desktop

doctor:
	@bash scripts/build-doctor.sh

## Desktop daemon (any OS, no extra toolchain).
desktop:
	$(GO) build -o $(BIN) .
	@echo "built ./$(BIN)  (run: RINDLER_PAIRING_CODE=<code> ./$(BIN))"

test:
	$(GO) test -race -count=1 ./...

fmt:
	$(GO) fmt ./...

vet:
	$(GO) vet ./...

## Ensure gomobile is initialized (installs golang.org/x/mobile tooling).
gomobile-init:
	@command -v gomobile >/dev/null 2>&1 || { \
	  echo ">> installing gomobile…"; \
	  $(GO) install golang.org/x/mobile/cmd/gomobile@latest && gomobile init; }

## Android .aar — needs the Android SDK + NDK (ANDROID_HOME / ANDROID_NDK_HOME).
android: gomobile-init
	@[ -n "$$ANDROID_NDK_HOME$$ANDROID_HOME" ] || { \
	  echo "ERROR: Android SDK/NDK not found. Install Android Studio + NDK, then export ANDROID_HOME and ANDROID_NDK_HOME. See BUILD.md."; exit 1; }
	@mkdir -p shells/android/app/libs
	gomobile bind -target=android -androidapi 26 -javapkg=ai.rindler -o $(AAR_OUT) $(MOBILE_PKG)
	@echo "built $(AAR_OUT) — the Gradle app already references it; build the APK with:"
	@echo "  cd shells/android && ./gradlew assembleDebug   (APK -> app/build/outputs/apk/debug/)"

## iOS + macOS .xcframework — needs a Mac with Xcode (xcode-select installed).
ios: gomobile-init
	@command -v xcodebuild >/dev/null 2>&1 || { \
	  echo "ERROR: xcodebuild not found. `make ios` must run on a Mac with Xcode. See BUILD.md."; exit 1; }
	@mkdir -p shells/ios
	gomobile bind -target=ios,iossimulator,macos -o $(IOS_OUT) $(MOBILE_PKG)
	@echo "built $(IOS_OUT) — open shells/ios/Custody.xcodeproj (or drag the framework into your app) and Archive."

SIM_NAME   ?= iPhone 15
IOS_APP    := shells/ios/build/Build/Products/Debug-iphonesimulator/CustodyiOS.app
MAC_APP    := shells/ios/build/Build/Products/Debug/CustodyMac.app

## Generate the Xcode project from project.yml (needs `xcodegen`).
ios-project:
	@command -v xcodegen >/dev/null 2>&1 || { echo "ERROR: xcodegen not found. 'brew install xcodegen'. See BUILD.md."; exit 1; }
	cd shells/ios && xcodegen generate

## Build the iOS app for the simulator (unsigned). Requires `make ios` first.
ios-app: ios-project
	@[ -d "$(IOS_OUT)" ] || { echo "ERROR: $(IOS_OUT) missing — run 'make ios' first."; exit 1; }
	cd shells/ios && xcodebuild -project Custody.xcodeproj -scheme CustodyiOS \
	  -sdk iphonesimulator -configuration Debug -derivedDataPath build \
	  CODE_SIGNING_ALLOWED=NO build
	@echo "built $(IOS_APP)"

## Build the macOS menu-bar app (unsigned).
macos-app: ios-project
	@[ -d "$(IOS_OUT)" ] || { echo "ERROR: $(IOS_OUT) missing — run 'make ios' first."; exit 1; }
	cd shells/ios && xcodebuild -project Custody.xcodeproj -scheme CustodyMac \
	  -configuration Debug -derivedDataPath build CODE_SIGNING_ALLOWED=NO build
	@echo "built $(MAC_APP)"

clean:
	rm -rf $(BIN) $(IOS_OUT) $(AAR_OUT)
