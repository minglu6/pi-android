#!/usr/bin/env bash
# 附件模型解析的 JVM 回归：无 APK / 无凭据 / 无后端请求。
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
JSON="$HERE/../bridge-connection/lib/json-20240303.jar"
printf '%s  %s\n' 3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed "$JSON" | sha256sum -c -
BUILD="$(mktemp -d "${TMPDIR:-/tmp}/pi-attachments-model-test.XXXXXX")"
trap 'rm -rf "$BUILD"' EXIT
find "$HERE/src" -name '*.java' -print > "$BUILD/sources.txt"
printf '%s\n' "$ROOT/app/src/dev/pi/android/model/ChatMessage.java" >> "$BUILD/sources.txt"
"${JAVAC:-javac}" --release 11 -encoding UTF-8 -cp "$JSON" -d "$BUILD/classes" @"$BUILD/sources.txt"
"${JAVA:-java}" -ea -cp "$BUILD/classes:$JSON" dev.pi.android.model.AttachmentsModelTest
