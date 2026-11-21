#!/usr/bin/env bash
# Runs from the devcontainer's postStartCommand. Keeps exactly one relay alive.
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE_DIR="$HOME/.cloud-claude"
mkdir -p "$STATE_DIR" && chmod 700 "$STATE_DIR"

if pgrep -f "$REPO_DIR/.cloud-claude/relay.mjs" >/dev/null 2>&1; then
  echo "[relay] already running"
  exit 0
fi

export NODE_PATH="$STATE_DIR/node_modules"
export CLOUD_REPO_DIR="$REPO_DIR"
export PATH="$HOME/.local/bin:$PATH"
nohup node "$REPO_DIR/.cloud-claude/relay.mjs" >> "$STATE_DIR/relay.log" 2>&1 &
echo "[relay] started"
