#!/usr/bin/env bash
# Requires an explicitly selected Android device; does not install or launch an app.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
: "${ANDROID_SERIAL:?Select a test device with ANDROID_SERIAL}"
: "${ANDROID_JAR:?Set ANDROID_JAR to an installed platform android.jar}"
CORE="$ROOT/app/libs/androidx/core/core/1.13.0/classes.jar"
BUILD="$(mktemp -d "${TMPDIR:-/tmp}/pi-provider-icons.XXXXXX")"
REMOTE="/data/local/tmp/pi-provider-icons-$$"
cleanup() {
    adb -s "$ANDROID_SERIAL" shell rm -rf "$REMOTE" >/dev/null 2>&1 || true
    rm -rf "$BUILD"
}
trap cleanup EXIT
mkdir -p "$BUILD/dex" "$BUILD/icons"
cp "$ROOT"/app/res/drawable/ic_provider_*.xml "$BUILD/icons/"
javac --release 11 -cp "$CORE:$ANDROID_JAR" -d "$BUILD" \
    "$ROOT/tests/provider-icons/ProviderIconsTest.java"
d8 --min-api 23 --lib "$ANDROID_JAR" --output "$BUILD/dex" \
    "$BUILD/ProviderIconsTest.class" "$CORE"
adb -s "$ANDROID_SERIAL" shell mkdir -p "$REMOTE"
adb -s "$ANDROID_SERIAL" push "$BUILD/dex/classes.dex" "$REMOTE/classes.dex" >/dev/null
adb -s "$ANDROID_SERIAL" push "$BUILD/icons" "$REMOTE/icons" >/dev/null
adb -s "$ANDROID_SERIAL" shell \
    "CLASSPATH=$REMOTE/classes.dex app_process /system/bin ProviderIconsTest $REMOTE/icons"
