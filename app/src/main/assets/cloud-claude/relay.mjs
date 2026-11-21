#!/usr/bin/env node
// Cloud relay: the only bridge between the phone and Claude Code in this
// Codespace. It understands a closed set of commands (hello, start, message,
// interrupt, permission, resume, status, stop), numbers every event so a
// reconnect can replay what the phone missed, and holds provider keys in
// $HOME/.cloud-claude with 0600 permissions — never in the repository.
import { createServer } from "node:http";
import { createHash, randomUUID, timingSafeEqual } from "node:crypto";
import { spawn } from "node:child_process";
import { mkdirSync, readFileSync, writeFileSync, chmodSync } from "node:fs";
import { join } from "node:path";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const { WebSocketServer } = require("ws");

const REPO_DIR = process.env.CLOUD_REPO_DIR || process.cwd();
const STATE_DIR = join(process.env.HOME || "/home/node", ".cloud-claude");
const PORT = Number(process.env.CLOUD_RELAY_PORT || 8765);
const MAX_BUFFER = 4000;
const ALLOWED = new Set(["hello", "start", "message", "interrupt", "permission", "resume", "status", "stop"]);

mkdirSync(STATE_DIR, { recursive: true, mode: 0o700 });

const tokenHashFile = join(REPO_DIR, ".cloud-claude", "relay-token.sha256");
const tokenHash = (() => {
  try {
    return readFileSync(tokenHashFile, "utf8").trim().toLowerCase();
  } catch {
    return "";
  }
})();

let seq = 0;
const buffer = [];
const clients = new Set();
let child = null;
let childSession = null;
let gateway = null;
let gatewayToken = "";
let stderrTail = "";
let pendingPermission = new Map();

function emit(event) {
  const frame = { seq: ++seq, kind: "event", event };
  buffer.push(frame);
  if (buffer.length > MAX_BUFFER) buffer.shift();
  const text = JSON.stringify(frame);
  for (const client of clients) {
    if (client.readyState === 1) {
      try { client.send(text); } catch { /* a dead socket is cleaned up by its close handler */ }
    }
  }
  return frame.seq;
}

function state(value, message) {
  emit({ kind: "state", value, message });
}

function fail(message) {
  emit({ kind: "error", message: String(message).slice(0, 2000) });
}

function authorized(token) {
  if (!tokenHash) return false;
  const got = createHash("sha256").update(String(token)).digest();
  const want = Buffer.from(tokenHash, "hex");
  return want.length === got.length && timingSafeEqual(want, got);
}

function writePrivate(name, content) {
  const file = join(STATE_DIR, name);
  writeFileSync(file, content, { mode: 0o600 });
  try { chmodSync(file, 0o600); } catch { /* best effort on filesystems without modes */ }
}

async function ensureGateway(config) {
  if (!config || !config.yaml) throw new Error("The session did not include a gateway configuration.");
  writePrivate("litellm.yaml", config.yaml);
  const envLines = Object.entries(config.secrets || {}).map(([key, value]) => `${key}=${value}`);
  writePrivate("gateway.env", envLines.join("\n") + "\n");
  gatewayToken = String((config.secrets || {}).VEXORA_GATEWAY_TOKEN || "");
  if (gateway && gateway.exitCode === null) return;
  const env = { ...process.env, ...(config.env || {}) };
  for (const [key, value] of Object.entries(config.secrets || {})) env[key] = String(value);
  const executable = process.env.LITELLM_BIN || "litellm";
  gateway = spawn(executable, ["--config", join(STATE_DIR, "litellm.yaml"), "--host", "127.0.0.1",
    "--port", "4000", "--num_workers", "1"], { env, stdio: ["ignore", "pipe", "pipe"] });
  gateway.stdout.on("data", (chunk) => { stderrTail = (stderrTail + chunk.toString()).slice(-2000); });
  gateway.stderr.on("data", (chunk) => { stderrTail = (stderrTail + chunk.toString()).slice(-2000); });
  gateway.on("exit", (code) => {
    gateway = null;
    if (code && code !== 0) fail(`The model gateway stopped (exit ${code}). ${stderrTail.trim()}`);
  });
  await waitForPort(4000, 120_000);
}

function waitForPort(port, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  return new Promise((resolve, reject) => {
    const check = () => {
      const socket = new (require("node:net").Socket)();
      socket.setTimeout(1500);
      socket.once("connect", () => { socket.destroy(); resolve(); });
      socket.once("timeout", () => { socket.destroy(); retry(); });
      socket.once("error", () => { socket.destroy(); retry(); });
      socket.connect(port, "127.0.0.1");
    };
    const retry = () => {
      if (Date.now() > deadline) reject(new Error(`Timed out waiting for the model gateway on port ${port}.`));
      else setTimeout(check, 1000);
    };
    check();
  });
}

function spawnClaude({ model, fastModel, permissionMode, gatewayEnv, resume }) {
  const args = ["-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose",
    "--include-partial-messages", "--permission-mode", permissionMode || "default"];
  if (resume || childSession) args.push("--resume", resume || childSession);
  else args.push("--session-id", randomUUID());
  const env = {
    ...process.env,
    ...(gatewayEnv || {}),
    DISABLE_TELEMETRY: "1",
    CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC: "1",
    ANTHROPIC_MODEL: model,
    ANTHROPIC_DEFAULT_HAIKU_MODEL: fastModel,
    CLAUDE_CODE_SUBAGENT_MODEL: model,
  };
  const proc = spawn(process.env.CLAUDE_BIN || "claude", args, {
    cwd: REPO_DIR, env, stdio: ["pipe", "pipe", "pipe"],
  });
  let stdout = "";
  proc.stdout.on("data", (chunk) => {
    stdout += chunk.toString();
    let index;
    while ((index = stdout.indexOf("\n")) >= 0) {
      const line = stdout.slice(0, index).trim();
      stdout = stdout.slice(index + 1);
      if (!line) continue;
      emit({ kind: "raw", json: line });
      const parsed = safeJson(line);
      if (parsed && parsed.type === "system" && parsed.subtype === "init" && parsed.session_id) {
        childSession = parsed.session_id;
      }
    }
  });
  proc.stderr.on("data", (chunk) => {
    stderrTail = (stderrTail + chunk.toString()).slice(-4000);
  });
  proc.on("exit", (code, signal) => {
    child = null;
    if (code === 0 || code === null || signal === "SIGINT") state("idle", null);
    else {
      fail(`Claude Code stopped (exit ${code}). ${stderrTail.trim().split("\n").slice(-6).join("\n")}`);
      state("error", null);
    }
  });
  return proc;
}

function safeJson(text) {
  try { return JSON.parse(text); } catch { return null; }
}

function sendUserMessage(text) {
  if (!child || child.exitCode !== null) return false;
  child.stdin.write(JSON.stringify({ type: "user", message: { role: "user", content: [{ type: "text", text }] } }) + "\n");
  return true;
}

function stopChild() {
  if (child && child.exitCode === null) {
    try { child.kill("SIGINT"); } catch { /* already gone */ }
    setTimeout(() => { try { child && child.kill("SIGKILL"); } catch { /* gone */ } }, 4000).unref();
  }
  child = null;
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let data = "";
    req.on("data", (chunk) => {
      data += chunk.toString();
      if (data.length > 1_000_000) { reject(new Error("body too large")); req.destroy(); }
    });
    req.on("end", () => resolve(data));
    req.on("error", reject);
  });
}

const server = createServer(async (req, res) => {
  if (req.url === "/health") {
    res.writeHead(200, { "content-type": "application/json", "cache-control": "no-store" });
    res.end(JSON.stringify({ ok: true, running: Boolean(child), gateway: Boolean(gateway) }));
    return;
  }
  // Authenticated one-shot model test: a tiny tool call through the gateway.
  if (req.method === "POST" && req.url === "/probe") {
    if (!authorized(req.headers["x-relay-token"])) {
      res.writeHead(401, { "content-type": "application/json" });
      res.end(JSON.stringify({ error: "unauthorized" }));
      return;
    }
    try {
      const body = safeJson(await readBody(req)) || {};
      const model = String(body.model || "");
      if (!model || !gatewayToken) throw new Error("No gateway session is running.");
      const upstream = await fetch("http://127.0.0.1:4000/v1/messages", {
        method: "POST",
        headers: { "content-type": "application/json", "x-api-key": gatewayToken,
          authorization: `Bearer ${gatewayToken}`, "anthropic-version": "2023-06-01" },
        body: JSON.stringify({
          model, max_tokens: 64,
          tools: [{ name: "ping", description: "Reply with pong", input_schema: { type: "object", properties: {} } }],
          messages: [{ role: "user", content: "Call the ping tool." }],
        }),
      });
      const text = await upstream.text();
      const parsed = safeJson(text) || {};
      const toolUse = Array.isArray(parsed.content) && parsed.content.some((block) => block.type === "tool_use");
      res.writeHead(upstream.ok ? 200 : 502, { "content-type": "application/json", "cache-control": "no-store" });
      res.end(JSON.stringify({ ok: upstream.ok, toolUse, detail: upstream.ok ? undefined : text.slice(0, 300) }));
    } catch (error) {
      res.writeHead(502, { "content-type": "application/json" });
      res.end(JSON.stringify({ error: String(error && error.message ? error.message : error).slice(0, 300) }));
    }
    return;
  }
  res.writeHead(404, { "content-type": "text/plain" });
  res.end("Not found");
});

const wss = new WebSocketServer({ server, path: "/relay", maxPayload: 2 * 1024 * 1024 });

wss.on("connection", (socket) => {
  clients.add(socket);
  let authed = false;
  let windowStart = Date.now();
  let windowCount = 0;
  socket.on("close", () => clients.delete(socket));
  socket.on("error", () => clients.delete(socket));

  socket.on("message", async (raw) => {
    if (Date.now() - windowStart > 5000) { windowStart = Date.now(); windowCount = 0; }
    if (++windowCount > 40) { socket.close(1008, "rate limited"); return; }
    const message = safeJson(raw.toString());
    if (!message || typeof message.type !== "string" || !ALLOWED.has(message.type)) {
      socket.close(1008, "unsupported command");
      return;
    }
    try {
      if (message.type === "hello") {
        if (!authorized(message.token)) { socket.close(1008, "unauthorized"); return; }
        authed = true;
        const since = Number(message.since || 0);
        for (const frame of buffer) {
          if (frame.seq > since && socket.readyState === 1) socket.send(JSON.stringify(frame));
        }
        socket.send(JSON.stringify({ seq, kind: "ready", lastSeq: seq }));
        return;
      }
      if (!authed) { socket.close(1008, "unauthorized"); return; }

      switch (message.type) {
        case "start": {
          stopChild();
          await ensureGateway(message.gateway);
          child = spawnClaude({
            model: message.model,
            fastModel: message.fastModel,
            permissionMode: message.permissionMode,
            gatewayEnv: message.gateway ? message.gateway.env : {},
            resume: message.sessionId || null,
          });
          state("running", null);
          if (message.prompt) sendUserMessage(String(message.prompt).slice(0, 100_000));
          break;
        }
        case "message": {
          if (!child) {
            if (!message.gateway && !message.model) { fail("Start a session before sending a message."); break; }
            await ensureGateway(message.gateway);
            child = spawnClaude({
              model: message.model, fastModel: message.fastModel,
              permissionMode: message.permissionMode, gatewayEnv: message.gateway ? message.gateway.env : {},
              resume: childSession,
            });
            state("running", null);
          }
          if (!sendUserMessage(String(message.text || "").slice(0, 100_000))) fail("Claude Code is not running.");
          break;
        }
        case "interrupt":
          stopChild();
          state("idle", "Interrupted.");
          break;
        case "permission": {
          const request = pendingPermission.get(message.requestId);
          pendingPermission.delete(message.requestId);
          if (request && child && child.stdin.writable) {
            child.stdin.write(JSON.stringify({
              type: "control_response",
              response: { subtype: "success", request_id: message.requestId,
                response: { behavior: message.allow ? "allow" : "deny" } },
            }) + "\n");
          }
          break;
        }
        case "resume":
          childSession = String(message.sessionId || "") || childSession;
          state("idle", "Session resumed.");
          break;
        case "status":
          socket.send(JSON.stringify({ seq, kind: "ready", lastSeq: seq, running: Boolean(child) }));
          break;
        case "stop":
          stopChild();
          if (gateway) { try { gateway.kill("SIGTERM"); } catch { /* gone */ } gateway = null; }
          state("idle", "Stopped.");
          break;
        default:
          break;
      }
    } catch (error) {
      fail(error && error.message ? error.message : error);
      state("error", null);
    }
  });
});

server.listen(PORT, "0.0.0.0", () => {
  console.log(`[relay] listening on ${PORT}`);
});

for (const signal of ["SIGTERM", "SIGINT"]) {
  process.on(signal, () => {
    stopChild();
    if (gateway) { try { gateway.kill("SIGTERM"); } catch { /* gone */ } }
    server.close(() => process.exit(0));
  });
}
