import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { buildSuggestions } from "../suggestions.mjs";

function writeSession(dir, name, lines) {
  const file = path.join(dir, name);
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, lines.map((o) => JSON.stringify(o)).join("\n") + "\n");
  return file;
}

test("get_suggestions: todos + pending user + until + schedules", () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "pi-sug-"));
  const cwd = path.join(root, "proj");
  fs.mkdirSync(cwd);
  const sessions = path.join(root, "sessions");
  writeSession(sessions, "a.jsonl", [
    { type: "session", id: "1", cwd },
    { type: "message", message: { role: "user", content: [{ type: "text", text: "修 flaky 测试" }] } },
    {
      type: "message",
      message: {
        role: "assistant",
        content: [
          { type: "text", text: "计划：\n- [ ] 复现失败\n- [ ] 修断言\n- [x] 开分支\n" },
        ],
      },
    },
  ]);

  const data = buildSuggestions({
    cwd,
    sessionsDir: sessions,
    untilGoal: { command: "npm test", status: "working" },
    schedules: [{ id: "s1", text: "提醒提交 PR", nextLabel: "明天 9:00" }],
  });
  assert.equal(data.cwd, path.resolve(cwd));
  assert.ok(data.suggestions.length >= 1);
  assert.ok(data.suggestions.some((s) => s.kind === "until"));
  assert.ok(data.suggestions.some((s) => s.kind === "todo" || s.kind === "schedule"));
  assert.ok(data.suggestions.every((s) => s.title && s.prompt));
});

test("get_suggestions: aborted session yields continue/error chip", () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "pi-sug-"));
  const cwd = path.join(root, "proj");
  fs.mkdirSync(cwd);
  const sessions = path.join(root, "sessions");
  writeSession(sessions, "b.jsonl", [
    { type: "session", id: "2", cwd },
    { type: "message", message: { role: "user", content: "排查 OOM" } },
    { type: "message", message: { role: "assistant", content: "正在看…" } },
    { type: "agent_end", aborted: true, stopReason: "aborted" },
  ]);
  const data = buildSuggestions({ cwd, sessionsDir: sessions });
  assert.ok(data.suggestions.some((s) => s.kind === "error" || s.kind === "continue"));
});
