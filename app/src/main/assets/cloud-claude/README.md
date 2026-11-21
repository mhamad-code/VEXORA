# Cloud coding agent workspace

This private repository is provisioned by the app's **Claude Code** section.

| File | Purpose |
| --- | --- |
| `devcontainer.json` | Node 22 image, installs Claude Code + LiteLLM + the relay, forwards the relay port publicly. |
| `setup.sh` | Idempotent install step (runs from `postCreateCommand`). |
| `start-relay.sh` | Starts exactly one relay from `postStartCommand`. |
| `relay.mjs` | WebSocket relay: the only bridge the phone can talk to. |
| `relay-token.sha256` | SHA-256 of this device's random relay secret. The secret itself never leaves the phone. |
| `.gitignore` | Keeps generated gateway configs, logs and `node_modules` out of Git. |

Provider API keys are delivered after the authenticated WebSocket handshake and written to
`~/.cloud-claude/gateway.env` (mode `0600`, outside this repository). They are never committed.

The relay only accepts: `hello`, `start`, `message`, `interrupt`, `permission`, `resume`, `status`,
`stop`. It never exposes a general shell.
