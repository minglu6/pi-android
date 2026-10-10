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
let model = models[0], thinkingLevel = 'high', newAttempts = 0, busy = false;
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
  const event = e => send({ v: 1, kind: 'event', event: e });
  send({ v: 1, kind: 'status', state: 'offline' });
  ws.on('message', data => {
    const frame = JSON.parse(String(data));
    if (!authed) {
      if (frame.auth !== 'pi-ui-fixture-only') return ws.close(4001, 'bad fixture token');
      authed = true;
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
        sessionFile = '/fixture/after.jsonl'; sessionName = ''; messages = [];
        return response(true, { cancelled: false });
      }
      case 'switch_session': return response(true, { cancelled: true });
      case 'list_sessions': return response(true, { sessions: [{ path: '/fixture/cancelled.jsonl', name: 'CANCELLED_SWITCH', mtimeMs: Date.now() }] });
      case 'get_fork_messages': return response(true, { messages: [{ entryId: 'fork-id', text: 'FORK_INPUT' }] });
      case 'fork': return response(true, { cancelled: true });
      case 'prompt': {
        busy = true;
        messages.push({ role: 'user', content: params.message, timestamp: Date.now() });
        response(true, { disposition: 'started' }); event({ type: 'agent_start' });
        let delay = 100;
        const later = fn => { setTimeout(fn, delay); delay += 150; };
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
      case 'abort': busy = false; return response(true, {});
      default: return response(true, {});
    }
  });
});
server.on('listening', () => console.log(`UI_FIXTURE_READY ${port}`));
process.on('SIGTERM', () => { for (const ws of server.clients) ws.terminate(); server.close(() => process.exit(0)); });
