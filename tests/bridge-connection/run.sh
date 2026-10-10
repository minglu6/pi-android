#!/usr/bin/env bash
# 只编译 BridgeConnection 与 JVM 测试，不启动 socket/Android/服务，不构建 APK。
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
JAVAC="${JAVAC:-${JAVA_HOME:+$JAVA_HOME/bin/}javac}"
JAVA="${JAVA:-${JAVA_HOME:+$JAVA_HOME/bin/}java}"
JSON="$HERE/lib/json-20240303.jar"
[ -f "$JSON" ] || { echo "缺本地 JSON jar；先显式运行 $HERE/setup-json.sh" >&2; exit 1; }
(cd "$HERE/lib" && printf '%s  %s\n' \
  3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed json-20240303.jar | sha256sum -c -)
CP="$JSON"
for dependency in \
  com/squareup/okhttp3/okhttp/4.12.0/okhttp.jar \
  com/squareup/okio/okio-jvm/3.6.0/okio-jvm.jar \
  org/jetbrains/kotlin/kotlin-stdlib/1.9.24/kotlin-stdlib.jar \
  androidx/annotation/annotation-jvm/1.8.1/annotation-jvm.jar; do
  jar="$ROOT/app/libs/$dependency"
  [ -f "$jar" ] || { echo "缺本地依赖: $jar" >&2; exit 1; }
  CP="$CP:$jar"
done
BUILD="$(mktemp -d "${TMPDIR:-/tmp}/pi-bridge-test.XXXXXX")"
trap 'rm -rf "$BUILD"' EXIT
find "$HERE/stubs" "$HERE/src" -name '*.java' -print > "$BUILD/sources.txt"
printf '%s\n' "${BRIDGE_SOURCE:-$ROOT/app/src/dev/pi/android/bridge/BridgeConnection.java}" >> "$BUILD/sources.txt"
"$JAVAC" --release 11 -encoding UTF-8 -cp "$CP" -d "$BUILD/classes" @"$BUILD/sources.txt"
"$JAVA" -ea "-Dtest.filter=${TEST_FILTER:-}" -cp "$BUILD/classes:$CP" dev.pi.android.bridge.BridgeConnectionTest
