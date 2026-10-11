#!/usr/bin/env node
/**
 * pi-rpc-bridge — WebSocket ↔ Pi RPC 桥（Termux 侧）
 *
 *   APK ──ws://127.0.0.1:8945──→ 本进程 ──stdin/stdout JSONL──→ pi --mode rpc
 *          ◄──────── 事件流 ──────────────────────────────────┘
 *
 * 设计要点（每条都有一个代价/alternative，见 docs/bridge.md）：
 *  - 只以官方支持的方式驱动 Pi：`pi --mode rpc` 的 JSONL 子协议，不 import Pi 内部 dist。
 *  - 默认绑 127.0.0.1 + token；`--tailscale` 只绑 CGNAT，`--host` 禁 0.0.0.0。token 必填。
 *  - 单个 Pi 子进程，客户端共享；最后一个客户端断开后按 --idle-exit 秒空闲退出（默认 600s），
 *    便于 runit/前台两种运行方式。
 *  - 能力白名单：默认 read-only + prompt/abort 等，以及 Pocket 所需官方 `bash` / `abort_bash`
 *    （`--allow-bash` 仍为同义词）。白名单不限制 prompt 驱动的 agent 工具。
 *  - 计划模式：仅向 prompt/steer/follow_up 注入只读指令（Pi RPC 无工具 allow-list，无法硬拦截）。
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
import { spawn, spawnSync } from "node:child_process";
import { createHash, timingSafeEqual } from "node:crypto";
import { WebSocketServer } from "ws";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import {AttachmentStore, prepareAttachments, prepareInlineImages} from "./attachment-store.mjs";
import {
  parseWhen, nextRepeat, describeMoment, describeRepeat, WHEN_HELP, knownZone,
} from "./schedule-when.mjs";

/** Stamped by the App「更新并重启连接器」script (sed); get_version returns these. */
const CONNECTOR_SHA = "unknown";
const CONNECTOR_DATE = "unknown";

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

const PORT = Number(arg("port", 8945));
const WANT_TAILSCALE = flags("tailscale");
function detectTailscaleIp() {
  // Prefer `tailscale ip -4`; fall back to parsing `ip -4 addr show tailscale0`.
  try {
    const r = spawnSync("tailscale", ["ip", "-4"], { encoding: "utf8", timeout: 4000 });
    if (r.status === 0) {
      const ip = String(r.stdout || "").trim().split(/\s+/)[0];
      if (/^100\.\d+\.\d+\.\d+$/.test(ip)) return ip;
    }
  } catch { /* ignore */ }
  try {
    const r = spawnSync("ip", ["-4", "-o", "addr", "show", "dev", "tailscale0"], {
      encoding: "utf8", timeout: 4000,
    });
    const m = String(r.stdout || "").match(/\binet\s+(100\.\d+\.\d+\.\d+)\//);
    if (m) return m[1];
  } catch { /* ignore */ }
  return null;
}
function resolveListenHost() {
  if (WANT_TAILSCALE) {
    const ip = detectTailscaleIp();
    if (!ip) {
      console.error("[pi-rpc-bridge] --tailscale: 未检测到 tailscale0 / `tailscale ip -4`，拒绝启动");
      process.exit(2);
    }
    return ip;
  }
  return arg("host", "127.0.0.1");
}
const HOST = resolveListenHost();
const PI_BIN = arg("pi-bin", process.env.PI_BIN || findPi());
const DEFAULT_CWD = process.env.HOME || ".";
/** Persist last App/bridge working directory across connector restarts. */
const CWD_FILE = path.join(os.homedir(), ".pi/agent/pi-android.cwd");
function loadPersistedCwd() {
  try {
    const t = fs.readFileSync(CWD_FILE, "utf8").trim();
    if (t && fs.existsSync(t) && fs.statSync(t).isDirectory()) return path.resolve(t);
  } catch { /* ignore */ }
  return null;
}
// No Pi RPC for set_cwd — bridge owns session cwd (spawn + project file/git helpers).
const argCwd = arg("cwd", "");
let sessionCwd = path.resolve(
  (argCwd && fs.existsSync(argCwd) ? argCwd : null)
  || loadPersistedCwd()
  || DEFAULT_CWD
);
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
  // Pocket ports: !shell / !!shell (Pi official bash RPC)
  "bash", "abort_bash",
]);
// --allow-bash kept as documented opt-in synonym; bash is now always whitelisted for Pocket.
void ALLOW_BASH;

/** 当前会话显示名；get_session_name 通过官方 get_state 获取，不返回乐观缓存。 */
let currentSessionName = "";

// ---------- Done-when (/until): Pocket-style goal check after each agent turn ----------
const UNTIL_MAX = 5;
const UNTIL_TIMEOUT_MS = 600_000;
const UNTIL_TAIL = 3000;
const UNTIL_PREFIX = "[until] ";
/** @type {null | {command: string, tries: number, max: number, status: string, lastCode?: number, lastTail?: string}} */
let untilGoal = null;
let untilBusy = false;
/** @type {import('node:child_process').ChildProcess | null} */
let untilProc = null;

function untilSnapshot() {
  if (!untilGoal) return { type: "until_update", status: "idle" };
  return {
    type: "until_update",
    command: untilGoal.command,
    tries: untilGoal.tries,
    max: untilGoal.max,
    status: untilGoal.status,
    lastCode: untilGoal.lastCode ?? null,
    lastTail: untilGoal.lastTail ?? "",
  };
}

function broadcastUntil() {
  dispatch(untilSnapshot());
}

function setUntilGoal(command) {
  const check = String(command || "").trim();
  if (!check) throw new Error("请指定要通过的命令，例如 /until npm test");
  if (check.length > 2000) throw new Error("检查命令过长（最多 2000 字符）");
  abortUntilProc();
  untilGoal = { command: check, tries: 0, max: UNTIL_MAX, status: "working" };
  broadcastUntil();
  return untilSnapshot();
}

function clearUntilGoal() {
  abortUntilProc();
  if (!untilGoal) {
    broadcastUntil();
    return { status: "idle" };
  }
  untilGoal = { ...untilGoal, status: "stopped" };
  const snap = untilSnapshot();
  broadcastUntil();
  untilGoal = null;
  return snap;
}

function abortUntilProc() {
  if (untilProc && untilProc.exitCode === null) {
    try { untilProc.kill("SIGKILL"); } catch { /* ignore */ }
  }
  untilProc = null;
}

/** Fire a prompt into Pi with no client response (bridge-owned follow-up). */
function bridgeOwnedPrompt(message) {
  const pi = ensurePi();
  if (!pi || pi.stdin.destroyed || pi.stdin.writableEnded) return false;
  const internalId = `bridge-${++rpcSeq}`;
  const sink = { send() {} };
  const timer = setTimeout(() => pendingReqs.delete(internalId), 120_000);
  timer.unref();
  pendingReqs.set(internalId, {
    ws: sink, clientId: internalId, command: "prompt", piCommand: "prompt", timer,
  });
  try {
    pi.stdin.write(JSON.stringify({ id: internalId, type: "prompt", message }) + "\n");
    return true;
  } catch (e) {
    clearTimeout(timer);
    pendingReqs.delete(internalId);
    log(`until follow-up prompt failed: ${e.message}`);
    return false;
  }
}

function runUntilShell(command) {
  return new Promise((resolve) => {
    const cwd = resolveProjectCwd();
    let out = "";
    // Prefer bash -lc when available (Termux); fall back to sh -c.
    const shell = process.env.SHELL && fs.existsSync(process.env.SHELL)
      ? process.env.SHELL : "/bin/sh";
    const child = spawn(shell, ["-lc", command], {
      cwd,
      env: process.env,
      stdio: ["ignore", "pipe", "pipe"],
    });
    untilProc = child;
    const timer = setTimeout(() => {
      try { child.kill("SIGKILL"); } catch { /* ignore */ }
    }, UNTIL_TIMEOUT_MS);
    timer.unref();
    const onChunk = (buf) => {
      out = (out + buf.toString()).slice(-UNTIL_TAIL);
    };
    child.stdout.on("data", onChunk);
    child.stderr.on("data", onChunk);
    child.on("close", (code, signal) => {
      clearTimeout(timer);
      if (untilProc === child) untilProc = null;
      if (signal) {
        resolve({
          passed: false,
          code: -1,
          tail: `${out}\n检查被中断（${signal === "SIGKILL" ? "超时或停止" : signal}）`
            .trim().slice(-UNTIL_TAIL),
        });
        return;
      }
      resolve({ passed: code === 0, code: code ?? -1, tail: out.trim() });
    });
    child.on("error", (e) => {
      clearTimeout(timer);
      if (untilProc === child) untilProc = null;
      resolve({ passed: false, code: -1, tail: e.message });
    });
  });
}

async function maybeRunUntilCheck(source) {
  // Pocket: in plan mode the check waits until the plan is approved.
  if (planModeOn) return;
  if (!untilGoal || untilGoal.status !== "working" || untilBusy) return;
  untilBusy = true;
  const command = untilGoal.command;
  untilGoal = { ...untilGoal, status: "checking" };
  broadcastUntil();
  let result;
  try {
    result = await runUntilShell(command);
  } catch (e) {
    result = { passed: false, code: -1, tail: e.message || String(e) };
  }
  // Cleared while running?
  if (!untilGoal || untilGoal.command !== command) {
    untilBusy = false;
    broadcastUntil();
    return;
  }
  untilGoal.tries += 1;
  untilGoal.lastCode = result.code;
  untilGoal.lastTail = result.tail;
  if (result.passed) {
    untilGoal.status = "met";
    broadcastUntil();
    log(`until met: ${command} (after ${untilGoal.tries} checks, source=${source})`);
    untilGoal = null;
    untilBusy = false;
    return;
  }
  if (untilGoal.tries >= untilGoal.max) {
    untilGoal.status = "gave-up";
    broadcastUntil();
    log(`until gave-up: ${command} after ${untilGoal.max} checks`);
    untilGoal = null;
    untilBusy = false;
    return;
  }
  untilGoal.status = "working";
  broadcastUntil();
  const output = result.tail === ""
    ? "命令没有输出。"
    : `输出末尾：\n\n\`\`\`\n${result.tail}\n\`\`\``;
  const msg = `${UNTIL_PREFIX}\`${command}\` 仍未通过（exit ${result.code}，第 ${untilGoal.tries}/${untilGoal.max} 次检查）。${output}\n\n请继续修改直到该命令通过。`;
  const sent = bridgeOwnedPrompt(msg);
  if (!sent) log("until: could not send follow-up prompt (Pi unavailable)");
  untilBusy = false;
}

// ---------- Scheduled messages (/schedule): persist JSON, never double-send ----------
const SCHEDULE_FILE = path.join(os.homedir(), ".pi/agent/pi-android.schedules.json");
const SCHEDULED_PREFIX = "[scheduled] ";
const MAX_SCHEDULES = 50;
const MAX_SCHEDULE_TEXT = 4000;
const DEFAULT_ZONE = (() => {
  try { return Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC"; }
  catch { return "UTC"; }
})();
/** @type {Record<string, object>} */
let scheduleItems = {};
/** @type {ReturnType<typeof setTimeout> | null} */
let scheduleTimer = null;

function loadSchedules() {
  try {
    const raw = JSON.parse(fs.readFileSync(SCHEDULE_FILE, "utf8"));
    const items = raw && typeof raw === "object" ? (raw.items || raw) : {};
    scheduleItems = {};
    for (const [id, item] of Object.entries(items)) {
      if (!item || typeof item !== "object") continue;
      scheduleItems[id] = item;
      // Crash mid-send: treat as already sent (never double-send).
      if (item.phase === "sending") {
        finishScheduleSend(item);
      }
    }
  } catch {
    scheduleItems = {};
  }
  persistSchedules();
  armScheduleTimer();
}

function persistSchedules() {
  try {
    fs.mkdirSync(path.dirname(SCHEDULE_FILE), { recursive: true });
    const tmp = SCHEDULE_FILE + ".tmp";
    fs.writeFileSync(tmp, JSON.stringify({ items: scheduleItems }, null, 2));
    fs.renameSync(tmp, SCHEDULE_FILE);
  } catch (e) {
    log(`schedule persist failed: ${e.message}`);
  }
}

function listSchedulesPublic() {
  const now = Date.now();
  return Object.values(scheduleItems)
    .filter((s) => s && s.phase !== "sending")
    .sort((a, b) => (a.next || 0) - (b.next || 0))
    .map((s) => ({
      id: s.id,
      text: s.text,
      next: s.next,
      nextLabel: describeMoment(s.next, s.zone || DEFAULT_ZONE, now),
      every: s.every || null,
      everyLabel: s.every ? describeRepeat(s.every) : null,
      zone: s.zone || DEFAULT_ZONE,
      runs: s.runs || 0,
    }));
}

function broadcastSchedules() {
  dispatch({ type: "schedule_update", schedules: listSchedulesPublic() });
}

function armScheduleTimer() {
  if (scheduleTimer) {
    clearTimeout(scheduleTimer);
    scheduleTimer = null;
  }
  const now = Date.now();
  let soonest = Infinity;
  for (const s of Object.values(scheduleItems)) {
    if (!s || s.phase === "sending") continue;
    if (typeof s.next === "number" && s.next < soonest) soonest = s.next;
  }
  if (!Number.isFinite(soonest)) return;
  const delay = Math.max(0, Math.min(soonest - now, 60_000));
  scheduleTimer = setTimeout(() => {
    scheduleTimer = null;
    tickSchedules().catch((e) => log(`schedule tick: ${e.message}`));
  }, delay);
  scheduleTimer.unref?.();
}

function finishScheduleSend(item) {
  const id = item.id;
  item.runs = (item.runs || 0) + 1;
  delete item.phase;
  delete item.sendKey;
  if (!item.every) {
    delete scheduleItems[id];
    return;
  }
  try {
    item.next = nextRepeat(item.every, Date.now(), item.zone || DEFAULT_ZONE);
    scheduleItems[id] = item;
  } catch (e) {
    log(`schedule nextRepeat failed: ${e.message}`);
    delete scheduleItems[id];
  }
}

async function tickSchedules() {
  const now = Date.now();
  const due = Object.values(scheduleItems)
    .filter((s) => s && s.phase !== "sending" && typeof s.next === "number" && s.next <= now)
    .sort((a, b) => a.next - b.next);
  for (const item of due) {
    await fireSchedule(item);
  }
  persistSchedules();
  broadcastSchedules();
  armScheduleTimer();
}

async function fireSchedule(item) {
  const sendKey = `${item.id}-${item.runs || 0}`;
  // Persist sending phase BEFORE prompt — restart will skip re-send.
  item.phase = "sending";
  item.sendKey = sendKey;
  scheduleItems[item.id] = item;
  persistSchedules();
  dispatch({
    type: "schedule_fired",
    id: item.id,
    text: item.text,
    sendKey,
  });
  const sent = bridgeOwnedPrompt(SCHEDULED_PREFIX + item.text);
  if (!sent) log(`schedule ${item.id}: Pi unavailable; marked sent to avoid double-fire`);
  finishScheduleSend(item);
}

function addSchedule(whenText, zone) {
  const z = (zone && knownZone(zone)) ? zone : DEFAULT_ZONE;
  const raw = String(whenText || "").trim();
  if (!raw) throw new Error(WHEN_HELP);
  const parsed = parseWhen(raw, Date.now(), z);
  const text = (parsed.rest || "").trim();
  if (!text) throw new Error("请在时间后写上要发送的内容");
  if (text.length > MAX_SCHEDULE_TEXT) throw new Error(`正文最多 ${MAX_SCHEDULE_TEXT} 字`);
  if (Object.keys(scheduleItems).length >= MAX_SCHEDULES) {
    throw new Error(`最多 ${MAX_SCHEDULES} 条定时消息`);
  }
  const id = `s${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
  const item = {
    id,
    text,
    whenRaw: raw,
    next: parsed.next,
    every: parsed.every || null,
    zone: z,
    runs: 0,
  };
  scheduleItems[id] = item;
  persistSchedules();
  broadcastSchedules();
  armScheduleTimer();
  return listSchedulesPublic().find((s) => s.id === id) || item;
}

function cancelSchedule(id) {
  const key = String(id || "");
  if (!key || !scheduleItems[key]) return { cancelled: false, schedules: listSchedulesPublic() };
  delete scheduleItems[key];
  persistSchedules();
  broadcastSchedules();
  armScheduleTimer();
  return { cancelled: true, schedules: listSchedulesPublic() };
}

loadSchedules();

// ---------- Plan mode: soft enforcement via injected instruction (Pi RPC has no tool allow-list) ----------
const PLANNING_INSTRUCTION =
  "【计划模式已开启】请只阅读与探索，提出计划，暂时不要做任何修改。"
  + "可用只读命令（如 ls/cat/grep/find、git status/log/diff/show），不要写文件、编辑、跑会改状态的命令或启动子代理。"
  + "回答末尾用简短编号列表给出计划。"
  + "\n\n（说明：连接器无法在 Pi RPC 层强制限制工具；本段指令为软约束。真正的安全边界需依赖 Lancet Guard 等防护。）\n\n";
const APPROVE_PLAN_PROMPT =
  "计划已批准。请按你提出的计划开始执行；计划模式已关闭，可以修改文件与运行命令。";
let planModeOn = false;

function planSnapshot() {
  return { type: "plan_update", on: planModeOn };
}

function broadcastPlan() {
  dispatch(planSnapshot());
}

function setPlanMode(on) {
  planModeOn = !!on;
  broadcastPlan();
  return planSnapshot();
}

function approvePlan() {
  planModeOn = false;
  broadcastPlan();
  const sent = bridgeOwnedPrompt(APPROVE_PLAN_PROMPT);
  return { ...planSnapshot(), prompted: sent };
}

/** Prepend plan-mode + per-session instructions to user-facing agent messages. */
function applyPlanToParams(command, params) {
  if (command !== "prompt" && command !== "steer" && command !== "follow_up") return params;
  const message = params && typeof params.message === "string" ? params.message : "";
  let prefix = "";
  if (planModeOn && !message.startsWith(PLANNING_INSTRUCTION) && !message.startsWith("【计划模式")) {
    prefix += PLANNING_INSTRUCTION;
  }
  const instr = getSessionInstructions();
  if (instr) {
    const block = `【本会话说明】\n${instr}\n\n`;
    if (!message.includes("【本会话说明】") && !prefix.includes("【本会话说明】")) {
      prefix += block;
    }
  }
  if (!prefix) return params;
  return { ...params, message: prefix + message };
}

// ---------- Per-session instructions (/instructions) ----------
const INSTRUCTIONS_FILE = path.join(os.homedir(), ".pi/agent/pi-android.instructions.json");
const MAX_INSTRUCTIONS = 8000;
/** @type {Record<string, string>} */
let instructionsBySession = {};
/** Current session key: session file path, or "default". */
let currentSessionKey = "default";

function loadInstructions() {
  try {
    const raw = JSON.parse(fs.readFileSync(INSTRUCTIONS_FILE, "utf8"));
    const map = raw && typeof raw === "object" ? (raw.bySession || raw) : {};
    instructionsBySession = {};
    for (const [k, v] of Object.entries(map)) {
      if (typeof v === "string" && v.trim()) instructionsBySession[k] = v.trim();
    }
  } catch {
    instructionsBySession = {};
  }
}

function persistInstructions() {
  try {
    fs.mkdirSync(path.dirname(INSTRUCTIONS_FILE), { recursive: true });
    const tmp = INSTRUCTIONS_FILE + ".tmp";
    fs.writeFileSync(tmp, JSON.stringify({ bySession: instructionsBySession }, null, 2));
    fs.renameSync(tmp, INSTRUCTIONS_FILE);
  } catch (e) {
    log(`instructions persist failed: ${e.message}`);
  }
}

function sessionKeyFromPath(sessionPath) {
  if (typeof sessionPath !== "string" || !sessionPath.trim()) return "default";
  try {
    return path.resolve(sessionPath);
  } catch {
    return sessionPath.trim();
  }
}

function getSessionInstructions() {
  const t = instructionsBySession[currentSessionKey];
  return typeof t === "string" ? t : "";
}

function setSessionInstructions(text) {
  const v = text == null ? "" : String(text).trim();
  if (v.length > MAX_INSTRUCTIONS) {
    throw new Error(`说明最多 ${MAX_INSTRUCTIONS} 字`);
  }
  if (!v) delete instructionsBySession[currentSessionKey];
  else instructionsBySession[currentSessionKey] = v;
  persistInstructions();
  const snap = instructionsSnapshot();
  dispatch({ type: "instructions_update", ...snap });
  return snap;
}

function instructionsSnapshot() {
  return {
    sessionKey: currentSessionKey,
    text: getSessionInstructions(),
  };
}

loadInstructions();

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

// Non-loopback binds are only safe with a non-empty token (already enforced above).
const isLoopbackHost = HOST === "127.0.0.1" || HOST === "localhost" || HOST === "::1"
  || HOST.startsWith("127.");
if (!isLoopbackHost) {
  console.error(
    `[pi-rpc-bridge] WARNING: listening on non-loopback ${HOST}:${PORT} — `
    + "token auth is mandatory; prefer --tailscale (bind CGNAT only) over 0.0.0.0"
  );
  if (HOST === "0.0.0.0" || HOST === "::") {
    console.error(
      "[pi-rpc-bridge] refused: binding 0.0.0.0/:: exposes the connector on all interfaces; "
      + "use --tailscale or --host <tailscale-ip>"
    );
    process.exit(2);
  }
}

// 延迟创建上传目录：未鉴权客户端不能在磁盘创建文件。
const uploads = new AttachmentStore();
const UPLOAD_COMMANDS = new Set(["upload_begin", "upload_chunk", "upload_finish", "upload_abort"]);
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
    cwd: resolveProjectCwd(),
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
      clearTimeout(entry.timer);
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
    clearTimeout(entry.timer);
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
      // New/switched session: drop Done-when so checks never follow the wrong cwd/session.
      if (untilGoal) clearUntilGoal();
      if (planModeOn) setPlanMode(false);
      if (entry.command === "new_session") {
        currentSessionKey = "default";
      } else if (entry.pendingSessionPath) {
        currentSessionKey = sessionKeyFromPath(entry.pendingSessionPath);
      }
      dispatch({ type: "instructions_update", ...instructionsSnapshot() });
    }
    if (entry.onResponse) {
      entry.onResponse(rec);
      return;
    }
    let data = rec.data;
    if (rec.success && entry.command === "get_session_name") {
      data = { name: currentSessionName };
    } else if (rec.success && entry.piCommand === "get_state" && entry.command === "get_state") {
      // Inject bridge cwd — Pi get_state does not document a cwd field / set_cwd RPC.
      data = {
        ...(rec.data && typeof rec.data === "object" ? rec.data : {}),
        cwd: resolveProjectCwd(),
        until: untilSnapshot(),
        plan: planSnapshot(),
        instructions: instructionsSnapshot(),
      };
    }
    respond(entry.ws, entry.clientId, !!rec.success, entry.command, data, rec.error);
    return;
  }
  dispatch(rec);
  // Done-when: after Pi settles (not aborted), run the check in session cwd.
  if (rec.type === "agent_settled" && !rec.aborted) {
    maybeRunUntilCheck("agent_settled").catch((e) => log(`until check error: ${e.message}`));
  }
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

const SKIP_DIR = new Set([
  ".git", "node_modules", ".pi", "__pycache__", ".gradle", "build", "dist",
  ".idea", ".vscode", "target", "vendor", ".next", ".cache",
]);

function resolveProjectCwd() {
  if (sessionCwd && fs.existsSync(sessionCwd) && fs.statSync(sessionCwd).isDirectory()) {
    return sessionCwd;
  }
  return path.resolve(DEFAULT_CWD || ".");
}

function persistSessionCwd(dir) {
  try {
    fs.mkdirSync(path.dirname(CWD_FILE), { recursive: true });
    fs.writeFileSync(CWD_FILE, dir + "\n", { mode: 0o600 });
  } catch { /* best-effort */ }
}

/** Change bridge+Pi working directory; restarts the Pi child so tools use the new cwd. */
function setSessionCwd(raw) {
  const full = path.resolve(String(raw || "").trim() || ".");
  if (!fs.existsSync(full) || !fs.statSync(full).isDirectory()) {
    throw new Error("目录不存在");
  }
  sessionCwd = full;
  persistSessionCwd(full);
  // Drop in-flight RPCs; Pi process cwd is fixed at spawn time.
  for (const [id, entry] of [...pendingReqs.entries()]) {
    if (entry.timer) clearTimeout(entry.timer);
    pendingReqs.delete(id);
    try {
      respond(entry.ws, entry.clientId, false, entry.command, null, "工作目录已切换，请重试");
    } catch { /* ignore */ }
  }
  const pi = child;
  child = null;
  if (pi) {
    try { pi.stdin.end(); } catch { /* ignore */ }
    try { pi.kill("SIGTERM"); } catch { /* ignore */ }
  }
  ensurePi();
  return full;
}

function listCwdEntries(dirPath) {
  const root = dirPath ? path.resolve(dirPath) : resolveProjectCwd();
  if (!fs.existsSync(root) || !fs.statSync(root).isDirectory()) {
    throw new Error("目录不存在");
  }
  const entries = [];
  const parent = path.dirname(root);
  if (parent && parent !== root) {
    entries.push({ name: "..", path: parent, kind: "dir" });
  }
  let names = [];
  try { names = fs.readdirSync(root, { withFileTypes: true }); } catch (e) {
    throw new Error(e.message);
  }
  const dirs = [];
  for (const e of names) {
    if (!e.isDirectory()) continue;
    if (e.name.startsWith(".") && e.name !== ".github") continue;
    dirs.push({ name: e.name, path: path.join(root, e.name), kind: "dir" });
  }
  dirs.sort((a, b) => a.name.localeCompare(b.name));
  return { cwd: root, entries: entries.concat(dirs).slice(0, 200) };
}

/** Read mcpServers map from a Pi mcp.json; never exposes secrets (env/headers/auth). */
function readMcpServersFile(file) {
  try {
    if (!fs.existsSync(file)) return {};
    const raw = JSON.parse(fs.readFileSync(file, "utf8"));
    const map = raw && typeof raw === "object" ? (raw.mcpServers || raw) : null;
    if (!map || typeof map !== "object" || Array.isArray(map)) return {};
    return map;
  } catch {
    return {};
  }
}

function mcpTransportOf(cfg) {
  if (!cfg || typeof cfg !== "object") return "unknown";
  const t = String(cfg.type || "").toLowerCase();
  if (t === "http" || t === "streamable-http") return "http";
  if (t === "stdio") return "stdio";
  if (cfg.url) return "http";
  if (cfg.command) return "stdio";
  return "unknown";
}

/**
 * Project trust from ~/.pi/agent/trust.json (canonical path → decision).
 * Walks cwd and parents; closest entry wins. null = no saved decision.
 */
function resolveProjectTrust(cwd) {
  const trustFile = path.join(os.homedir(), ".pi/agent/trust.json");
  let data = {};
  try {
    if (fs.existsSync(trustFile)) {
      data = JSON.parse(fs.readFileSync(trustFile, "utf8")) || {};
    }
  } catch { data = {}; }
  let cur = path.resolve(cwd || ".");
  while (true) {
    if (Object.prototype.hasOwnProperty.call(data, cur)) {
      const v = data[cur];
      if (v === null || v === undefined) {
        /* skip null tombstone — keep walking */
      } else {
        const decision = typeof v === "string" ? v : (v && v.decision) || v;
        const trusted = decision === true || decision === "trusted" || decision === "yes"
          || decision === "allow" || decision === "always";
        const untrusted = decision === false || decision === "untrusted" || decision === "no"
          || decision === "deny" || decision === "never";
        return {
          trusted: trusted ? true : (untrusted ? false : null),
          decision: trusted ? "trusted" : (untrusted ? "untrusted" : String(decision)),
          path: cur,
        };
      }
    }
    const parent = path.dirname(cur);
    if (parent === cur) break;
    cur = parent;
  }
  return { trusted: null, decision: null, path: path.resolve(cwd || ".") };
}

/**
 * Pocket MCP sheet data: config from user+project mcp.json + trust.json.
 * Live connected/failed when `pi mcp list` succeeds within timeout; else configured/disabled.
 * Never returns env/headers/auth secrets.
 */
function listMcpServers() {
  const cwd = resolveProjectCwd();
  const trust = resolveProjectTrust(cwd);
  const userFile = path.join(os.homedir(), ".pi/agent/mcp.json");
  const projectFile = path.join(cwd, ".pi/mcp.json");
  const userMap = readMcpServersFile(userFile);
  // Project mcp.json is only loaded by Pi when trusted; still list file presence for UI,
  // but mark projectTrusted so the client can explain.
  const projectMap = readMcpServersFile(projectFile);
  const names = new Set([...Object.keys(userMap), ...Object.keys(projectMap)]);
  const live = tryPiMcpListStatus(cwd);
  const servers = [];
  for (const name of [...names].sort((a, b) => a.localeCompare(b))) {
    const fromProject = Object.prototype.hasOwnProperty.call(projectMap, name);
    const cfg = fromProject ? projectMap[name] : userMap[name];
    if (!cfg || typeof cfg !== "object") continue;
    const enabled = cfg.enabled !== false;
    let status = enabled ? "configured" : "disabled";
    let detail = "";
    if (live && live[name]) {
      status = live[name].status || status;
      detail = live[name].detail || "";
    }
    servers.push({
      name,
      description: typeof cfg.description === "string" ? cfg.description : "",
      enabled,
      status,
      detail,
      source: fromProject ? "project" : "user",
      transport: mcpTransportOf(cfg),
      exposure: typeof cfg.exposure === "string" ? cfg.exposure : "",
    });
  }
  return {
    servers,
    cwd,
    projectTrusted: trust.trusted,
    trustDecision: trust.decision,
    projectMcpPath: projectFile,
    userMcpPath: userFile,
    live: !!live,
  };
}

/** Best-effort parse of `pi mcp list` text; null when CLI unavailable/times out. */
function tryPiMcpListStatus(cwd) {
  try {
    const r = spawnSync(PI_BIN, ["mcp", "list"], {
      cwd,
      encoding: "utf8",
      timeout: 10_000,
      env: process.env,
    });
    const out = `${r.stdout || ""}\n${r.stderr || ""}`;
    if (!out.trim()) return null;
    const map = {};
    for (const line of out.split(/\r?\n/)) {
      const m = line.match(/^\s*([A-Za-z0-9_-]+)\s+(connected|failed|error|disabled|idle|connecting|not-connected|cached|needs-auth)\b(.*)$/i);
      if (!m) continue;
      const status = m[2].toLowerCase().replace("error", "failed").replace("not-connected", "disconnected");
      map[m[1]] = { status, detail: (m[3] || "").trim() };
    }
    return Object.keys(map).length ? map : null;
  } catch {
    return null;
  }
}

/** Safe relative path under project cwd; rejects .. escape. */
function underProject(rel) {
  const root = resolveProjectCwd();
  const full = path.resolve(root, rel || ".");
  if (full !== root && !full.startsWith(root + path.sep)) {
    throw new Error("路径越界");
  }
  return full;
}

/**
 * Pocket @-mention: shallow-ish walk of project files (cap by count, skip bulky dirs).
 * query filters basename/path substring (case-insensitive).
 */
function listProjectFiles(query, limit) {
  const root = resolveProjectCwd();
  const out = [];
  const walk = (dir, depth) => {
    if (out.length >= limit || depth > 8) return;
    let entries = [];
    try {
      entries = fs.readdirSync(dir, { withFileTypes: true });
    } catch {
      return;
    }
    for (const e of entries) {
      if (out.length >= limit) return;
      if (e.name.startsWith(".") && e.name !== ".env.example") {
        if (e.isDirectory() && SKIP_DIR.has(e.name)) continue;
        if (e.isDirectory() && e.name !== ".github") continue;
      }
      if (e.isDirectory()) {
        if (SKIP_DIR.has(e.name)) continue;
        walk(path.join(dir, e.name), depth + 1);
        continue;
      }
      if (!e.isFile()) continue;
      const full = path.join(dir, e.name);
      const rel = path.relative(root, full);
      if (query && !rel.toLowerCase().includes(query) && !e.name.toLowerCase().includes(query)) {
        continue;
      }
      let bytes = 0;
      try { bytes = fs.statSync(full).size; } catch { /* skip */ }
      out.push({ path: rel.replace(/\\/g, "/"), name: e.name, bytes });
    }
  };
  walk(root, 0);
  out.sort((a, b) => a.path.localeCompare(b.path));
  return out.slice(0, limit);
}

/** Fixed-argv git helpers for Files / Changes / branch chip. */
function runGitLocal(command, params) {
  const cwd = resolveProjectCwd();
  const run = (args) => {
    const r = spawnSync("git", args, {
      cwd, encoding: "utf8", timeout: 15000, maxBuffer: 2 * 1024 * 1024,
    });
    if (r.error) throw new Error(r.error.message);
    return {
      stdout: (r.stdout || "").toString(),
      stderr: (r.stderr || "").toString(),
      status: r.status == null ? 1 : r.status,
    };
  };
  if (command === "git_status") {
    const r = run(["status", "--porcelain=v1", "-uall", "--no-optional-locks"]);
    if (r.status !== 0 && !(r.stdout || "").trim()) {
      throw new Error((r.stderr || "git status failed").trim());
    }
    const files = [];
    for (const line of (r.stdout || "").split("\n")) {
      if (!line || line.length < 4) continue;
      const code = line.slice(0, 2);
      let p = line.slice(3);
      // rename: "R  old -> new"
      const arrow = p.indexOf(" -> ");
      if (arrow >= 0) p = p.slice(arrow + 4);
      files.push({ path: p, status: code.trim() || code });
    }
    const branch = run(["rev-parse", "--abbrev-ref", "HEAD"]);
    return {
      files,
      branch: (branch.stdout || "").trim() || null,
      cwd,
    };
  }
  if (command === "git_branches") {
    const r = run(["branch", "--list", "--no-color"]);
    if (r.status !== 0) throw new Error((r.stderr || "git branch failed").trim());
    const branches = [];
    let current = "";
    for (const line of (r.stdout || "").split("\n")) {
      const s = line.trim();
      if (!s) continue;
      if (s.startsWith("* ")) {
        current = s.slice(2).trim();
        branches.push(current);
      } else {
        branches.push(s.replace(/^\*\s*/, ""));
      }
    }
    return { branches, current, cwd };
  }
  if (command === "git_checkout") {
    const branch = String(params.branch || "").trim();
    if (!/^[A-Za-z0-9._/\-]+$/.test(branch)) throw new Error("非法分支名");
    const r = run(["checkout", branch]);
    if (r.status !== 0) throw new Error((r.stderr || r.stdout || "checkout failed").trim());
    return { branch, cwd };
  }
  if (command === "git_diff") {
    const file = String(params.path || "").trim();
    const args = ["diff", "--no-color", "--"];
    if (file) {
      underProject(file); // path escape check
      args.push(file);
    }
    const r = run(args);
    // untracked: fall back to /dev/null diff via show if needed — empty ok
    return { path: file || null, diff: r.stdout || "", cwd };
  }
  throw new Error("unknown git command");
}

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
  if (UPLOAD_COMMANDS.has(command)) {
    try {
      const method = command.slice("upload_".length);
      respond(ws, id, true, command, uploads[method](ws, params), null);
    } catch (e) {
      respond(ws, id, false, command, null, e.message);
    }
    return;
  }
  if (Object.hasOwn(params, "attachmentIds") && command !== "prompt") {
    respond(ws, id, false, command, null, "attachmentIds 仅用于 prompt");
    return;
  }
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
  // Pocket: working directory — no Pi set_cwd RPC; bridge-local + Pi respawn.
  // Pocket scheduled messages (/schedule).
  if (command === "list_schedules") {
    respond(ws, id, true, "list_schedules", { schedules: listSchedulesPublic() }, null);
    return;
  }
  if (command === "add_schedule") {
    try {
      const when = (params || {}).when || (params || {}).text || "";
      const added = addSchedule(when, (params || {}).zone);
      respond(ws, id, true, "add_schedule", { schedule: added, schedules: listSchedulesPublic() }, null);
    } catch (e) {
      respond(ws, id, false, "add_schedule", null, e.message);
    }
    return;
  }
  if (command === "cancel_schedule") {
    try {
      // Note: params.id is reserved by the wire protocol — use scheduleId.
      const sid = (params || {}).scheduleId || (params || {}).schedule_id;
      respond(ws, id, true, "cancel_schedule", cancelSchedule(sid), null);
    } catch (e) {
      respond(ws, id, false, "cancel_schedule", null, e.message);
    }
    return;
  }
  // Pocket per-session instructions.
  if (command === "get_instructions") {
    respond(ws, id, true, "get_instructions", instructionsSnapshot(), null);
    return;
  }
  if (command === "set_instructions") {
    try {
      respond(ws, id, true, "set_instructions",
        setSessionInstructions((params || {}).text ?? (params || {}).instructions ?? ""), null);
    } catch (e) {
      respond(ws, id, false, "set_instructions", null, e.message);
    }
    return;
  }
  // Pocket plan mode (soft: instruction injection only — no Pi RPC tool allow-list).
  if (command === "set_plan_mode") {
    const on = !!(params || {}).on;
    respond(ws, id, true, "set_plan_mode", setPlanMode(on), null);
    return;
  }
  if (command === "get_plan_mode") {
    respond(ws, id, true, "get_plan_mode", planSnapshot(), null);
    return;
  }
  if (command === "approve_plan") {
    respond(ws, id, true, "approve_plan", approvePlan(), null);
    return;
  }
  // Pocket Done-when (/until): bridge-local goal check after each agent_settled.
  if (command === "set_until") {
    try {
      respond(ws, id, true, "set_until", setUntilGoal((params || {}).command || (params || {}).cmd), null);
    } catch (e) {
      respond(ws, id, false, "set_until", null, e.message);
    }
    return;
  }
  if (command === "clear_until") {
    respond(ws, id, true, "clear_until", clearUntilGoal(), null);
    return;
  }
  if (command === "get_until") {
    respond(ws, id, true, "get_until", untilSnapshot(), null);
    return;
  }
  if (command === "get_version") {
    respond(ws, id, true, "get_version", {
      sha: CONNECTOR_SHA,
      date: CONNECTOR_DATE,
      file: typeof __filename !== "undefined" ? __filename : "pi-rpc-bridge.mjs",
    }, null);
    return;
  }
  if (command === "get_cwd") {
    respond(ws, id, true, "get_cwd", { cwd: resolveProjectCwd() }, null);
    return;
  }
  if (command === "set_cwd") {
    try {
      const cwd = setSessionCwd((params || {}).path || (params || {}).cwd);
      respond(ws, id, true, "set_cwd", { cwd }, null);
    } catch (e) {
      respond(ws, id, false, "set_cwd", null, e.message);
    }
    return;
  }
  if (command === "list_cwd_entries") {
    try {
      const data = listCwdEntries((params || {}).path);
      respond(ws, id, true, "list_cwd_entries", data, null);
    } catch (e) {
      respond(ws, id, false, "list_cwd_entries", null, e.message);
    }
    return;
  }
  // Pocket: MCP sheet — no Pi list_mcp RPC; bridge reads mcp.json + trust.json (+ optional pi mcp list).
  if (command === "list_mcp_servers") {
    try {
      respond(ws, id, true, "list_mcp_servers", listMcpServers(), null);
    } catch (e) {
      respond(ws, id, false, "list_mcp_servers", null, e.message);
    }
    return;
  }
  if (command === "get_project_trust") {
    try {
      const cwd = resolveProjectCwd();
      respond(ws, id, true, "get_project_trust", { cwd, ...resolveProjectTrust(cwd) }, null);
    } catch (e) {
      respond(ws, id, false, "get_project_trust", null, e.message);
    }
    return;
  }
  // Pocket: @ file mentions — bridge-local walk of session cwd (not a Pi RPC).
  if (command === "list_project_files") {
    try {
      const query = String((params || {}).query || "").trim().toLowerCase();
      const limit = Math.max(1, Math.min(200, Number((params || {}).limit) || 80));
      const files = listProjectFiles(query, limit);
      respond(ws, id, true, "list_project_files",
        { files, cwd: resolveProjectCwd(), query }, null);
    } catch (e) {
      respond(ws, id, false, "list_project_files", null, e.message);
    }
    return;
  }
  // Pocket: Files tile viewer — capped text read under project cwd (not a Pi RPC).
  if (command === "read_project_file") {
    try {
      const rel = String((params || {}).path || "").trim();
      if (!rel) throw new Error("缺少 path");
      const full = underProject(rel);
      const st = fs.statSync(full);
      if (!st.isFile()) throw new Error("不是文件");
      const max = Math.max(1024, Math.min(512 * 1024, Number((params || {}).maxBytes) || 200 * 1024));
      const buf = Buffer.alloc(Math.min(st.size, max + 1));
      const fd = fs.openSync(full, "r");
      let n = 0;
      try { n = fs.readSync(fd, buf, 0, buf.length, 0); } finally { fs.closeSync(fd); }
      const truncated = n > max || st.size > max;
      const slice = buf.subarray(0, Math.min(n, max));
      const wantBase64 = String((params || {}).encoding || "").toLowerCase() === "base64"
          || (params || {}).asImage === true;
      // Reject obvious binary (NUL) unless caller asked for base64 (inline images).
      if (!wantBase64 && slice.includes(0)) throw new Error("二进制文件，无法预览");
      if (wantBase64) {
        respond(ws, id, true, "read_project_file", {
          path: rel.replace(/\\/g, "/"),
          contentBase64: slice.toString("base64"),
          bytes: st.size,
          truncated,
          cwd: resolveProjectCwd(),
        }, null);
      } else {
        respond(ws, id, true, "read_project_file", {
          path: rel.replace(/\\/g, "/"),
          content: slice.toString("utf8"),
          bytes: st.size,
          truncated,
          cwd: resolveProjectCwd(),
        }, null);
      }
    } catch (e) {
      respond(ws, id, false, "read_project_file", null, e.message);
    }
    return;
  }
  // Pocket: Files / Changes / branch switcher — fixed-argv git only (no shell).
  if (command === "git_status" || command === "git_branches"
      || command === "git_checkout" || command === "git_diff") {
    try {
      const data = runGitLocal(command, params || {});
      respond(ws, id, true, command, data, null);
    } catch (e) {
      respond(ws, id, false, command, null, e.message);
    }
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
  if (command === "prompt" && (Object.hasOwn(params, "attachmentIds") || Object.hasOwn(params, "images"))) {
    try {
      const prepared = Object.hasOwn(params, "attachmentIds")
        ? prepareAttachments(uploads, params) : prepareInlineImages(uploads, params);
      if (prepared.imageItems.length || prepared.inlineImages?.length) {
        promptWithImages(id, ws, prepared);
        return;
      }
      params = prepared.params;
    } catch (e) {
      respond(ws, id, false, command, null, e.message);
      return;
    }
  }
  forwardCommand(id, ws, command, params);
}

/** 图片只经官方 images 通道；当前模型声明仅 text 时拒绝，不让 Pi 静默丢图。 */
function promptWithImages(id, ws, prepared) {
  const pi = ensurePi();
  if (!pi || pi.stdin.destroyed || pi.stdin.writableEnded) {
    respond(ws, id, false, "prompt", null, "Pi 正在退出，请稍后重试");
    return;
  }
  const internalId = `bridge-${++rpcSeq}`;
  const timer = setTimeout(() => {
    if (!pendingReqs.delete(internalId)) return;
    respond(ws, id, false, "prompt", null, "查询图片模型能力超时；未发送 prompt");
  }, 10000);
  timer.unref();
  pendingReqs.set(internalId, {ws, clientId: id, command: "prompt", piCommand: "get_state", timer,
    onResponse: (rec) => {
      if (!rec.success) {
        respond(ws, id, false, "prompt", null, "无法查询图片模型能力；未发送 prompt");
        return;
      }
      const input = rec.data?.model?.input;
      if (Array.isArray(input) && !input.includes("image")) {
        respond(ws, id, false, "prompt", null, "当前模型不支持图片，请切换支持图片的模型");
        return;
      }
      try {
        const images = prepared.inlineImages || prepared.imageItems.map(item => ({type: "image",
          data: uploads.readVerified(item, true), mimeType: item.attachment.mimeType}));
        forwardCommand(id, ws, "prompt", {...prepared.params, images});
      } catch (e) { respond(ws, id, false, "prompt", null, e.message); }
    },
  });
  try { pi.stdin.write(JSON.stringify({id: internalId, type: "get_state"}) + "\n"); }
  catch (e) {
    clearTimeout(timer); pendingReqs.delete(internalId);
    respond(ws, id, false, "prompt", null, e.message);
  }
}

function forwardCommand(id, ws, command, params) {
  const pi = ensurePi();
  if (!pi || pi.stdin.destroyed || pi.stdin.writableEnded) {
    respond(ws, id, false, command, null, "Pi 正在退出，请稍后重试");
    return;
  }
  params = applyPlanToParams(command, params || {});
  const internalId = `bridge-${++rpcSeq}`;
  // get_session_name 是兼容别名；名称的权威来源是官方 get_state，不是本地乐观写入。
  const piCommand = command === "get_session_name" ? "get_state" : command;
  const pending = { ws, clientId: id, command, piCommand };
  if (["switch_session", "fork", "clone"].includes(command)
      && params && typeof params.sessionPath === "string") {
    pending.pendingSessionPath = params.sessionPath;
  }
  const rec = { ...params, id: internalId, type: piCommand };
  pendingReqs.set(internalId, pending);
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
    uploads.disconnect(ws);
    for (const [id, entry] of pendingReqs) {
      if (entry.ws === ws) { clearTimeout(entry.timer); pendingReqs.delete(id); }
    }
    // 未鉴权连接不能阻止取消无人能回答的对话框。
    if (!clients.some((client) => client.authed)) releaseStuckDialogs("所有已鉴权客户端已断开");
    scheduleIdleExit();
  });
  ws.on("error", () => {});

  safeSend(ws, JSON.stringify({ v: 1, kind: "status", state: c.authed ? piState() : "offline" }));
});

process.on("exit", () => uploads.close());
process.on("SIGTERM", () => {
  uploads.close();
  stopPi("SIGTERM");
  wss.close(() => process.exit(0));
  setTimeout(() => process.exit(0), 3000);
});
process.on("SIGINT", () => {
  uploads.close();
  stopPi("SIGINT");
  wss.close(() => process.exit(0));
});

wss.on("listening", () => {
  log(`连接器 listening ws://${HOST}:${PORT}/ws  pi=${PI_BIN}  cwd=${resolveProjectCwd()}  bash=${ALLOW_BASH ? "on" : "off"}`);
});
