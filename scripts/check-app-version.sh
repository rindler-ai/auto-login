#!/usr/bin/env bash
# Validates the Android app version fields that govern whether an update installs
# cleanly over a previous install. Android rejects an update when applicationId
# changes, the signing key changes, or versionCode does not increase — this script
# guards the two we can check statically (applicationId pin + version formats) and
# prints the values for callers (the release workflow enforces the versionCode
# increase against the previous release).
set -euo pipefail

GRADLE="daemon/shells/android/app/build.gradle.kts"
EXPECT_APPID="ai.rindler.autologin"

appid=$(grep -oE 'applicationId = "[^"]+"' "$GRADLE" | sed -E 's/.*"([^"]+)".*/\1/')
vcode=$(grep -oE 'versionCode = [0-9]+' "$GRADLE" | grep -oE '[0-9]+' | head -1)
vname=$(grep -oE 'versionName = "[^"]+"' "$GRADLE" | sed -E 's/.*"([^"]+)".*/\1/')

fail() { echo "::error::$1"; exit 1; }

[ "$appid" = "$EXPECT_APPID" ] || fail "applicationId '$appid' != pinned '$EXPECT_APPID' — a changed applicationId is a DIFFERENT app and breaks in-place updates"
echo "$vcode" | grep -qE '^[0-9]+$' || fail "versionCode '$vcode' is not a positive integer"
echo "$vname" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+$' || fail "versionName '$vname' is not semver X.Y.Z"

echo "APPLICATION_ID=$appid"
echo "VERSION_CODE=$vcode"
echo "VERSION_NAME=$vname"
