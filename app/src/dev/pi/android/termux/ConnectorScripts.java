package dev.pi.android.termux;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Shell snippets executed inside Termux via RUN_COMMAND ({@code bash -lc}).
 * Keep scripts short so PendingIntent results stay under Termux's ~100KB cap.
 */
public final class ConnectorScripts {
    private ConnectorScripts() {}

    /**
     * Start the connector: prefer runit {@code sv up}/{@code sv restart}, else
     * {@code nohup} the bridge. Always verifies the listen port before exit 0.
     *
     * <p>If a runit service <em>directory</em> exists but {@code runsv} is not
     * supervising it (common after OEM kills Termux), fall through to nohup —
     * do not {@code set -e}-abort before the fallback.
     *
     * @param token connector auth token written to {@code ~/.pi/agent/pi-android.token}
     * @param port  WebSocket listen port to probe after launch
     */
    public static String startOrRestart(@Nullable String token, int port) {
        int p = port > 0 ? port : 8945;
        String tok = token == null ? "" : token;
        return ""
                // No global set -e: sv failures must fall through to nohup.
                + "PREFIX=\"${PREFIX:-/data/data/com.termux/files/usr}\"; "
                + "HOME=\"${HOME:-/data/data/com.termux/files/home}\"; "
                + "export HOME PREFIX; "
                + "export PATH=\"$PREFIX/bin:$HOME/bin:$PATH\"; "
                + "PORT=\"" + p + "\"; "
                + "TOKEN_FILE=\"$HOME/.pi/agent/pi-android.token\"; "
                + "mkdir -p \"$HOME/.pi/agent\" \"$HOME/bin\"; "
                + "echo \"[pi-android] starting connector port=$PORT home=$HOME\"; "
                // Sync token from APK (required; bridge refuses empty/missing file).
                + "printf '%s' '" + shSingleQuote(tok) + "' > \"$TOKEN_FILE\"; "
                + "chmod 600 \"$TOKEN_FILE\" 2>/dev/null || true; "
                + "if [ ! -s \"$TOKEN_FILE\" ]; then "
                + "  echo \"[pi-android] ERROR: token file empty\" >&2; exit 4; "
                + "fi; "
                + "echo \"[pi-android] token file ok ($(wc -c < \"$TOKEN_FILE\") bytes)\"; "
                // Prefer runit only when the service is actually supervised.
                + "started=0; "
                + "if command -v sv >/dev/null 2>&1; then "
                + "  for d in \"$PREFIX/var/service/pi-rpc-bridge\" \"$HOME/service/pi-rpc-bridge\" "
                + "           \"$HOME/.local/service/pi-rpc-bridge\" \"$PREFIX/service/pi-rpc-bridge\"; do "
                + "    if [ -d \"$d\" ]; then "
                + "      echo \"[pi-android] found service dir $d\"; "
                + "      if sv status pi-rpc-bridge >/dev/null 2>&1 "
                + "         || sv up pi-rpc-bridge >/dev/null 2>&1 "
                + "         || sv restart pi-rpc-bridge >/dev/null 2>&1; then "
                + "        sleep 0.5; "
                + "        sv status pi-rpc-bridge 2>/dev/null || true; "
                + "        started=1; "
                + "        echo \"[pi-android] runit ok\"; "
                + "        break; "
                + "      else "
                + "        echo \"[pi-android] runit dir present but sv idle — fall through to nohup\"; "
                + "      fi; "
                + "    fi; "
                + "  done; "
                + "fi; "
                // nohup fallback
                + "if [ \"$started\" != 1 ]; then "
                + "  pkill -f 'pi-rpc-bridge' 2>/dev/null || true; "
                + "  sleep 0.2; "
                + "  if [ -x \"$HOME/bin/pi-rpc-bridge\" ]; then "
                + "    nohup \"$HOME/bin/pi-rpc-bridge\" >>\"$HOME/.pi/agent/bridge.log\" 2>&1 & "
                + "    echo \"[pi-android] launched ~/bin/pi-rpc-bridge pid $!\"; "
                + "    started=1; "
                + "  else "
                + "    for f in \"$HOME/.pi/agent/bridge/pi-rpc-bridge.mjs\" "
                + "             \"$HOME/pi-android/bridge/pi-rpc-bridge.mjs\" "
                + "             \"$HOME/pi-android/pi-rpc-bridge.mjs\" "
                + "             \"$HOME/.pi/agent/pi-rpc-bridge.mjs\"; do "
                + "      if [ -f \"$f\" ]; then "
                + "        nohup node \"$f\" >>\"$HOME/.pi/agent/bridge.log\" 2>&1 & "
                + "        echo \"[pi-android] launched node $f pid $!\"; "
                + "        started=1; "
                + "        break; "
                + "      fi; "
                + "    done; "
                + "  fi; "
                + "fi; "
                + "if [ \"$started\" != 1 ]; then "
                + "  echo \"[pi-android] ERROR: no pi-rpc-bridge service or binary found\" >&2; "
                + "  echo \"[pi-android] hint: open Settings → 更新连接器, or install into ~/.pi/agent/bridge\" >&2; "
                + "  exit 2; "
                + "fi; "
                // Wait until something listens on PORT (bridge may need a moment).
                + "ok=0; "
                + "for i in 1 2 3 4 5 6 7 8 9 10 11 12; do "
                + "  if command -v ss >/dev/null 2>&1; then "
                + "    if ss -ltn 2>/dev/null | grep -E \":$PORT\\\\b\" >/dev/null; then ok=1; break; fi; "
                + "  elif command -v netstat >/dev/null 2>&1; then "
                + "    if netstat -ltn 2>/dev/null | grep -E \":$PORT\\\\b\" >/dev/null; then ok=1; break; fi; "
                + "  else "
                + "    if \"$PREFIX/bin/bash\" -c \"echo >/dev/tcp/127.0.0.1/$PORT\" 2>/dev/null; then ok=1; break; fi; "
                + "  fi; "
                + "  sleep 0.4; "
                + "done; "
                + "if [ \"$ok\" = 1 ]; then "
                + "  echo \"[pi-android] listening on :$PORT\"; "
                + "  exit 0; "
                + "fi; "
                + "echo \"[pi-android] ERROR: process started but nothing listening on :$PORT\" >&2; "
                + "tail -n 20 \"$HOME/.pi/agent/bridge.log\" 2>/dev/null >&2 || true; "
                + "exit 3";
    }

    /** Probe used by Settings → 诊断 (no side effects beyond echo). */
    public static String diagnose(int port) {
        int p = port > 0 ? port : 8945;
        return ""
                + "PREFIX=\"${PREFIX:-/data/data/com.termux/files/usr}\"; "
                + "HOME=\"${HOME:-/data/data/com.termux/files/home}\"; "
                + "export HOME PREFIX PATH=\"$PREFIX/bin:$HOME/bin:$PATH\"; "
                + "PORT=\"" + p + "\"; "
                + "echo \"[diag] home=$HOME prefix=$PREFIX port=$PORT\"; "
                + "echo \"[diag] bash=$(command -v bash)\"; "
                + "echo \"[diag] node=$(command -v node || echo missing)\"; "
                + "echo \"[diag] sv=$(command -v sv || echo missing)\"; "
                + "TOKEN_FILE=\"$HOME/.pi/agent/pi-android.token\"; "
                + "if [ -s \"$TOKEN_FILE\" ]; then echo \"[diag] token=present ($(wc -c < \"$TOKEN_FILE\")B)\"; "
                + "else echo \"[diag] token=MISSING\"; fi; "
                + "for f in \"$HOME/bin/pi-rpc-bridge\" "
                + "         \"$HOME/.pi/agent/bridge/pi-rpc-bridge.mjs\" "
                + "         \"$HOME/pi-android/bridge/pi-rpc-bridge.mjs\"; do "
                + "  if [ -e \"$f\" ]; then echo \"[diag] found $f\"; fi; "
                + "done; "
                + "for d in \"$PREFIX/var/service/pi-rpc-bridge\" \"$HOME/service/pi-rpc-bridge\"; do "
                + "  if [ -d \"$d\" ]; then echo \"[diag] service-dir $d\"; "
                + "    sv status pi-rpc-bridge 2>&1 | sed 's/^/[diag] sv: /' || echo \"[diag] sv: not supervised\"; "
                + "  fi; "
                + "done; "
                + "if command -v ss >/dev/null 2>&1; then "
                + "  ss -ltn 2>/dev/null | grep -E \":$PORT\\\\b\" | sed 's/^/[diag] listen: /' "
                + "    || echo \"[diag] listen: none on :$PORT\"; "
                + "else echo \"[diag] ss=missing\"; fi; "
                + "pgrep -af pi-rpc-bridge 2>/dev/null | sed 's/^/[diag] proc: /' || echo \"[diag] proc: none\"; "
                + "echo \"[diag] done\"; "
                + "exit 0";
    }

    /**
     * Download latest {@code pi-rpc-bridge.mjs} (+ siblings) from GitHub raw master,
     * npm install if package.json changed, then {@code sv restart} or nohup.
     */
    public static String updateAndRestart() {
        String rawBase = "https://raw.githubusercontent.com/minglu6/pi-android/master/bridge";
        return ""
                + "set -e; "
                + "PREFIX=\"${PREFIX:-/data/data/com.termux/files/usr}\"; "
                + "HOME=\"${HOME:-/data/data/com.termux/files/home}\"; "
                + "export HOME PREFIX; "
                + "export PATH=\"$PREFIX/bin:$HOME/bin:$PATH\"; "
                + "DIR=\"$HOME/.pi/agent/bridge\"; "
                + "mkdir -p \"$DIR\"; "
                + "cd \"$DIR\"; "
                + "echo \"[pi-android] updating connector into $DIR\"; "
                + "SHA=$(curl -fsSL https://api.github.com/repos/minglu6/pi-android/commits/master "
                + "  | sed -n 's/.*\\\"sha\\\": \\\"\\([0-9a-f]\\{7,40\\}\\)\\\".*/\\1/p' | head -1 | cut -c1-7); "
                + "DATE=$(date -u +%Y-%m-%dT%H:%M:%SZ); "
                + "SHA=${SHA:-unknown}; "
                + "echo \"[pi-android] remote sha=$SHA date=$DATE\"; "
                + "OLD_PKG_HASH=\"\"; "
                + "[ -f package.json ] && OLD_PKG_HASH=$(sha256sum package.json | awk '{print $1}'); "
                + "for f in pi-rpc-bridge.mjs schedule-when.mjs attachment-store.mjs package.json; do "
                + "  curl -fsSL \"" + rawBase + "/$f\" -o \"$f.tmp\"; "
                + "  if [ -f \"$f\" ]; then cp -f \"$f\" \"$f.bak\"; fi; "
                + "  mv -f \"$f.tmp\" \"$f\"; "
                + "  echo \"[pi-android] wrote $f\"; "
                + "done; "
                + "sed -i \"s/const CONNECTOR_SHA = \\\"[^\\\"]*\\\"/const CONNECTOR_SHA = \\\"$SHA\\\"/\" pi-rpc-bridge.mjs; "
                + "sed -i \"s/const CONNECTOR_DATE = \\\"[^\\\"]*\\\"/const CONNECTOR_DATE = \\\"$DATE\\\"/\" pi-rpc-bridge.mjs; "
                + "NEW_PKG_HASH=$(sha256sum package.json | awk '{print $1}'); "
                + "if [ \"$OLD_PKG_HASH\" != \"$NEW_PKG_HASH\" ]; then "
                + "  echo \"[pi-android] package.json changed — npm install\"; "
                + "  npm install --omit=dev --no-fund --no-audit; "
                + "else "
                + "  echo \"[pi-android] package.json unchanged — skip npm\"; "
                + "fi; "
                + "chmod +x pi-rpc-bridge.mjs 2>/dev/null || true; "
                + "mkdir -p \"$HOME/bin\"; "
                + "printf '%s\\n' '#!/data/data/com.termux/files/usr/bin/bash' "
                + "  'exec node \"$HOME/.pi/agent/bridge/pi-rpc-bridge.mjs\" \"$@\"' "
                + "  > \"$HOME/bin/pi-rpc-bridge\"; "
                + "chmod +x \"$HOME/bin/pi-rpc-bridge\"; "
                + "restart_ok=0; "
                + "if command -v sv >/dev/null 2>&1; then "
                + "  for d in \"$PREFIX/var/service/pi-rpc-bridge\" \"$HOME/service/pi-rpc-bridge\" "
                + "           \"$HOME/.local/service/pi-rpc-bridge\"; do "
                + "    if [ -d \"$d\" ]; then "
                + "      if sv restart pi-rpc-bridge >/dev/null 2>&1 || sv up pi-rpc-bridge >/dev/null 2>&1; then "
                + "        sv status pi-rpc-bridge || true; "
                + "        echo \"[pi-android] sv restart done\"; "
                + "        restart_ok=1; "
                + "        break; "
                + "      fi; "
                + "    fi; "
                + "  done; "
                + "fi; "
                + "if [ \"$restart_ok\" != 1 ]; then "
                + "  pkill -f 'pi-rpc-bridge' 2>/dev/null || true; "
                + "  nohup \"$HOME/bin/pi-rpc-bridge\" >>\"$HOME/.pi/agent/bridge.log\" 2>&1 & "
                + "  echo \"[pi-android] relaunched ~/bin/pi-rpc-bridge\"; "
                + "fi; "
                + "echo \"VERSION $SHA $DATE\"; "
                + "exit 0";
    }

    /** Escape for inclusion inside single-quoted shell string. */
    @NonNull
    static String shSingleQuote(@NonNull String raw) {
        // 'foo'bar' → 'foo'\''bar'
        return raw.replace("'", "'\\''");
    }
}
