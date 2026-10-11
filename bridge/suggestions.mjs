/**
 * Cheap heuristics for empty-state suggestion chips (no LLM).
 * Scans recent Pi session JSONL files + live until/schedules + git porcelain.
 */
import fs from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";

const DEFAULT_LIMIT = 12;
const TAIL_BYTES = 160 * 1024;

/**
 * @param {{
 *   cwd: string,
 *   sessionsDir: string,
 *   untilGoal?: null | { command: string, status: string },
 *   schedules?: Array<{ id: string, text: string, nextLabel?: string }>,
 *   limit?: number,
 * }} opts
 * @returns {{ suggestions: Array<object>, cwd: string, gitRepo: boolean }}
 */
export function buildSuggestions(opts) {
  const cwd = path.resolve(opts.cwd || ".");
  const sessionsDir = opts.sessionsDir;
  const limit = Math.max(3, Math.min(40, Number(opts.limit) || DEFAULT_LIMIT));
  const out = [];
  const seen = new Set();

  const push = (item) => {
    if (!item || !item.title || !item.prompt) return;
    const key = item.kind + "|" + item.title;
    if (seen.has(key)) return;
    seen.add(key);
    out.push({
      title: String(item.title).slice(0, 40),
      prompt: String(item.prompt).slice(0, 500),
      kind: item.kind || "generic",
      sessionPath: item.sessionPath || null,
    });
  };

  // Live bridge state first (most actionable).
  if (opts.untilGoal && opts.untilGoal.command
      && (opts.untilGoal.status === "working" || opts.untilGoal.status === "checking")) {
    const cmd = String(opts.untilGoal.command).trim();
    push({
      kind: "until",
      title: `继续 until：${short(cmd, 18)}`,
      prompt: `继续完成 /until 目标：\`${cmd}\`。先看上次失败原因，再修到通过。`,
    });
  }

  const schedules = Array.isArray(opts.schedules) ? opts.schedules : [];
  for (const s of schedules.slice(0, 2)) {
    if (!s || !s.text) continue;
    const when = s.nextLabel ? `（${s.nextLabel}）` : "";
    push({
      kind: "schedule",
      title: `定时：${short(s.text, 16)}${when ? "" : ""}`,
      prompt: `查看并确认定时消息${when}：${s.text}`,
    });
  }

  const git = inspectGit(cwd);
  if (git.repo && git.dirty > 0) {
    push({
      kind: "git",
      title: `处理 ${git.dirty} 个未提交改动`,
      prompt: "用 git status 和 git diff 看当前未提交改动，总结要点，并建议下一步（提交 / 继续改 / 丢弃）。不要直接 commit，除非我明确要求。",
    });
  }

  // Session file heuristics (newest first, prefer same cwd).
  const sessions = listRecentSessions(sessionsDir, limit);
  const sameCwd = sessions.filter((s) => samePath(s.cwd, cwd));
  const ordered = sameCwd.concat(sessions.filter((s) => !samePath(s.cwd, cwd)));

  for (const session of ordered) {
    if (out.length >= 3) break;
    let scan;
    try {
      scan = scanSessionTail(session.path);
    } catch {
      continue;
    }
    if (!scan) continue;

    if (scan.openTodos.length > 0) {
      const first = scan.openTodos[0];
      const n = scan.openTodos.length;
      push({
        kind: "todo",
        title: n > 1 ? `继续：${short(first, 14)}（还有 ${n - 1} 项）` : `继续：${short(first, 18)}`,
        prompt: `继续上次未完成的待办：\n- [ ] ${scan.openTodos.slice(0, 5).join("\n- [ ] ")}\n请从第一项接着做。`,
        sessionPath: session.path,
      });
    }

    if (scan.endedBad && scan.lastUser) {
      push({
        kind: "error",
        title: `继续：修复 ${short(scan.lastUser, 16)}`,
        prompt: `上次会话在处理「${scan.lastUser}」时以错误/中断结束。请接着排查并完成。`,
        sessionPath: session.path,
      });
    } else if (scan.pendingUser && scan.lastUser) {
      push({
        kind: "continue",
        title: `继续：${short(scan.lastUser, 18)}`,
        prompt: `继续完成我上次的请求：「${scan.lastUser}」`,
        sessionPath: session.path,
      });
    }
  }

  return {
    suggestions: out.slice(0, 3),
    cwd,
    gitRepo: !!git.repo,
  };
}

function short(s, n) {
  const t = String(s || "").replace(/\s+/g, " ").trim();
  if (t.length <= n) return t;
  return t.slice(0, Math.max(1, n - 1)) + "…";
}

function samePath(a, b) {
  if (!a || !b) return false;
  try {
    return path.resolve(a) === path.resolve(b);
  } catch {
    return false;
  }
}

function listRecentSessions(sessionsDir, limit) {
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
  walk(sessionsDir);
  const statted = [];
  for (const file of files) {
    try {
      const st = fs.statSync(file);
      statted.push({ path: file, mtimeMs: st.mtimeMs, bytes: st.size, cwd: null });
    } catch { /* skip */ }
  }
  statted.sort((a, b) => b.mtimeMs - a.mtimeMs);
  const top = statted.slice(0, limit);
  for (const entry of top) {
    entry.cwd = peekSessionCwd(entry.path, entry.bytes);
  }
  return top;
}

function peekSessionCwd(file, bytes) {
  try {
    const fh = fs.openSync(file, "r");
    const n = Math.min(bytes, 8192);
    const buf = Buffer.alloc(n);
    fs.readSync(fh, buf, 0, n, 0);
    fs.closeSync(fh);
    for (const line of buf.toString("utf8").split("\n")) {
      if (!line.trim()) continue;
      try {
        const rec = JSON.parse(line);
        if (rec.type === "session" && typeof rec.cwd === "string") return rec.cwd;
      } catch { /* continue */ }
    }
  } catch { /* ignore */ }
  return null;
}

/**
 * Scan the tail of a session JSONL for todos / abort / unfinished user turns.
 */
function scanSessionTail(filePath) {
  const st = fs.statSync(filePath);
  const fh = fs.openSync(filePath, "r");
  const len = Math.min(st.size, TAIL_BYTES);
  const buf = Buffer.alloc(len);
  fs.readSync(fh, buf, 0, len, Math.max(0, st.size - len));
  fs.closeSync(fh);
  let text = buf.toString("utf8");
  if (st.size > len) {
    const nl = text.indexOf("\n");
    if (nl >= 0) text = text.slice(nl + 1);
  }

  const openTodos = [];
  let lastUser = "";
  let pendingUser = false;
  let endedBad = false;
  let sawAssistantAfterUser = false;

  for (const line of text.split("\n")) {
    if (!line.trim()) continue;
    let rec;
    try {
      rec = JSON.parse(line);
    } catch {
      continue;
    }
    const typ = rec.type || "";

    if (typ === "agent_end" || typ === "agent_settled") {
      const aborted = !!(rec.aborted || rec.error || rec.stopReason === "error"
          || rec.stopReason === "aborted" || rec.stopReason === "interrupted"
          || rec.status === "error" || rec.status === "aborted");
      if (aborted) endedBad = true;
      else {
        endedBad = false;
        pendingUser = false;
      }
      continue;
    }

    const msg = rec.message || rec;
    const role = msg.role || rec.role;
    if (typ === "message" || role) {
      if (role === "user") {
        const t = textOf(msg.content || rec.content);
        if (t && !t.startsWith("/")) {
          lastUser = t;
          pendingUser = true;
          sawAssistantAfterUser = false;
          endedBad = false;
        }
      } else if (role === "assistant") {
        sawAssistantAfterUser = true;
        collectTodos(msg.content || rec.content, openTodos);
        // Also scan plain text for markdown checkboxes.
        const t = textOf(msg.content || rec.content);
        for (const m of t.matchAll(/^- \[ \] (.+)$/gm)) {
          const item = m[1].trim();
          if (item && !openTodos.includes(item)) openTodos.push(item);
        }
      } else if (role === "toolResult" || role === "tool") {
        collectTodosFromTool(msg, openTodos);
      }
    }

    // Pi todo tool custom events / toolCall results
    if (typ === "tool_result" || typ === "toolResult") {
      collectTodosFromTool(rec, openTodos);
    }
  }

  // If we saw a user turn and never got a clean agent_end after assistant work started,
  // treat as pending when the file ends without a successful settle.
  if (pendingUser && sawAssistantAfterUser && !endedBad) {
    // assistant replied but no agent_end in tail — still "continue" is useful
    pendingUser = true;
  }

  return {
    openTodos: openTodos.slice(0, 8),
    lastUser: lastUser.slice(0, 120),
    pendingUser,
    endedBad,
  };
}

function textOf(content) {
  if (typeof content === "string") return content;
  if (!Array.isArray(content)) return "";
  let out = "";
  for (const part of content) {
    if (!part || typeof part !== "object") continue;
    if (part.type === "text" && typeof part.text === "string") out += part.text + "\n";
  }
  return out;
}

function collectTodos(content, into) {
  if (!Array.isArray(content)) return;
  for (const part of content) {
    if (!part || typeof part !== "object") continue;
    if (part.type === "toolCall" || part.type === "toolUse" || part.type === "functionCall") {
      const name = String(part.name || part.toolName || "").toLowerCase();
      if (!name.includes("todo")) continue;
      const args = part.arguments || part.args || part.input || {};
      harvestTodoArgs(args, into);
    }
  }
}

function collectTodosFromTool(rec, into) {
  const name = String(rec.toolName || rec.name || "").toLowerCase();
  const content = rec.content ?? rec.result ?? rec.output ?? rec.message?.content;
  if (name.includes("todo")) {
    harvestTodoArgs(typeof content === "object" ? content : {}, into);
  }
  const text = typeof content === "string" ? content
    : (content && typeof content === "object" && typeof content.text === "string" ? content.text : "");
  if (text) {
    for (const m of text.matchAll(/^- \[ \] (.+)$/gm)) {
      const item = m[1].trim();
      if (item && !into.includes(item)) into.push(item);
    }
    // JSON blob with todos array
    try {
      const j = JSON.parse(text);
      harvestTodoArgs(j, into);
    } catch { /* ignore */ }
  }
}

function harvestTodoArgs(args, into) {
  if (!args || typeof args !== "object") return;
  const lists = [args.todos, args.items, args.tasks, args.open, args.pending];
  for (const list of lists) {
    if (!Array.isArray(list)) continue;
    for (const item of list) {
      if (typeof item === "string" && item.trim()) {
        if (!into.includes(item.trim())) into.push(item.trim());
      } else if (item && typeof item === "object") {
        const status = String(item.status || item.state || "").toLowerCase();
        const done = status === "done" || status === "completed" || item.done === true
            || item.checked === true;
        if (done) continue;
        const title = String(item.content || item.title || item.text || item.task || "").trim();
        if (title && !into.includes(title)) into.push(title);
      }
    }
  }
}

function inspectGit(cwd) {
  try {
    const top = spawnSync("git", ["rev-parse", "--show-toplevel"], {
      cwd, encoding: "utf8", timeout: 4000,
    });
    if (top.status !== 0) return { repo: false, dirty: 0 };
    const st = spawnSync("git", ["status", "--porcelain=v1", "-uall", "--no-optional-locks"], {
      cwd, encoding: "utf8", timeout: 8000,
    });
    const lines = (st.stdout || "").split("\n").filter((l) => l && l.length >= 2);
    return { repo: true, dirty: lines.length };
  } catch {
    return { repo: false, dirty: 0 };
  }
}
