#!/data/data/com.termux/files/usr/bin/bash
# scripts/install-bridge.sh — install/update the Harness Hub bridge inside Termux.
# Idempotent. Requires node (pkg install nodejs — the script offers to install).
set -euo pipefail

BRIDGE_DIR="$HOME/.harnesshub/bridge"
BRIDGE_FILE="$BRIDGE_DIR/bridge.js"

echo "[harness-hub-bridge] installing"

if ! command -v node >/dev/null 2>&1; then
  echo "[harness-hub-bridge] nodejs missing; run: pkg install -y nodejs"
  exit 1
fi

mkdir -p "$BRIDGE_DIR" "$HOME/.harnesshub" "$HOME/bin" 2>/dev/null || true

# The APK copies assets/bridge/bridge.js into Termux storage via RUN_COMMAND
# (the app writes the file before invoking this script); if that copy exists,
# install it. Otherwise install whatever sits next to this script.
SRC="$(dirname "$0")/../bridge/bridge.js"
if [ -f "$HOME/storage/shared/harness-hub/bridge.js" ]; then
  SRC="$HOME/storage/shared/harness-hub/bridge.js"
fi

cp "$SRC" "$BRIDGE_FILE"
chmod 700 "$BRIDGE_FILE"

# launcher: termux-bridge (wake-locked, logs under ~/.harnesshub)
cat > "$HOME/bin/harness-hub-bridge-start" <<'LAUNCHER'
#!/data/data/com.termux/files/usr/bin/bash
exec >> ~/.harnesshub/bridge.log 2>&1
command -v node >/dev/null 2>&1 || { echo "[bridge] nodejs missing"; exit 1; }
pkill -f "$HOME/.harnesshub/bridge/bridge.js" 2>/dev/null || true
termux-wake-lock 2>/dev/null || true
exec node "$HOME/.harnesshub/bridge/bridge.js" --port 0
LAUNCHER
chmod 700 "$HOME/bin/harness-hub-bridge-start"

echo "[harness-hub-bridge] installed:"
echo "  bridge:   $BRIDGE_FILE"
echo "  launcher: \$HOME/bin/harness-hub-bridge-start"
echo "  start it with: $HOME/bin/harness-hub-bridge-start"
echo "  token: \$HOME/.harnesshub/bridge-token (created on first start)"
