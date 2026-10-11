#!/usr/bin/env bash
# Install pi-rpc-bridge as a user service for Tailscale remote access.
# Supports: macOS launchd · Linux systemd --user
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BRIDGE="$ROOT/bridge/pi-rpc-bridge.mjs"
TOKEN_FILE="${PI_ANDROID_TOKEN_FILE:-$HOME/.pi/agent/pi-android.token}"
PORT="${PI_ANDROID_PORT:-8945}"
LABEL="dev.pi.android.bridge"
UNIT_NAME="pi-rpc-bridge"

die() { echo "error: $*" >&2; exit 1; }

[[ -f "$BRIDGE" ]] || die "missing $BRIDGE — clone/pull minglu6/pi-android first"
[[ -f "$TOKEN_FILE" ]] || die "missing token file $TOKEN_FILE (umask 077; head -c 24 /dev/urandom | base64 > …)"
command -v node >/dev/null || die "node not on PATH"
command -v npm >/dev/null || die "npm not on PATH"

echo "==> npm install (bridge)"
( cd "$ROOT/bridge" && npm install --omit=dev --no-fund --no-audit )

NODE="$(command -v node)"
# Prefer --tailscale (bind only CGNAT). Override with PI_ANDROID_HOST if needed.
HOST_ARGS=(--tailscale)
if [[ -n "${PI_ANDROID_HOST:-}" ]]; then
  HOST_ARGS=(--host "$PI_ANDROID_HOST")
fi

run_cmd=(
  "$NODE" "$BRIDGE"
  "${HOST_ARGS[@]}"
  --port "$PORT"
  --token-file "$TOKEN_FILE"
  --idle-exit 0
)

case "$(uname -s)" in
  Darwin)
    PLIST="$HOME/Library/LaunchAgents/${LABEL}.plist"
    mkdir -p "$(dirname "$PLIST")"
    {
      echo '<?xml version="1.0" encoding="UTF-8"?>'
      echo '<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">'
      echo '<plist version="1.0"><dict>'
      echo "  <key>Label</key><string>${LABEL}</string>"
      echo '  <key>RunAtLoad</key><true/>'
      echo '  <key>KeepAlive</key><true/>'
      echo '  <key>ProgramArguments</key><array>'
      for a in "${run_cmd[@]}"; do
        printf '    <string>%s</string>\n' "$a"
      done
      echo '  </array>'
      echo "  <key>WorkingDirectory</key><string>${ROOT}/bridge</string>"
      echo "  <key>StandardOutPath</key><string>${HOME}/Library/Logs/pi-rpc-bridge.log</string>"
      echo "  <key>StandardErrorPath</key><string>${HOME}/Library/Logs/pi-rpc-bridge.err.log</string>"
      echo '</dict></plist>'
    } > "$PLIST"
    launchctl unload "$PLIST" 2>/dev/null || true
    launchctl load "$PLIST"
    echo "==> launchd loaded: $PLIST"
    echo "    logs: ~/Library/Logs/pi-rpc-bridge*.log"
    ;;
  Linux)
    UNIT_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
    mkdir -p "$UNIT_DIR"
    UNIT="$UNIT_DIR/${UNIT_NAME}.service"
    {
      echo "[Unit]"
      echo "Description=Pi Android RPC bridge (Tailscale)"
      echo "After=network-online.target"
      echo
      echo "[Service]"
      echo "Type=simple"
      echo "WorkingDirectory=${ROOT}/bridge"
      printf 'ExecStart=%s' "$NODE"
      for a in "$BRIDGE" "${HOST_ARGS[@]}" --port "$PORT" --token-file "$TOKEN_FILE" --idle-exit 0; do
        printf ' %q' "$a"
      done
      echo
      echo "Restart=on-failure"
      echo "RestartSec=3"
      echo
      echo "[Install]"
      echo "WantedBy=default.target"
    } > "$UNIT"
    systemctl --user daemon-reload
    systemctl --user enable --now "$UNIT_NAME"
    echo "==> systemd user service: $UNIT"
    echo "    status: systemctl --user status $UNIT_NAME"
    ;;
  *)
    die "unsupported OS $(uname -s); run manually: ${run_cmd[*]}"
    ;;
esac

IP="$(tailscale ip -4 2>/dev/null | head -1 || true)"
echo
echo "Remote profile on phone:"
echo "  host = ${IP:-<your-tailscale-ip-or-MagicDNS>}"
echo "  port = $PORT"
echo "  token = (same as $TOKEN_FILE)"
echo "Open Tailscale on Android, then add a profile in Pi → 连接配置."
