#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
JSON="$ROOT/tests/bridge-connection/lib/json-20240303.jar"
printf '%s  %s\n' 3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed "$JSON" | sha256sum -c -
BUILD="$(mktemp -d "${TMPDIR:-/tmp}/pi-extension-test.XXXXXX")"
trap 'rm -rf "$BUILD"' EXIT
find "$ROOT/tests/bridge-connection/stubs/android/os" "$ROOT/tests/model-state/stubs/androidx" -name '*.java' > "$BUILD/sources.txt"
printf '%s\n' "$ROOT/app/src/dev/pi/android/ui/ExtensionUi.java" "$ROOT/app/src/dev/pi/android/ui/HistoryPresentation.java" "$HERE/ExtensionUiTest.java" >> "$BUILD/sources.txt"
javac --release 11 -encoding UTF-8 -cp "$JSON" -d "$BUILD/classes" @"$BUILD/sources.txt"
java -ea -cp "$BUILD/classes:$JSON" ExtensionUiTest
