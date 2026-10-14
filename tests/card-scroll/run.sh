#!/usr/bin/env bash
# CardScrollCap has no Android deps — compile + run on the host JVM.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
BUILD="$(mktemp -d "${TMPDIR:-/tmp}/pi-card-scroll-test.XXXXXX")"
trap 'rm -rf "$BUILD"' EXIT
mkdir -p "$BUILD/classes"
"${JAVAC:-javac}" --release 11 -encoding UTF-8 -d "$BUILD/classes" \
  "$ROOT/app/src/dev/pi/android/ui/CardScrollCap.java" \
  "$HERE/CardScrollCapTest.java"
"${JAVA:-java}" -ea -cp "$BUILD/classes" dev.pi.android.ui.CardScrollCapTest
