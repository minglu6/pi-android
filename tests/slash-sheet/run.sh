#!/usr/bin/env bash
# SheetHeight / FuzzyFilter / SlashCommand have no Android runtime deps — host JVM.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
JSON="$ROOT/tests/bridge-connection/lib/json-20240303.jar"
printf '%s  %s\n' 3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed "$JSON" | sha256sum -c -
BUILD="$(mktemp -d "${TMPDIR:-/tmp}/pi-slash-sheet-test.XXXXXX")"
trap 'rm -rf "$BUILD"' EXIT
mkdir -p "$BUILD/classes"
find "$ROOT/tests/model-state/stubs/androidx" -name '*.java' > "$BUILD/sources.txt"
printf '%s\n' \
  "$ROOT/app/src/dev/pi/android/ui/SheetHeight.java" \
  "$ROOT/app/src/dev/pi/android/ui/ComposerIme.java" \
  "$ROOT/app/src/dev/pi/android/util/FuzzyFilter.java" \
  "$ROOT/app/src/dev/pi/android/model/SlashCommand.java" \
  "$HERE/SheetHeightTest.java" \
  "$HERE/ComposerImeTest.java" \
  "$HERE/FuzzyFilterTest.java" \
  "$HERE/SlashCommandMenuTest.java" >> "$BUILD/sources.txt"
"${JAVAC:-javac}" --release 11 -encoding UTF-8 -cp "$JSON" -d "$BUILD/classes" @"$BUILD/sources.txt"
"${JAVA:-java}" -ea -cp "$BUILD/classes" dev.pi.android.ui.SheetHeightTest
"${JAVA:-java}" -ea -cp "$BUILD/classes" dev.pi.android.ui.ComposerImeTest
"${JAVA:-java}" -ea -cp "$BUILD/classes" dev.pi.android.util.FuzzyFilterTest
"${JAVA:-java}" -ea -cp "$BUILD/classes:$JSON" dev.pi.android.model.SlashCommandMenuTest
