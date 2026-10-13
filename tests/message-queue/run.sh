#!/usr/bin/env bash
# Compile MessageQueue + unit test without android.jar.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
BUILD="$(mktemp -d "${TMPDIR:-/tmp}/pi-message-queue-test.XXXXXX")"
trap 'rm -rf "$BUILD"' EXIT
mkdir -p "$BUILD/classes"
"${JAVAC:-javac}" --release 11 -encoding UTF-8 -d "$BUILD/classes" \
  "$ROOT/app/src/dev/pi/android/model/MessageQueue.java" \
  "$HERE/MessageQueueTest.java"
"${JAVA:-java}" -ea -cp "$BUILD/classes" MessageQueueTest
