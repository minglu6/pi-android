#!/usr/bin/env node
/**
 * pi-rpc-bridge — WebSocket ↔ Pi RPC 桥（Termux 侧）
 *
 *   APK ──ws://127.0.0.1:8945──→ 本进程 ──stdin/stdout JSONL──→ pi --mode rpc
 *          ◄──────── 事件流 ──────────────────────────────────┘
 *
 * 设计要点（每条都有一个代价/alternative，见 docs/bridge.md）：
 *  - 只以官方支持的方式驱动 Pi：`pi --mode rpc` 的 JSONL 子协议，不 import Pi 内部 dist。
 *  - 只绑 127.0.0.1 + token 鉴权。loopback 在本机不对 App 做强隔离，token 是唯一门槛。
 *  - 单个 Pi 子进程，客户端共享；最后一个客户端断开后按 --idle-exit 秒空闲退出（默认 600s），
 *    便于 runit/前台两种运行方式。
 *  - 能力白名单：pc julia mayka? 不。默认只允许 read-only + prompt/abort 等交互命令，
 *    `bash` 命令默认关闭（--allow-bash 显式开），因为它等于把 shell 交给同一台设备上的任意 App。
 *
 * 线协议（client → server）：
 *    {"id":"1","rpc":"get_state","params":{}}
 *    {"id":"2","rpc":"prompt","params":{"message":"hi"}}
 *    {"type":"ping"}                        // 不看 token 也能心跳
 * server → client：
 *    {"v":1,"kind":"status","state":"online"|"starting"|"offline","error"?,"pid"?}
 *    {"v":1,"kind":"response","id":"1","ok":true,"command":"get_state","data":{...}}
 *    {"v":1,"kind":"response","id":"1","ok":false,"command":"get_state","error":"..."}
 *    {"v":1,"kind":"event","event":{...Pi 的 stdout 记录原样转发...}}
 *    {"v":1,"kind":"pong","t":<ms>}
 *    {"v":1,"kind":"log","line":"..."}      // Pi 的 stderr 摘录，--verbose-log 时下发
 */
import { spawn } from "node:child_process";
import { WebSocketServer } from "ws";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

const argv = process.argv.slice(2);
const arg = (name, def) => {
  const i = argv.findIndex((a) => a === `--${name}` || a.startsWith(`--${name}=`));
  if (i < 0) return def;
  if (argv[i].includes("=")) return argv[i].slice(argv[i].indexOf("=") + 1);
  // `--port 8945` 这种空格给值的形式：下一个参数不是开关才算值，否则这个开关是布尔
  const next = argv[i + 1];
  return next && !next.startsWith("--") ? next : true;
};
const flags = (name) => argv.includes(`--${name}`);

const HOST = arg("host", "127.0.0.1");
const PORT = Number(arg("port", 8945));
const PI_BIN = arg("pi-bin", process.env.PI_BIN || findPi());
const CWD = arg("cwd", process.env.HOME);
const DEFAULT_CWD = process.env.HOME;
const TOKEN_FILE = arg(
  "token-file",
  path.join(process.env.HOME || ".", ".pi/agent/pi-android.token")
);
const IDLE_EXIT = Number(arg("idle-exit", 600));
const ALLOW_BASH = flags("allow-bash");
const VERBOSE = flags("verbose-log");
const PI_ARGS = (arg("pi-arg", "") ? String(arg("pi-arg", "")).split(",") : []).concat(
  flags("session") ? [] : ["--no-session"]
);

/** 交互命令白名单：能读状态、能提问、能中断、能改模型/会话，但不能拿到 shell。 */
const ALLOWED = new Set([
  "prompt", "steer", "follow_up", "abort", "clear_queue",
  "new_session", "switch_session", "fork", "clone",
  "get_state", "get_messages", "get_last_assistant_text", "get_tree", "get_entries",
  "get_session_stats", "get_fork_messages",
  "set_model", "cycle_model", "get_available_models",
  "set_thinking_level", "cycle_thinking_level", "get_available_thinking_levels",
  "set_steering_mode", "set_follow_up_mode",
  "set_auto_compaction", "set_auto_retry", "abort_retry", "compact",
  "export_html",
  "extension_ui_response",
  "set_session_name", "get_session_name",
]);
if (ALLOW_BASH) {
  ALLOWED.add("bash");
  ALLOWED.add("abort_bash");
}

function findPi() {
  for (const p of [
    path.join(os.homedir(), ".pi/agent/bin/pi"),
    "/data/data/com.termux/files/home/.pi/agent/bin/pi",
  ]) {
    if (fs.existsSync(p)) return p;
  }
  return "pi";
}

const token = (() => {
  try {
    return fs.readFileSync(TOKEN_FILE, "utf8").trim();
  } catch {
    return null;
  }
})();

const wss = new WebSocketServer({ host: HOST, port: PORT, maxPayload: 8 * 1024 * 1024 });
/** @type {{ws: import('ws').WebSocket, authed: boolean}[]} */
const clients = [];
let child = null;
let stdoutBuf = "";
let stderrTail = [];
let idleTimer = null;
let seq = 0;

const log = (...a) => console.error(`[bridge ${new Date().toISOString()}]`, ...a);

function broadcast(obj) {
  const line = JSON.stringify(obj);
  for (const c of clients) if (c.authed) safeSend(c.ws, line);
}
function safeSend(ws, line) {
  try {
    ws.send(line);
  } catch {
    /* 客户端已断开 */
  }
}
function dispatch(rawEvent, ws) {
  // broadcast() 自己会 JSON.stringify，这里必须传对象；
  // 传已经 stringify 过的字符串会把事件做成双重编码，客户端 JSONObject 直接解析失败。
  const envelope = { v: 1, kind: "event", event: rawEvent };
  if (ws) safeSend(ws, JSON.stringify(envelope));
  else broadcast(envelope);
}

function piState() {
  return child && child.exitCode === null ? "online" : "offline";
}

function ensurePi() {
  if (child && child.exitCode === null) return child;
  if (idleTimer) {
    clearTimeout(idleTimer);
    idleTimer = null;
  }
  broadcast({ v: 1, kind: "status", state: "starting" });
  // spawn 而不是 exec：`pi` 的 wrapper 是 shell 脚本，spawn + shell:false 能拿到直接的 PID，
  // 便于 stop() 时整棵进程树收尾，也不会被 shell 再包一层。
  child = spawn(PI_BIN, ["--mode", "rpc", ...PI_ARGS], {
    cwd: CWD && fs.existsSync(CWD) ? CWD : DEFAULT_CWD,
    stdio: ["pipe", "pipe", "pipe"],
    env: process.env,
  });
  log(`spawn ${PI_BIN} --mode rpc pid=${child.pid}`);
  broadcast({ v: 1, kind: "status", state: "starting", pid: child.pid });

  child.stdout.setEncoding("utf8");
  child.stdout.on("data", (chunk) => {
    stdoutBuf += chunk;
    // JSONL framing：只按 LF 切；Node 的 readline 还会按 U+2028/2029 切，可能切断 JSON 字符串
    let nl;
    while ((nl = stdoutBuf.indexOf("\n")) >= 0) {
      const line = stdoutBuf.slice(0, nl).replace(/\r$/, "");
      stdoutBuf = stdoutBuf.slice(nl + 1);
      if (!line.trim()) continue;
      let rec;
      try {
        rec = JSON.parse(line);
      } catch (e) {
        log("stdout 非 JSON 行:", line.slice(0, 200));
        continue;
      }
      routeRecord(rec);
    }
  });

  child.stderr.setEncoding("utf8");
  child.stderr.on("data", (chunk) => {
    stderrTail.push(chunk);
    if (stderrTail.length > 20) stderrTail.shift();
    if (VERBOSE) broadcast({ v: 1, kind: "log", line: chunk });
  });

  child.on("exit", (code, signal) => {
    log(`pi 退出 code=${code} signal=${signal}`);
    broadcast({
      v: 1,
      kind: "status",
      state: "offline",
      error: `pi exited code=${code} signal=${signal}`,
      stderr: stderrTail.join("").slice(-2000),
    });
    child = null;
  });
  child.on("error", (e) => {
    log("spawn 失败:", e.message);
    broadcast({ v: 1, kind: "status", state: "offline", error: e.message });
    child = null;
  });
  return child;
}

/** Pi stdout 的每一条记录：响应按 id 回填，其余作为事件广播。 */
function routeRecord(rec) {
  if (rec && rec.type === "response") {
    const pending = [...pendingReqs.entries()].find(([, v]) => v.command === rec.command);
    if (pending) {
      const [id, entry] = pending;
      pendingReqs.delete(id);
      if (rec.id) pendingReqs.delete(rec.id);
      safeSend(
        entry.ws,
        JSON.stringify({
          v: 1,
          kind: "response",
          id,
          ok: !!rec.success,
          command: rec.command,
          data: rec.data,
          error: rec.error,
        })
      );
      return;
    }
  }
  dispatch(rec);
}

/** id -> {ws, command, ts}；Pi 的 response 会带上同样的 id。 */
const pendingReqs = new Map();

function writeCommand(id, ws, command, params) {
  if (!ALLOWED.has(command)) {
    safeSend(
      ws,
      JSON.stringify({ v: 1, kind: "response", id, ok: false, command, error: `命令不允许: ${command}` })
    );
    return;
  }
  const rec = { id, type: command, ...(params || {}) };
  pendingReqs.set(id, { ws, command, ts: Date.now() });
  log(`rpc ${command} ${id}${command === "prompt" ? ` len=${String((params || {}).message || "").length}` : ""}`);
  try {
    child.stdin.write(JSON.stringify(rec) + "\n");
  } catch (e) {
    pendingReqs.delete(id);
    safeSend(ws, JSON.stringify({ v: 1, kind: "response", id, ok: false, command, error: e.message }));
  }
}

function stopPi(why) {
  if (!child) return;
  log(`关闭 pi: ${why}`);
  try {
    child.stdin.end();
  } catch {}
  // 文档要求：关闭 stdin 请求有序退出；给 5s 再兜底 SIGTERM
  const t = setTimeout(() => {
    try {
      process.kill(child.pid, "SIGTERM");
    } catch {}
  }, 5000);
  child.once("exit", () => clearTimeout(t));
}

function scheduleIdleExit() {
  if (idleTimer) clearTimeout(idleTimer);
  if (!IDLE_EXIT || clients.length) return;
  idleTimer = setTimeout(() => {
    if (!clients.length) {
      stopPi("空闲超时");
      if (process.env.PI_BRIDGE_EXIT_ON_IDLE === "1") process.exit(0);
    }
  }, IDLE_EXIT * 1000);
}

wss.on("connection", (ws, req) => {
  const c = { ws, authed: !token };
  clients.push(c);
  log(`client ${req.socket.remoteAddress}:${req.socket.remotePort} ${c.authed ? "(无 token 配置)" : "(待鉴权)"}`);

  ws.on("message", (data) => {
    let msg;
    try {
      msg = JSON.parse(String(data));
    } catch {
      return;
    }
    if (!c.authed) {
      if (msg.auth === token) {
        c.authed = true;
        safeSend(ws, JSON.stringify({ v: 1, kind: "status", state: token ? "online" : piState() }));
        log("client 鉴权通过");
      } else {
        safeSend(ws, JSON.stringify({ v: 1, kind: "status", state: "offline", error: "bad token" }));
        ws.close(4001, "bad token");
      }
      return;
    }
    if (msg.type === "ping") {
      safeSend(ws, JSON.stringify({ v: 1, kind: "pong", t: Date.now() }));
      return;
    }
    if (msg.type === "stop_pi") {
      stopPi("client 请求");
      return;
    }
    if (msg.rpc) {
      ensurePi();
      writeCommand(msg.id || String(++seq), ws, msg.rpc, msg.params || {});
    }
  });

  ws.on("close", () => {
    const i = clients.indexOf(c);
    if (i >= 0) clients.splice(i, 1);
    scheduleIdleExit();
  });
  ws.on("error", () => {});

  safeSend(ws, JSON.stringify({ v: 1, kind: "status", state: c.authed ? piState() : "offline" }));
});

process.on("SIGTERM", () => {
  stopPi("SIGTERM");
  wss.close(() => process.exit(0));
  setTimeout(() => process.exit(0), 3000);
});
process.on("SIGINT", () => {
  stopPi("SIGINT");
  wss.close(() => process.exit(0));
});

if (!token) {
  log("! 未读到 token 文件，任何本机 App 都能连进来。建议建一份 600 权限的 token：");
  log(`  umask 077; head -c 24 /dev/urandom | base64 > ${TOKEN_FILE}`);
}
log(`桥 listening ws://${HOST}:${PORT}/ws  pi=${PI_BIN}  cwd=${CWD}  bash=${ALLOW_BASH ? "on" : "off"}`);
