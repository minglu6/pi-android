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
 *    `bash` 命令默认关闭（--allow-bash 显式开）；这不限制 prompt 调用 agent 工具。
 *
 * 线协议（client → server）：
 *    {"id":"1","rpc":"get_state","params":{}}
 *    {"id":"2","rpc":"prompt","params":{"message":"hi"}}
 *    {"type":"ping"}                        // 仅鉴权后心跳
 * server → client：
 *    {"v":1,"kind":"status","state":"online"|"starting"|"offline","error"?,"pid"?}
 *    {"v":1,"kind":"response","id":"1","ok":true,"command":"get_state","data":{...}}
 *    {"v":1,"kind":"response","id":"1","ok":false,"command":"get_state","error":"..."}
 *    {"v":1,"kind":"event","event":{...Pi 的 stdout 记录原样转发...}}
 *    {"v":1,"kind":"pong","t":<ms>}
 *    {"v":1,"kind":"log","line":"..."}      // Pi 的 stderr 摘录，--verbose-log 时下发
 */
import { spawn } from "node:child_process";
import { createHash, timingSafeEqual } from "node:crypto";
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
// Pi 默认持久保存会话。旧 --session 开关仍可用（现在是兼容 no-op）；
// 只有显式 --no-session 才启用临时会话，也允许通过 --pi-arg 传入。
const PI_ARGS = (arg("pi-arg", "") ? String(arg("pi-arg", "")).split(",") : []).concat(
  flags("no-session") ? ["--no-session"] : []
);

/** RPC 命令白名单；禁用直接 bash 不会限制 prompt 驱动的 agent 工具。 */
const ALLOWED = new Set([
  "prompt", "steer", "follow_up", "abort", "clear_queue",
  "new_session", "switch_session", "fork", "clone",
  "get_state", "get_session_name", "get_messages", "get_last_assistant_text", "get_tree", "get_entries",
  "get_session_stats", "get_fork_messages",
  "get_commands", // 斜杠命令发现：扩展 / prompt 模板 / skills
  "set_model", "cycle_model", "get_available_models",
  "set_thinking_level", "cycle_thinking_level", "get_available_thinking_levels",
  "set_steering_mode", "set_follow_up_mode",
  "set_auto_compaction", "set_auto_retry", "abort_retry", "compact",
  "export_html",
  "extension_ui_response",
  "set_session_name",
]);
if (ALLOW_BASH) {
  ALLOWED.add("bash");
  ALLOWED.add("abort_bash");
}

/** 当前会话显示名；get_session_name 通过官方 get_state 获取，不返回乐观缓存。 */
let currentSessionName = "";

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
  let value;
  try {
    value = fs.readFileSync(TOKEN_FILE, "utf8").trim();
  } catch {
    throw new Error("无法读取 token 文件；拒绝启动未鉴权的连接器");
  }
  if (!value) throw new Error("token 文件为空；拒绝启动未鉴权的连接器");
  return value;
})();
// 固定长度摘要做常量时间比较；不记录 token 内容。
const tokenDigest = createHash("sha256").update(token).digest();
function authenticates(value) {
  return typeof value === "string" && timingSafeEqual(
    tokenDigest, createHash("sha256").update(value).digest()
  );
}

const wss = new WebSocketServer({ host: HOST, port: PORT, maxPayload: 8 * 1024 * 1024 });
/** @type {{ws: import('ws').WebSocket, authed: boolean}[]} */
const clients = [];
let child = null;
let stdoutBuf = "";
let stderrTail = [];
let idleTimer = null;
let seq = 0;
let rpcSeq = 0;

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
  const pi = spawn(PI_BIN, ["--mode", "rpc", ...PI_ARGS], {
    cwd: CWD && fs.existsSync(CWD) ? CWD : DEFAULT_CWD,
    stdio: ["pipe", "pipe", "pipe"],
    env: process.env,
  });
  child = pi;
  stdoutBuf = "";
  stderrTail = [];
  log(`spawn ${PI_BIN} --mode rpc pid=${pi.pid}`);
  broadcast({ v: 1, kind: "status", state: "starting", pid: pi.pid });

  pi.stdout.setEncoding("utf8");
  pi.stdout.on("data", (chunk) => {
    if (child !== pi) return;
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
        log("stdout 非 JSON 行（内容不记录）");
        continue;
      }
      routeRecord(rec);
    }
  });

  pi.stderr.setEncoding("utf8");
  pi.stderr.on("data", (chunk) => {
    if (child !== pi) return;
    stderrTail.push(chunk);
    if (stderrTail.length > 20) stderrTail.shift();
    if (VERBOSE) broadcast({ v: 1, kind: "log", line: chunk });
  });

  const failed = (error) => {
    if (child !== pi) return;
    log(error);
    for (const entry of pendingReqs.values()) {
      respond(entry.ws, entry.clientId, false, entry.command, null, error);
    }
    pendingReqs.clear();
    stdoutBuf = "";
    currentSessionName = "";
    uiRequests.clear();
    const stderr = stderrTail.join("").slice(-2000);
    stderrTail = [];
    child = null;
    broadcast({ v: 1, kind: "status", state: "offline", error, stderr });
  };
  pi.on("exit", (code, signal) => failed(`pi exited code=${code} signal=${signal}`));
  pi.on("error", (e) => failed(`spawn failed: ${e.message}`));
  // EPIPE / 异步写错误不是 write() 的同步异常，必须监听 stream error。
  pi.stdin.on("error", (e) => {
    failed(`pi stdin failed: ${e.message}`);
    pi.kill("SIGTERM");
  });
  return pi;
}

/** Pi stdout 的每一条记录：响应按 id 回填，其余作为事件广播。 */
function routeRecord(rec) {
  if (!rec || typeof rec !== "object" || Array.isArray(rec)) return;
  if (rec.type === "session_info_changed") {
    currentSessionName = typeof rec.name === "string" ? rec.name : "";
  }
  if (rec.type === "extension_ui_request" && DIALOG_METHODS.has(rec.method) && typeof rec.id === "string") {
    uiRequests.set(rec.id, { method: rec.method });
  }
  if (rec.type === "bash_execution_update") {
    // 官方 RPC 的唯一带命令 ID 的事件；同样还原客户端 ID，只送到发起方。
    const entry = typeof rec.id === "string" ? pendingReqs.get(rec.id) : null;
    if (entry?.command === "bash") dispatch({ ...rec, id: entry.clientId }, entry.ws);
    return;
  }
  if (rec.type === "response") {
    // 所有 stdin 命令都有桥生成的唯一 ID；未知/旧响应绝不能按 command 猜归属或广播。
    const entry = typeof rec.id === "string" ? pendingReqs.get(rec.id) : null;
    if (!entry) return;
    pendingReqs.delete(rec.id);
    if (rec.command !== entry.piCommand) {
      respond(entry.ws, entry.clientId, false, entry.command, null, "Pi 响应命令不匹配");
      return;
    }
    if (rec.success && entry.piCommand === "get_state") {
      currentSessionName = typeof rec.data?.sessionName === "string" ? rec.data.sessionName : "";
    }
    if (rec.success && !rec.data?.cancelled &&
        ["new_session", "switch_session", "fork", "clone"].includes(entry.command)) {
      currentSessionName = "";
    }
    const data = rec.success && entry.command === "get_session_name"
      ? { name: currentSessionName } : rec.data;
    respond(entry.ws, entry.clientId, !!rec.success, entry.command, data, rec.error);
    return;
  }
  dispatch(rec);
}

/** 唯一内部 ID -> {ws, clientId, command, piCommand}；不以客户端 ID 为 Map key。 */
const pendingReqs = new Map();

/**
 * 未答复的阻塞式 UI 请求（select / confirm / input / editor）。
 * 这类请求会让 Pi **停在那里等** —— 如果请求的客户端中途断开（APK 被杀、特效、锁屏），
 * 没有任何人会回答，Pi 就一直挂着，之后所有 prompt 都会报 "Agent is already processing"。
 * 所以客户端全部离开时，桥代表它们回一条 cancelled 把 Pi 松开。
 * 代价要说清楚：真正还在思考/等待人类点击的场景点上钮之前，如果最后一个客户端断开，也会被取消。
 */
const DIALOG_METHODS = new Set(["select", "confirm", "input", "editor"]);
const uiRequests = new Map();   // Pi UI id -> {method}，不是普通 RPC pending

function releaseStuckDialogs(why) {
  let released = 0;
  for (const [id] of [...uiRequests]) {
    if (child && child.exitCode === null) {
      try {
        child.stdin.write(JSON.stringify({ type: "extension_ui_response", id, cancelled: true }) + "\n");
      } catch {
        continue;
      }
    }
    uiRequests.delete(id);
    released++;
  }
  if (released) log(`${why}：补答 ${released} 个未答复的 UI 对话框（cancelled），避免 Pi 卡住`);
}

/** 会话文件根目录 `$HOME/.pi/agent/sessions/<cwd 编码>/<时间>_<id>.jsonl` */
const SESSIONS_DIR = arg("sessions-dir", path.join(os.homedir(), ".pi/agent/sessions"));

/**
 * 列出本地会话文件。Pi 的 RPC 只有 get_tree（当前会话内部的树）而没有“有哪些会话”，
 * 所以列目录这件事只能由桥来做 —— 这是本桥自己加的能力，不是 Pi 的命令。
 * 只读文件头与前若干行取首条用户消息做预览，不整文件读进内存。
 */
function listSessions(limit) {
  const files = [];
  const walk = (dir) => {
    let entries = [];
    try {
      entries = fs.readdirSync(dir, { withFileTypes: true });
    } catch {
      return;
    }
    for (const e of entries) {
      const full = path.join(dir, e.name);
      if (e.isDirectory()) walk(full);
      else if (e.isFile() && e.name.endsWith(".jsonl")) files.push(full);
    }
  };
  walk(SESSIONS_DIR);

  // 先只 stat 排序（323 个文件也很快），再只对要返回的前 N 个做读解码 —— 不要对全部文件进水 involving 提纯
  const statted = [];
  for (const file of files) {
    try {
      const st = fs.statSync(file);
      statted.push({ path: file, mtimeMs: st.mtimeMs, bytes: st.size });
    } catch {
      /* 读不到就跳过 */
    }
  }
  statted.sort((a, b) => b.mtimeMs - a.mtimeMs);
  return statted.slice(0, limit).map((entry) => enrichSession(entry));
}

/**
 * 读一小段文件内容填预览。头 64 KiB 取 session header 与首条用户消息；
 * 头里没有（长会话把早期消息挤出了头）就从尾部 128 KiB 取最近一条用户消息 —— 更符合“切回上次那条”的直觉。
 */
function enrichSession(entry) {
  const out = {
    path: entry.path,
    bytes: entry.bytes,
    mtimeMs: entry.mtimeMs,
    id: null,
    cwd: null,
    timestamp: null,
    name: null,
    preview: "",
  };
  try {
    const fh = fs.openSync(entry.path, "r");
    const headLen = Math.min(entry.bytes, 65536);
    const head = Buffer.alloc(headLen);
    fs.readSync(fh, head, 0, headLen, 0);
    scanHead(out, head.toString("utf8"));
    if (!out.preview && entry.bytes > headLen) {
      const tailLen = Math.min(entry.bytes, 131072);
      const tail = Buffer.alloc(tailLen);
      fs.readSync(fh, tail, 0, tailLen, Math.max(0, entry.bytes - tailLen));
      // 尾部切片可能从一行中间开始，丢掉第一段不完整的行
      const lines = tail.toString("utf8").split("\n").slice(1);
      for (const line of lines) {
        const preview = userTextOfLine(line);
        if (preview) out.preview = preview;
      }
    }
    fs.closeSync(fh);
  } catch {
    /* 预览拿不到也不影响切换 */
  }
  return out;
}

function scanHead(out, text) {
  for (const line of text.split("\n")) {
    if (!line.trim()) continue;
    let rec;
    try {
      rec = JSON.parse(line);
    } catch {
      continue;
    }
    if (rec.type === "session") {
      out.id = rec.id || null;
      out.cwd = rec.cwd || null;
      out.timestamp = rec.timestamp || null;
      if (typeof rec.name === "string" && rec.name.trim()) out.name = rec.name.trim();
      continue;
    }
    // 后续改名常见写成独立记录；后写覆盖先写
    if (
      (rec.type === "session_info" || rec.type === "session_name" || rec.type === "name") &&
      typeof (rec.name || rec.title) === "string"
    ) {
      const n = String(rec.name || rec.title).trim();
      if (n) out.name = n;
      continue;
    }
    if (!out.preview) {
      const preview = userTextOfLine(line);
      if (preview) out.preview = preview;
    }
  }
  if (out.preview) out.preview = out.preview.slice(0, 200);
}

function userTextOfLine(line) {
  if (!line.trim()) return "";
  try {
    const rec = JSON.parse(line);
    if (rec.type === "message" && rec.message && rec.message.role === "user") {
      return textOfContentLike(rec.message.content).slice(0, 200);
    }
  } catch {
    return "";
  }
  return "";
}

function textOfContentLike(content) {
  let raw = "";
  if (typeof content === "string") raw = content;
  else if (Array.isArray(content)) {
    for (const part of content) {
      if (part && typeof part === "object" && part.type === "text" && typeof part.text === "string") {
        raw = part.text;
        break;
      }
    }
  }
  return cleanPreviewText(raw);
}

/** 去掉 pi-timestamp / HTML 注释等元数据，避免会话列表标题泄漏 `<!--pi-timestamp:…-->`。 */
function cleanPreviewText(text) {
  if (typeof text !== "string" || !text) return "";
  let s = text
    .replace(/<!--[\s\S]*?-->/g, " ")
    .replace(/<\/?[a-zA-Z][^>]*>/g, " ")
    .replace(/\bpi-timestamp\s*:\s*[^\s<]+/gi, " ")
    .replace(/\s+/g, " ")
    .trim();
  return s;
}

function assertUnderSessionsDir(sessionPath) {
  if (typeof sessionPath !== "string" || !sessionPath.trim()) {
    throw new Error("缺少 sessionPath");
  }
  const root = path.resolve(SESSIONS_DIR);
  const resolved = path.resolve(sessionPath);
  const prefix = root.endsWith(path.sep) ? root : root + path.sep;
  if (resolved !== root && !resolved.startsWith(prefix)) {
    throw new Error("sessionPath 不在会话目录内");
  }
  if (!resolved.endsWith(".jsonl")) {
    throw new Error("不是会话文件（.jsonl）");
  }
  // 词法路径检查不足：中间目录可能是指向会话目录外的 symlink。
  const realRoot = fs.realpathSync(root);
  const realTarget = fs.realpathSync(resolved);
  if (!realTarget.startsWith(realRoot + path.sep) || !fs.lstatSync(resolved).isFile()) {
    throw new Error("不是会话目录内的普通文件");
  }
  return resolved;
}

function writeCommand(id, ws, command, params) {
  if (!params || typeof params !== "object" || Array.isArray(params) ||
      Object.hasOwn(params, "type") ||
      (command !== "extension_ui_response" && Object.hasOwn(params, "id"))) {
    respond(ws, id, false, command, null, "params 格式无效或含保留字段 type/id");
    return;
  }
  // 连接器本地命令：不进 Pi 的 stdin，直接由本进程回答
  if (command === "list_sessions") {
    const t0 = Date.now();
    let items = [];
    let err = null;
    try {
      items = listSessions(Math.max(1, Math.min(500, Number((params || {}).limit) || 50)));
    } catch (e) {
      err = e.message;
    }
    log(`local list_sessions -> ${items.length} 项 (${Date.now() - t0}ms)`);
    respond(ws, id, true, "list_sessions",
      err ? { sessions: [], error: err } : { sessions: items, dir: SESSIONS_DIR }, err);
    return;
  }
  if (command === "delete_session") {
    try {
      const target = assertUnderSessionsDir((params || {}).sessionPath);
      fs.unlinkSync(target);
      log(`local delete_session ${target}`);
      respond(ws, id, true, "delete_session", { path: target, deleted: true }, null);
    } catch (e) {
      respond(ws, id, false, "delete_session", null, e.message);
    }
    return;
  }
  if (!ALLOWED.has(command)) {
    safeSend(
      ws,
      JSON.stringify({ v: 1, kind: "response", id, ok: false, command, error: `命令不允许: ${command}` })
    );
    return;
  }
  // UI 回答使用 Pi 提供的 UI ID，不生成内部 ID，也不会产生普通 command response。
  if (command === "extension_ui_response") {
    const request = typeof params.id === "string" ? uiRequests.get(params.id) : null;
    const validAnswer = params.cancelled === true || (request?.method === "confirm"
      ? typeof params.confirmed === "boolean" : typeof params.value === "string");
    if (!request || !validAnswer || !child || child.stdin.destroyed || child.stdin.writableEnded) {
      respond(ws, id, false, command, null, "UI 请求已失效或回答格式无效");
      return;
    }
    const answer = params.cancelled === true ? { cancelled: true }
      : request.method === "confirm" ? { confirmed: params.confirmed } : { value: params.value };
    try {
      child.stdin.write(JSON.stringify({ type: command, id: params.id, ...answer }) + "\n");
      uiRequests.delete(params.id);
    } catch (e) {
      respond(ws, id, false, command, null, e.message);
    }
    return;
  }
  const pi = ensurePi();
  if (!pi || pi.stdin.destroyed || pi.stdin.writableEnded) {
    respond(ws, id, false, command, null, "Pi 正在退出，请稍后重试");
    return;
  }
  const internalId = `bridge-${++rpcSeq}`;
  // get_session_name 是兼容别名；名称的权威来源是官方 get_state，不是本地乐观写入。
  const piCommand = command === "get_session_name" ? "get_state" : command;
  const rec = { ...params, id: internalId, type: piCommand };
  pendingReqs.set(internalId, { ws, clientId: id, command, piCommand });
  if (command === "set_model") {
    log(`rpc set_model ${id} provider=${params && params.provider} modelId=${params && params.modelId}`);
  } else {
    log(`rpc ${command} ${id}${command === "prompt" ? ` len=${String((params || {}).message || "").length}` : ""}`);
  }
  try {
    pi.stdin.write(JSON.stringify(rec) + "\n");
  } catch (e) {
    pendingReqs.delete(internalId);
    safeSend(ws, JSON.stringify({ v: 1, kind: "response", id, ok: false, command, error: e.message }));
  }
}

function respond(ws, id, ok, command, data, error) {
  safeSend(ws, JSON.stringify({ v: 1, kind: "response", id, ok, command, data: data || null, error: error || null }));
}

function stopPi(why) {
  const pi = child;
  if (!pi) return;
  log(`关闭 pi: ${why}`);
  try {
    pi.stdin.end();
  } catch {}
  // 文档要求：关闭 stdin 请求有序退出；给 5s 再兜底 SIGTERM
  const t = setTimeout(() => {
    try {
      pi.kill("SIGTERM");
    } catch {}
  }, 5000);
  pi.once("exit", () => clearTimeout(t));
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
  const c = { ws, authed: false };
  clients.push(c);
  log(`client ${req.socket.remoteAddress}:${req.socket.remotePort} (待鉴权)`);

  ws.on("message", (data) => {
    let msg;
    try {
      msg = JSON.parse(String(data));
    } catch {
      return;
    }
    if (!msg || typeof msg !== "object" || Array.isArray(msg)) return;
    if (!c.authed) {
      if (authenticates(msg.auth)) {
        c.authed = true;
        safeSend(ws, JSON.stringify({ v: 1, kind: "status", state: "online" }));
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
    if (typeof msg.rpc === "string") {
      if (msg.id !== undefined && (typeof msg.id !== "string" || !msg.id)) {
        respond(ws, null, false, msg.rpc, null, "id 必须是非空字符串");
        return;
      }
      writeCommand(msg.id || String(++seq), ws, msg.rpc, msg.params === undefined ? {} : msg.params);
    }
  });

  ws.on("close", () => {
    const i = clients.indexOf(c);
    if (i >= 0) clients.splice(i, 1);
    for (const [id, entry] of pendingReqs) {
      if (entry.ws === ws) pendingReqs.delete(id);
    }
    // 未鉴权连接不能阻止取消无人能回答的对话框。
    if (!clients.some((client) => client.authed)) releaseStuckDialogs("所有已鉴权客户端已断开");
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

wss.on("listening", () => {
  log(`连接器 listening ws://${HOST}:${PORT}/ws  pi=${PI_BIN}  cwd=${CWD}  bash=${ALLOW_BASH ? "on" : "off"}`);
});
