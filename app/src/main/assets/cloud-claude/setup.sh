#!/usr/bin/env bash
# Provisioned once per Codespace. Idempotent: re-running repairs the environment.
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE_DIR="$HOME/.cloud-claude"
mkdir -p "$STATE_DIR"
chmod 700 "$STATE_DIR"

echo "[setup] Installing the relay dependencies"
npm install --prefix "$STATE_DIR" --no-audit --no-fund ws@8

echo "[setup] Installing Claude Code"
if ! command -v claude >/dev/null 2>&1; then
  npm install -g @anthropic-ai/claude-code
fi

echo "[setup] Installing the Anthropic-compatible gateway (LiteLLM)"
if ! command -v litellm >/dev/null 2>&1 && [ ! -x "$HOME/.local/bin/litellm" ]; then
  python3 -m pip install --user --quiet "litellm[proxy]"
fi

# The workspace repository must never carry secrets or generated state.
cat > "$REPO_DIR/.cloud-claude/.gitignore" <<'EOF'
node_modules/
*.log
litellm.yaml
gateway.env
session.json
EOF

chmod +x "$REPO_DIR/.cloud-claude/start-relay.sh" || true
echo "[setup] Done"
