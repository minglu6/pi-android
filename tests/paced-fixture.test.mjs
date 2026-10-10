// Only an isolated fake backend; never reads Pi credentials or performs shell/model calls.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { fileURLToPath } from 'node:url';
import WebSocket from '../bridge/node_modules/ws/wrapper.mjs';

async function peer(port) {
  const ws = new WebSocket(`ws://127.0.0.1:${port}`);
  const frames = [], waiters = new Set();
  const wait = (predicate, from = 0) => new Promise((resolve, reject) => {
    const match = frames.slice(from).find(predicate);
    if (match) return resolve(match);
    const item = { predicate, resolve: frame => { clearTimeout(item.timer); waiters.delete(item); resolve(frame); } };
    item.timer = setTimeout(() => { waiters.delete(item); reject(new Error('Fixture frame timeout')); }, 10000);
    waiters.add(item);
  });
  ws.on('message', data => {
    const frame = JSON.parse(String(data)); frames.push(frame);
    for (const item of [...waiters]) if (item.predicate(frame)) item.resolve(frame);
  });
  let sequence = 0;
  await once(ws, 'open'); ws.send(JSON.stringify({ auth: 'pi-ui-fixture-only' }));
  await wait(frame => frame.kind === 'status' && frame.state === 'online');
  return { ws, frames, wait, async rpc(command, params = {}) {
    const id = `rpc-${++sequence}`;
    ws.send(JSON.stringify({ v: 1, id, rpc: command, params }));
    const result = await wait(frame => frame.kind === 'response' && frame.id === id);
    assert.equal(result.ok, true, command);
    return result.data;
  } };
}

test('paced text/tool progress is visible before end; reconnect and abort do not restart a run', { timeout: 20000 }, async () => {
  const child = spawn(process.execPath, [fileURLToPath(new URL('./ui-fixture.mjs', import.meta.url)), '--port', '0'], {
    env: { ...process.env, TEST_RECORD: '' }, stdio: ['ignore', 'pipe', 'pipe'],
  });
  const peers = [];
  try {
    const port = await new Promise((resolve, reject) => {
      const timeout = setTimeout(() => reject(new Error('Fixture startup timeout')), 5000);
      child.once('error', reject);
      child.stdout.on('data', data => {
        const match = String(data).match(/UI_FIXTURE_READY (\d+)/);
        if (match) { clearTimeout(timeout); resolve(Number(match[1])); }
      });
    });
    const first = await peer(port); peers.push(first);
    assert.equal((await first.rpc('get_state')).isStreaming, false);
    await first.rpc('prompt', { message: 'PACED_STREAM' });
    await first.wait(frame => frame.event?.assistantMessageEvent?.type === 'text_delta');
    assert.equal(first.frames.some(frame => frame.event?.type === 'message_end'), false);
    assert.equal((await first.rpc('get_state')).isStreaming, true);
    const update = await first.wait(frame => frame.event?.type === 'tool_execution_update');
    assert.match(update.event.partialResult.content[0].text, /BASH_PARTIAL_1/);
    assert.equal(first.frames.some(frame => frame.event?.type === 'tool_execution_end'), false);
    const reconnected = await peer(port); peers.push(reconnected);
    assert.equal((await reconnected.rpc('get_state')).isStreaming, true);
    const next = await reconnected.wait(frame => frame.event?.type === 'tool_execution_update');
    assert.match(next.event.partialResult.content[0].text, /BASH_PARTIAL_2/);
    await reconnected.rpc('abort');
    await reconnected.wait(frame => frame.event?.type === 'agent_settled' && frame.event.aborted === true);
    assert.equal((await reconnected.rpc('get_state')).isStreaming, false);
    const messages = (await reconnected.rpc('get_messages')).messages;
    const result = messages.findLast(message => message.role === 'toolResult');
    assert.equal(result.details.cancelled, true);
    assert.equal(result.content[0].text, next.event.partialResult.content[0].text);
    const from = reconnected.frames.length;
    await new Promise(resolve => setTimeout(resolve, 600));
    assert.equal(reconnected.frames.slice(from).some(frame => frame.event?.type === 'tool_execution_update'), false);
  } finally {
    for (const peer of peers) peer.ws.terminate();
    if (child.exitCode === null) { const stopped = once(child, 'exit'); child.kill('SIGTERM'); await stopped; }
  }
});
