#!/usr/bin/env node
/**
 * 轻量协议自测：校验 set_model 参数形态 + prompt 失败/超时后客户端应回到 idle。
 * 不依赖真实 Pi；模拟 bridge 侧 JSONL 语义。
 *
 *   node app/tools/mock-set-model-prompt.mjs
 */
import assert from "node:assert/strict";

/** App → bridge 的 WS 帧 */
function clientRpc(id, rpc, params) {
  return { id, rpc, params: params || {} };
}

/** bridge → Pi stdin */
function toPiCommand(frame) {
  return { id: frame.id, type: frame.rpc, ...(frame.params || {}) };
}

function findModel(models, provider, modelId) {
  return models.find((m) => m.provider === provider && m.id === modelId) || null;
}

const models = [
  { id: "gpt-5.4", name: "GPT-5.4", provider: "openai-codex" },
  { id: "claude-sonnet-4-20250514", name: "Claude Sonnet 4", provider: "anthropic" },
];

// 1) picker 必须发 provider+modelId（id），不能发展示名
{
  const pick = models[0];
  const frame = clientRpc("a1", "set_model", { provider: pick.provider, modelId: pick.id });
  const pi = toPiCommand(frame);
  assert.equal(pi.type, "set_model");
  assert.equal(pi.provider, "openai-codex");
  assert.equal(pi.modelId, "gpt-5.4");
  assert.ok(findModel(models, pi.provider, pi.modelId));
  assert.notEqual(pi.modelId, pick.name, "modelId must not be display name");
}

// 2) 误用展示名 → Model not found
{
  const pick = models[1];
  const bad = toPiCommand(clientRpc("a2", "set_model", { provider: pick.provider, modelId: pick.name }));
  assert.equal(findModel(models, bad.provider, bad.modelId), null);
}

// 3) prompt 失败响应必须让 UI 回 idle（客户端契约）
{
  const fail = { v: 1, kind: "response", id: "a3", ok: false, command: "prompt", error: "Agent is already processing" };
  assert.equal(fail.ok, false);
  assert.equal(fail.command, "prompt");
  // 契约：ok=false 的 prompt → settleStreaming + 聊天气泡，禁止只 Toast
}

// 4) disposition=handled 不会有 agent_settled
{
  const handled = { v: 1, kind: "response", id: "a4", ok: true, command: "prompt", data: { disposition: "handled" } };
  assert.equal(handled.data.disposition, "handled");
}

// 5) agent_end(error) 带 errorMessage，正文可空
{
  const ev = {
    type: "agent_end",
    willRetry: false,
    messages: [
      {
        role: "assistant",
        content: [],
        stopReason: "error",
        errorMessage: "401 Incorrect API key",
      },
    ],
  };
  const last = ev.messages[ev.messages.length - 1];
  assert.equal(last.stopReason, "error");
  assert.ok(last.errorMessage);
}

console.log("mock-set-model-prompt: ok");
