#!/usr/bin/env bash
# 将修复前基线的旧类放入临时目录，仅加相同的测试注入构造器。
# 不替换工作树。旧版本必须因目标断言失败，新版本必须通过整套测试。
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
TEMP="$(mktemp -d "${TMPDIR:-/tmp}/pi-bridge-baseline.XXXXXX")"
trap 'rm -rf "$TEMP"' EXIT
git -C "$ROOT" show "${BASELINE_REF:-6efe21f}:app/src/dev/pi/android/bridge/BridgeConnection.java" > "$TEMP/original.java"
python3 - "$TEMP/original.java" "$TEMP/BridgeConnection.java" <<'PY'
from pathlib import Path
import sys
text = Path(sys.argv[1]).read_text()
old = '''    public BridgeConnection() {
        http = new OkHttpClient.Builder()
                .pingInterval(25, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)   // 长连接，不要超时断开
                .build();
    }'''
new = '''    public BridgeConnection() {
        this(new OkHttpClient.Builder()
                .pingInterval(25, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build());
    }

    BridgeConnection(OkHttpClient http) {
        this.http = http;
    }'''
if text.count(old) != 1:
    raise SystemExit('指定基线已改变，无法确认旧版；请审查后显式适配基线（不能把意外编译失败当作回归证据）。')
Path(sys.argv[2]).write_text(text.replace(old, new))
PY
verify_failure() {
  local filter="$1" assertion="$2"
  if TEST_FILTER="$filter" BRIDGE_SOURCE="$TEMP/BridgeConnection.java" "$HERE/run.sh" > "$TEMP/result.txt" 2>&1; then
    echo "错误：旧版意外通过目标测试: $filter" >&2
    exit 1
  fi
  if ! grep -Fq "$assertion" "$TEMP/result.txt"; then
    echo "错误：旧版不是目标断言失败: $filter" >&2
    command cat "$TEMP/result.txt" >&2
    exit 1
  fi
  printf 'CONFIRMED old version fails: %s (%s)\n' "$filter" "$assertion"
}
verify_failure 'open/initial offline' 'open sends only auth'
verify_failure 'all stale socket' 'old callback disconnected new socket'
verify_failure 'queued status/response/event' 'stale queued message delivered'
verify_failure 'close invalidates' 'close notification or invalidation'
verify_failure 'Pi offline keeps' 'Pi offline revoked socket auth'
"$HERE/run.sh"
