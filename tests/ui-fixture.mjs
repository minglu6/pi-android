// Isolated device UI fixture. Never starts Pi or reads real credentials/sessions.
// node tests/ui-fixture.mjs --port 18945  (App token: pi-ui-fixture-only)
import { WebSocketServer } from '../bridge/node_modules/ws/wrapper.mjs';
import fs from 'node:fs';
const portIndex = process.argv.indexOf('--port');
const port = Number(portIndex >= 0 ? process.argv[portIndex + 1] : 18945);
const server = new WebSocketServer({ host: '127.0.0.1', port });
const models = [
  { provider: 'fixture-a', id: 'shared-id', name: 'Fixture A', reasoning: true },
  { provider: 'fixture-b', id: 'shared-id', name: 'Fixture B', reasoning: true },
  { provider: 'fixture-b', id: 'rejected-id', name: 'Rejected model', reasoning: true },
];
let model = models[0], thinkingLevel = 'high', newAttempts = 0, busy = false, runVersion = 0;
const authorized = new Set();
const activeTools = new Map();
const broadcastEvent = event => {
  if (event.type === 'tool_execution_start') activeTools.set(event.toolCallId, { name: event.toolName, content: [] });
  if (event.type === 'tool_execution_update' && activeTools.has(event.toolCallId)) activeTools.get(event.toolCallId).content = event.partialResult.content;
  if (event.type === 'tool_execution_end') activeTools.delete(event.toolCallId);
  for (const peer of authorized) if (peer.readyState === 1) peer.send(JSON.stringify({ v: 1, kind: 'event', event }));
};
let sessionFile = '/fixture/before.jsonl', sessionName = 'REGRESSION_OLD';
let messages = [
  { role: 'user', content: 'HISTORY_USER_OLD', timestamp: 1000 },
  { role: 'assistant', content: [{ type: 'text', text: 'HISTORY_ASSISTANT_OLD' }], timestamp: 2000 },
];
const records = [];
function record(rec) {
  records.push(rec);
  if (process.env.TEST_RECORD) fs.writeFileSync(process.env.TEST_RECORD, JSON.stringify(records, null, 2));
}
server.on('connection', ws => {
  let authed = false;
  const send = data => ws.readyState === 1 && ws.send(JSON.stringify(data));
  const event = broadcastEvent;
  ws.on('close', () => authorized.delete(ws));
  send({ v: 1, kind: 'status', state: 'offline' });
  ws.on('message', data => {
    const frame = JSON.parse(String(data));
    if (!authed) {
      if (frame.auth !== 'pi-ui-fixture-only') return ws.close(4001, 'bad fixture token');
      authed = true;
      authorized.add(ws);
      send({ v: 1, kind: 'status', state: 'online' });
      return;
    }
    const command = frame.rpc, params = frame.params || {};
    const response = (ok, data, error) => send({ v: 1, kind: 'response', id: frame.id, command, ok, data, error });
    record({ command, ...(command === 'set_model' ? params : {}) });
    switch (command) {
      case 'get_state': return response(true, { model, thinkingLevel, sessionFile, sessionName, isStreaming: busy });
      case 'get_available_models': return response(true, { models });
      case 'get_available_thinking_levels': return response(true, { levels: ['off', 'medium', 'high'] });
      case 'get_messages': return response(true, { messages });
      case 'get_commands': return response(true, { commands: [] });
      case 'get_session_name': return response(true, { name: sessionName });
      case 'set_model': {
        const found = models.find(m => m.provider === params.provider && m.id === params.modelId);
        if (!found || found.id === 'rejected-id') return response(false, null, 'FIXTURE_MODEL_REJECTED');
        model = found;
        response(true, model);
        return;
      }
      case 'set_thinking_level': return response(false, null, 'FIXTURE_THINKING_REJECTED');
      case 'new_session': {
        if (++newAttempts === 1) return response(true, { cancelled: true });
        busy = false; runVersion++; activeTools.clear();
        sessionFile = '/fixture/after.jsonl'; sessionName = ''; messages = [];
        return response(true, { cancelled: false });
      }
      case 'switch_session': return response(true, { cancelled: true });
      case 'list_sessions': return response(true, { sessions: [{ path: '/fixture/cancelled.jsonl', name: 'CANCELLED_SWITCH', mtimeMs: Date.now() }] });
      case 'get_fork_messages': return response(true, { messages: [{ entryId: 'fork-id', text: 'FORK_INPUT' }] });
      case 'fork': return response(true, { cancelled: true });
      case 'prompt': {
        if (busy) return response(false, null, 'FIXTURE_ALREADY_BUSY');
        const version = ++runVersion;
        const active = () => busy && version === runVersion;
        busy = true;
        messages.push({ role: 'user', content: params.message, timestamp: Date.now() });
        response(true, { disposition: 'started' }); event({ type: 'agent_start' });
        if (params.message === 'PACED_STREAM') {
          pacedRun(event, () => { busy = false; }, active, version);
          return;
        }
        let delay = 100;
        const later = fn => { setTimeout(() => { if (active()) fn(); }, delay); delay += 150; };
        const assistant = text => {
          const msg = { role: 'assistant', content: [{ type: 'text', text }], timestamp: Date.now() };
          later(() => event({ type: 'message_start', message: { ...msg, content: [] } }));
          later(() => event({ type: 'message_update', assistantMessageEvent: { type: 'text_delta', contentIndex: 0, delta: text } }));
          later(() => { messages.push(msg); event({ type: 'message_end', message: msg }); });
        };
        assistant('STREAM_FIRST');
        later(() => event({ type: 'message_update', assistantMessageEvent: { type: 'toolcall_start', contentIndex: 1, id: 'tool-1', toolName: 'fixture_tool' } }));
        later(() => event({ type: 'tool_execution_start', toolCallId: 'tool-1', toolName: 'fixture_tool', args: {} }));
        later(() => {
          const msg = { role: 'toolResult', toolCallId: 'tool-1', content: [{ type: 'text', text: 'TOOL_RESULT' }] };
          messages.push(msg); event({ type: 'tool_execution_end', toolCallId: 'tool-1', toolName: 'fixture_tool', result: msg, isError: false });
        });
        assistant('STREAM_SECOND');
        later(() => { busy = false; event({ type: 'agent_end', messages: [], willRetry: false }); event({ type: 'agent_settled', aborted: false }); });
        return;
      }
      case 'ui_reconnect':
        // Device-only regression hook: retain canonical history and force the app to reconnect.
        response(true, {});
        setTimeout(() => { for (const client of server.clients) client.close(1012, 'UI fixture reconnect'); }, 25);
        return;
      case 'abort':
        busy = false; runVersion++;
        response(true, {});
        for (const [id, tool] of [...activeTools]) {
          const result = { role: 'toolResult', toolCallId: id, toolName: tool.name, content: tool.content, isError: true, details: { cancelled: true }, timestamp: Date.now() };
          messages.push(result);
          event({ type: 'tool_execution_end', toolCallId: id, toolName: tool.name, result, isError: true });
        }
        event({ type: 'agent_settled', aborted: true });
        return;
      default: return response(true, {});
    }
  });
});
function pacedRun(event, done, active, version) {
  const schedule = (ms, data, store) => setTimeout(() => {
    if (!active()) return;
    if (store) messages.push(store);
    event(data);
  }, ms);
  let text = '收到，开始流式演示。\n';
  schedule(100, { type: 'message_start', message: { role: 'assistant', content: [] } });
  schedule(250, { type: 'message_update', assistantMessageEvent: { type: 'text_start', contentIndex: 0 } });
  schedule(500, { type: 'message_update', assistantMessageEvent: { type: 'text_delta', contentIndex: 0, delta: text } });
  for (let i = 1; i <= 4; i++) {
    const delta = `流式片段 ${String(i).padStart(2, '0')}：正在持续输出。\n`;
    text += delta;
    schedule(500 + i * 500, { type: 'message_update', assistantMessageEvent: { type: 'text_delta', contentIndex: 0, delta } });
  }
  const first = { role: 'assistant', content: [{ type: 'text', text }], timestamp: Date.now() };
  schedule(3000, { type: 'message_update', assistantMessageEvent: { type: 'text_end', contentIndex: 0, content: text } });
  schedule(3100, { type: 'message_end', message: first }, first);
  const call = { type: 'toolCall', id: `paced-bash-${version}`, name: 'bash', arguments: { command: 'printf "streaming demo\\n"' } };
  const assistant = { role: 'assistant', content: [call], timestamp: Date.now() };
  schedule(3200, { type: 'message_start', message: { role: 'assistant', content: [] } });
  schedule(3250, { type: 'message_update', assistantMessageEvent: { type: 'toolcall_end', contentIndex: 0, toolCall: call } });
  schedule(3300, { type: 'message_end', message: assistant }, assistant);
  schedule(3400, { type: 'tool_execution_start', toolCallId: call.id, toolName: 'bash', args: call.arguments });
  let output = '';
  for (let i = 1; i <= 6; i++) {
    output += `BASH_PARTIAL_${i} /data/data/com.termux/files/home/very-long-demo-directory/` + 'unchanged-path-segment/'.repeat(4) + '\n';
    const partialResult = { content: [{ type: 'text', text: output }] };
    schedule(3400 + i * 500, { type: 'tool_execution_update', toolCallId: call.id, toolName: 'bash', args: call.arguments, partialResult });
  }
  output += '**literal tool text** `not markdown`\nBASH_FINAL_TAIL\n';
  const result = { role: 'toolResult', toolCallId: call.id, toolName: 'bash', content: [{ type: 'text', text: output }], timestamp: Date.now(), isError: false };
  schedule(7900, { type: 'tool_execution_end', toolCallId: call.id, toolName: 'bash', result, isError: false, durationMs: 4500 }, result);
  const last = { role: 'assistant', content: [{ type: 'text', text: '完成，工具输出应在运行过程中持续更新。' }], timestamp: Date.now() };
  schedule(8000, { type: 'message_start', message: { role: 'assistant', content: [] } });
  schedule(8100, { type: 'message_update', assistantMessageEvent: { type: 'text_delta', contentIndex: 0, delta: last.content[0].text } });
  schedule(8300, { type: 'message_end', message: last }, last);
  setTimeout(() => { if (!active()) return; done(); event({ type: 'agent_end', messages: [], willRetry: false }); event({ type: 'agent_settled', aborted: false }); }, 8400);
}
server.on('listening', () => console.log(`UI_FIXTURE_READY ${server.address().port}`));
process.on('SIGTERM', () => { for (const ws of server.clients) ws.terminate(); server.close(() => process.exit(0)); });
