#!/usr/bin/env node
/**
 * Mocked RPC regression：set_model 必须带精确 provider+modelId，桥按 id 回传，
 * 成功后状态与 get_state 对齐；失败必须可见。不依赖真实 Pi。
 *
 *   node app/tools/mock-set-model-prompt.mjs
 */
import assert from "node:assert/strict";
import { EventEmitter } from "node:events";

// ---------- 镜像 App ModelRpc / bridge 行为 ----------

function setModelParams(provider, modelId) {
  if (provider == null || modelId == null) return null;
  const p = String(provider).trim();
  const id = String(modelId).trim();
  if (!p || !id) return null;
  if (id.includes(" / ") || p.includes(" / ")) return null;
  return { provider: p, modelId: id };
}

function parseAvailableModels(data) {
  const models = (data && (data.models || data.availableModels)) || [];
  const labels = [];
  const refs = [];
  for (const m of models) {
    if (!m) continue;
    const provider = String(m.provider || m.providerName || "").trim();
    const modelId = String(m.id || m.modelId || "").trim();
    if (!provider || !modelId) continue;
    const label = `${provider} / ${modelId}`;
    if (labels.includes(label)) continue;
    labels.push(label);
    refs.push([provider, modelId]);
  }
  return { labels, refs };
}

function fromModelObject(model) {
  if (!model) return null;
  const provider = String(model.provider || "").trim();
  const modelId = String(model.id || model.modelId || "").trim();
  const name = String(model.name || "").trim();
  if (!provider && !modelId && !name) return null;
  let label;
  if (name) label = name.includes(" / ") || !provider ? name : `${provider} / ${name}`;
  else if (provider && modelId) label = `${provider} / ${modelId}`;
  else label = modelId;
  return { provider, modelId, label };
}

/** App → bridge WS 帧 */
function clientRpc(id, rpc, params) {
  return { id, rpc, params: params || {} };
}

/** bridge → Pi stdin（与 pi-rpc-bridge writeCommand 一致） */
function toPiCommand(frame) {
  return { id: frame.id, type: frame.rpc, ...(frame.params || {}) };
}

/** 迷你 bridge：按 id 匹配响应（回归：禁止仅 FIFO by command） */
class MockBridge extends EventEmitter {
  constructor(pi) {
    super();
    this.pi = pi;
    this.pending = new Map();
    this.seq = 0;
  }

  send(frame) {
    const id = frame.id || `a${++this.seq}`;
    const command = frame.rpc;
    const params = frame.params || {};
    this.pending.set(id, { command });
    const rec = { id, type: command, ...params };
    this.pi.handle(rec, (response) => this._onPiResponse(response));
    return id;
  }

  _onPiResponse(rec) {
    let id = typeof rec.id === "string" ? rec.id : null;
    let entry = id ? this.pending.get(id) : null;
    if (!entry) {
      for (const [k, v] of this.pending) {
        if (v.command === rec.command) {
          id = k;
          entry = v;
          break;
        }
      }
    }
    assert.ok(entry && id, `unmatched response command=${rec.command} id=${rec.id}`);
    this.pending.delete(id);
    this.emit("response", {
      v: 1,
      kind: "response",
      id,
      ok: !!rec.success,
      command: rec.command || entry.command,
      data: rec.data ?? null,
      error: rec.error ?? null,
    });
  }
}

/** 迷你 Pi：内存里持有 current model */
class MockPi {
  constructor(models) {
    this.models = models;
    this.current = models[0];
    this.thinkingLevel = "medium";
  }

  handle(rec, reply) {
    if (rec.type === "get_available_models") {
      reply({ id: rec.id, type: "response", command: "get_available_models", success: true, data: { models: this.models } });
      return;
    }
    if (rec.type === "get_state") {
      reply({
        id: rec.id,
        type: "response",
        command: "get_state",
        success: true,
        data: { model: this.current, thinkingLevel: this.thinkingLevel },
      });
      return;
    }
    if (rec.type === "set_model") {
      const found = this.models.find((m) => m.provider === rec.provider && m.id === rec.modelId);
      if (!found) {
        reply({
          id: rec.id,
          type: "response",
          command: "set_model",
          success: false,
          error: `Model not found: ${rec.provider}/${rec.modelId}`,
        });
        return;
      }
      this.current = found;
      reply({ id: rec.id, type: "response", command: "set_model", success: true, data: found });
      return;
    }
    if (rec.type === "prompt") {
      if (this.current.id === "broken") {
        reply({ id: rec.id, type: "response", command: "prompt", success: false, error: "provider auth failed" });
        return;
      }
      reply({ id: rec.id, type: "response", command: "prompt", success: true, data: { disposition: "started" } });
      return;
    }
    reply({ id: rec.id, type: "response", command: rec.type, success: false, error: "unknown" });
  }
}

// ---------- 用例 ----------

const catalog = [
  { id: "gpt-5.4", name: "GPT-5.4", provider: "openai-codex" },
  { id: "claude-sonnet-4-20250514", name: "Claude Sonnet 4", provider: "anthropic" },
  { id: "broken", name: "Broken", provider: "test" },
];

// 1) 解析列表：refs 用 id 不是 name
{
  const { labels, refs } = parseAvailableModels({ models: catalog });
  assert.equal(refs.length, 3);
  assert.deepEqual(refs[0], ["openai-codex", "gpt-5.4"]);
  assert.equal(labels[1], "anthropic / claude-sonnet-4-20250514");
  assert.notEqual(refs[1][1], catalog[1].name);
}

// 2) setModelParams 拒绝展示串 / 空值
{
  assert.equal(setModelParams("anthropic", "Claude Sonnet 4")?.modelId, "Claude Sonnet 4"); // 字面合法但 Pi 会 not found
  assert.equal(setModelParams("anthropic / x", "y"), null);
  assert.equal(setModelParams("anthropic", "a / b"), null);
  assert.equal(setModelParams("", "gpt"), null);
  const ok = setModelParams("openai-codex", "gpt-5.4");
  assert.deepEqual(ok, { provider: "openai-codex", modelId: "gpt-5.4" });
}

// 3) 端到端：picker refs → set_model → 状态芯片对齐 get_state
{
  const pi = new MockPi(catalog.slice(0, 2));
  const bridge = new MockBridge(pi);
  const ui = { provider: "", modelId: "", label: "", lastError: null, streaming: false };

  bridge.on("response", (resp) => {
    if (!resp.ok) {
      ui.lastError = resp.error;
      if (resp.command === "prompt" || resp.command === "set_model") ui.streaming = false;
      return;
    }
    if (resp.command === "set_model") {
      const applied = fromModelObject(resp.data);
      assert.ok(applied);
      ui.provider = applied.provider;
      ui.modelId = applied.modelId;
      ui.label = applied.label;
      // 成功后必须再拉 get_state
      bridge.send(clientRpc("gs1", "get_state", {}));
      return;
    }
    if (resp.command === "get_state") {
      const applied = fromModelObject(resp.data.model);
      ui.provider = applied.provider;
      ui.modelId = applied.modelId;
      ui.label = applied.label;
    }
  });

  const { refs } = parseAvailableModels({ models: catalog.slice(0, 2) });
  const pick = refs[1]; // anthropic / claude...
  const params = setModelParams(pick[0], pick[1]);
  const frame = clientRpc("sm1", "set_model", params);
  const piCmd = toPiCommand(frame);
  assert.equal(piCmd.type, "set_model");
  assert.equal(piCmd.provider, "anthropic");
  assert.equal(piCmd.modelId, "claude-sonnet-4-20250514");

  bridge.send(frame);
  assert.equal(pi.current.id, "claude-sonnet-4-20250514");
  assert.equal(ui.provider, "anthropic");
  assert.equal(ui.modelId, "claude-sonnet-4-20250514");
  assert.match(ui.label, /claude-sonnet-4-20250514|Claude Sonnet 4/);
  assert.equal(ui.lastError, null);
}

// 4) 并发 get_state + set_model：按 id 匹配，最终状态为新模型
{
  const pi = new MockPi(catalog.slice(0, 2));
  const bridge = new MockBridge(pi);
  const seen = [];
  bridge.on("response", (r) => seen.push(r));

  // 先发 get_state（旧模型），再 set_model，再 get_state —— 用 id 对齐
  const idGs0 = bridge.send(clientRpc("gs0", "get_state", {}));
  const params = setModelParams("anthropic", "claude-sonnet-4-20250514");
  const idSm = bridge.send(clientRpc("sm2", "set_model", params));
  const idGs1 = bridge.send(clientRpc("gs2", "get_state", {}));

  assert.equal(idGs0, "gs0");
  assert.equal(idSm, "sm2");
  const smResp = seen.find((r) => r.id === "sm2");
  assert.ok(smResp && smResp.ok);
  const gs1 = seen.find((r) => r.id === "gs2");
  assert.equal(gs1.data.model.id, "claude-sonnet-4-20250514");
}

// 5) 误用展示名 → 失败且错误可见
{
  const pi = new MockPi(catalog.slice(0, 2));
  const bridge = new MockBridge(pi);
  let err = null;
  bridge.on("response", (r) => {
    if (!r.ok && r.command === "set_model") err = r.error;
  });
  bridge.send(clientRpc("bad1", "set_model", { provider: "anthropic", modelId: "Claude Sonnet 4" }));
  assert.match(err || "", /Model not found/);
}

// 6) prompt 失败必须清 streaming（客户端契约）
{
  const pi = new MockPi([{ id: "broken", name: "Broken", provider: "test" }]);
  const bridge = new MockBridge(pi);
  const ui = { streaming: true, error: null };
  bridge.on("response", (r) => {
    if (r.command === "prompt" && !r.ok) {
      ui.streaming = false;
      ui.error = r.error;
    }
  });
  bridge.send(clientRpc("p1", "prompt", { message: "hi" }));
  assert.equal(ui.streaming, false);
  assert.match(ui.error || "", /auth|fail/i);
}

console.log("mock-set-model-prompt: ok");
