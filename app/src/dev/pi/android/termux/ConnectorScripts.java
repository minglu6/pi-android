package dev.pi.android.termux;

/**
 * Shell snippets executed inside Termux via RUN_COMMAND ({@code bash -lc}).
 * Keep scripts short so PendingIntent results stay under Termux's ~100KB cap.
 */
public final class ConnectorScripts {
    private ConnectorScripts() {}

    /**
     * Start the connector: prefer runit {@code sv up}/{@code sv restart}, else
     * launch {@code ~/bin/pi-rpc-bridge} or node bridge under common paths.
     */
    public static String startOrRestart() {
        return ""
                + "set -e; "
                + "PREFIX=\"${PREFIX:-/data/data/com.termux/files/usr}\"; "
                + "export PATH=\"$PREFIX/bin:$HOME/bin:$PATH\"; "
                + "echo \"[pi-android] starting connector…\"; "
                // Prefer runit service if present
                + "if command -v sv >/dev/null 2>&1; then "
                + "  for d in \"$PREFIX/var/service/pi-rpc-bridge\" \"$HOME/service/pi-rpc-bridge\" "
                + "           \"$HOME/.local/service/pi-rpc-bridge\" \"$PREFIX/service/pi-rpc-bridge\"; do "
                + "    if [ -d \"$d\" ]; then "
                + "      sv up pi-rpc-bridge 2>/dev/null || sv restart pi-rpc-bridge; "
                + "      sleep 0.4; "
                + "      sv status pi-rpc-bridge || true; "
                + "      echo \"[pi-android] runit ok\"; "
                + "      exit 0; "
                + "    fi; "
                + "  done; "
                + "fi; "
                // Fallback: ~/bin wrapper or node module
                + "if [ -x \"$HOME/bin/pi-rpc-bridge\" ]; then "
                + "  pkill -f 'pi-rpc-bridge' 2>/dev/null || true; "
                + "  nohup \"$HOME/bin/pi-rpc-bridge\" >/dev/null 2>&1 & "
                + "  echo \"[pi-android] launched ~/bin/pi-rpc-bridge pid $!\"; "
                + "  exit 0; "
                + "fi; "
                + "for f in \"$HOME/pi-android/bridge/pi-rpc-bridge.mjs\" "
                + "         \"$HOME/pi-android/pi-rpc-bridge.mjs\" "
                + "         \"$HOME/.pi/agent/pi-rpc-bridge.mjs\"; do "
                + "  if [ -f \"$f\" ]; then "
                + "    pkill -f 'pi-rpc-bridge' 2>/dev/null || true; "
                + "    nohup node \"$f\" >/dev/null 2>&1 & "
                + "    echo \"[pi-android] launched node $f pid $!\"; "
                + "    exit 0; "
                + "  fi; "
                + "done; "
                + "echo \"[pi-android] ERROR: no pi-rpc-bridge service or binary found\" >&2; "
                + "exit 2";
    }

    /**
     * Download latest {@code pi-rpc-bridge.mjs} (+ siblings) from GitHub raw master,
     * npm install if package.json changed, then {@code sv restart}.
     */
    public static String updateAndRestart() {
        String rawBase = "https://raw.githubusercontent.com/minglu6/pi-android/master/bridge";
        return ""
                + "set -e; "
                + "PREFIX=\"${PREFIX:-/data/data/com.termux/files/usr}\"; "
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
                // Stamp version constants inside the bridge file
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
                // Symlink ~/bin for fallback launcher
                + "mkdir -p \"$HOME/bin\"; "
                + "printf '%s\\n' '#!/data/data/com.termux/files/usr/bin/bash' "
                + "  'exec node \"$HOME/.pi/agent/bridge/pi-rpc-bridge.mjs\" \"$@\"' "
                + "  > \"$HOME/bin/pi-rpc-bridge\"; "
                + "chmod +x \"$HOME/bin/pi-rpc-bridge\"; "
                // Restart
                + "if command -v sv >/dev/null 2>&1; then "
                + "  for d in \"$PREFIX/var/service/pi-rpc-bridge\" \"$HOME/service/pi-rpc-bridge\" "
                + "           \"$HOME/.local/service/pi-rpc-bridge\"; do "
                + "    if [ -d \"$d\" ]; then "
                + "      sv restart pi-rpc-bridge; "
                + "      sv status pi-rpc-bridge || true; "
                + "      echo \"[pi-android] sv restart done\"; "
                + "      echo \"VERSION $SHA $DATE\"; "
                + "      exit 0; "
                + "    fi; "
                + "  done; "
                + "fi; "
                + "pkill -f 'pi-rpc-bridge' 2>/dev/null || true; "
                + "nohup \"$HOME/bin/pi-rpc-bridge\" >/dev/null 2>&1 & "
                + "echo \"[pi-android] relaunched ~/bin/pi-rpc-bridge\"; "
                + "echo \"VERSION $SHA $DATE\"; "
                + "exit 0";
    }
}
