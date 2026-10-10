#!/usr/bin/env bash
# 一次性、显式联网准备。run.sh 本身完全离线，不会自动下载。
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
HASH=3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed
mkdir -p "$HERE/lib"
TEMP="$(mktemp "$HERE/lib/json.XXXXXX")"
trap 'rm -f "$TEMP"' EXIT
curl -fL --connect-timeout 10 --max-time 60 \
  https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar -o "$TEMP"
printf '%s  %s\n' "$HASH" "$TEMP" | sha256sum -c -
mv "$TEMP" "$HERE/lib/json-20240303.jar"
