import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import {harness, sleep, waitFor} from './helpers/harness.mjs';

for (const [label, bridgeArgs, ephemeral] of [
  ['default', [], false], ['legacy --session', ['--session'], false],
  ['explicit --no-session', ['--no-session'], true],
  ['explicit Pi --no-session', ['--pi-arg=--no-session'], true],
]) {
  test(`${label}: persistent by default, ephemeral only when explicit`, async t => {
    const h = await harness(t, {bridgeArgs}), c = await h.connect();
    c.rpc('args', 'get_state');
    const args = (await c.response('args')).data.fixtureArgs;
    assert.equal(args.includes('--no-session'), ephemeral);
    assert.equal(args.includes('--session'), false);
    assert.deepEqual(args.slice(0, 2), ['--mode', 'rpc']);
  });
}

test('same client IDs from two sockets are correlated by unique internal IDs, out of order', async t => {
  const h = await harness(t), a = await h.connect(), b = await h.connect();
  a.rpc('a1', 'get_messages', {fixtureTag: 'A', fixtureDelay: 150});
  b.rpc('a1', 'get_messages', {fixtureTag: 'B'});
  const rb = await b.response('a1'), ra = await a.response('a1');
  assert.equal(ra.data.tag, 'A'); assert.equal(rb.data.tag, 'B');
  const commands = await h.commands();
  assert.notEqual(commands[0].id, commands[1].id);
  assert.notEqual(commands[0].id, 'a1');
  assert.equal(a.frames.filter(f => f.kind === 'response').length, 1);
  assert.equal(b.frames.filter(f => f.kind === 'response').length, 1);
});

test('unmatched IDs and missing IDs cannot steal pending requests or become broadcast events', async t => {
  const h = await harness(t), c = await h.connect();
  c.rpc('fresh', 'get_messages', {fixtureTag: 'fresh', fixtureDelay: 150});
  c.rpc('trigger', 'prompt', {message: '/fixture-unmatched'});
  await c.response('trigger');
  assert.equal((await c.response('fresh')).data.tag, 'fresh');
  assert(!c.frames.some(f => f.kind === 'event' && f.event.type === 'response'));
});

test('params cannot override command type or ordinary ID; bash is whitelisted for Pocket', async t => {
  const h = await harness(t), c = await h.connect();
  c.rpc('type', 'get_state', {type: 'bash', command: 'synthetic-never-executed'});
  c.rpc('id', 'get_state', {id: 'override'});
  for (const id of ['type', 'id']) assert.equal((await c.response(id)).ok, false);
  c.rpc('bash', 'bash', {command: 'echo pocket'});
  const bash = await c.response('bash');
  // Must not be rejected by the bridge allow-list (Pi fixture may still fail the command).
  assert.notEqual(bash.error, '命令不允许: bash');
  c.rpc('abort', 'abort_bash', {});
  const abort = await c.response('abort');
  assert.notEqual(abort.error, '命令不允许: abort_bash');
});

test('UI answer uses Pi UI ID, has no regular pending response, and is consumed exactly once', async t => {
  const h = await harness(t), c = await h.connect();
  c.rpc('ask', 'prompt', {message: '/fixture-ui'});
  const request = await c.event('confirm');
  c.rpc('answer', 'extension_ui_response', {id: request.event.id, confirmed: true});
  assert.equal((await c.response('ask')).ok, true);
  c.rpc('repeat', 'extension_ui_response', {id: request.event.id, cancelled: true});
  assert.equal((await c.response('repeat')).ok, false);
  assert(!c.frames.some(f => f.kind === 'response' && f.id === 'answer'));
  await c.close();
  await sleep(80);
  const answers = (await h.commands()).filter(r => r.type === 'extension_ui_response');
  assert.equal(answers.length, 1);
  assert.deepEqual(answers[0], {type: 'extension_ui_response', id: 'fixture-ui-id', confirmed: true});
});

test('last authenticated disconnect cancels dialog even while an unauthenticated socket stays', async t => {
  const h = await harness(t), c = await h.connect();
  await h.connect(false);
  c.rpc('ask', 'prompt', {message: '/fixture-ui'});
  await c.event('confirm'); await c.close();
  const commands = await waitFor(h.commands, rows => rows.some(r => r.cancelled));
  assert(commands.some(r => r.type === 'extension_ui_response' && r.id === 'fixture-ui-id' && r.cancelled));
});

test('disconnected owner response is dropped, not sent to another socket or broadcast', async t => {
  const h = await harness(t), a = await h.connect(), b = await h.connect();
  a.rpc('a1', 'get_messages', {fixtureTag: 'old-owner', fixtureDelay: 150});
  await waitFor(h.commands, rows => rows.length === 1); await a.close();
  b.rpc('a1', 'get_messages', {fixtureTag: 'new-owner', fixtureDelay: 250});
  assert.equal((await b.response('a1')).data.tag, 'new-owner');
  assert.equal(b.frames.filter(f => f.kind === 'response').length, 1);
  assert(!b.frames.some(f => f.kind === 'event' && f.event.type === 'response'));
});

test('exit rejects outstanding requests and resets partial stdout, dialogs and stderr before restart', async t => {
  const h = await harness(t), c = await h.connect();
  c.rpc('old-ask', 'prompt', {message: '/fixture-ui'}); await c.event('confirm');
  c.rpc('old-state', 'get_state', {fixtureDelay: 200});
  c.rpc('exit', 'prompt', {message: '/fixture-exit'});
  for (const id of ['old-ask', 'old-state', 'exit']) assert.equal((await c.response(id)).ok, false);
  c.rpc('stale-ui', 'extension_ui_response', {id: 'fixture-ui-id', confirmed: true});
  assert.equal((await c.response('stale-ui')).ok, false);
  c.rpc('new-state', 'get_state');
  assert.equal((await c.response('new-state')).ok, true);
  c.rpc('new-name', 'get_session_name');
  assert.equal((await c.response('new-name')).data.name, '');
  c.send({type: 'stop_pi'});
  const offline = await waitFor(() => c.frames.filter(f => f.kind === 'status' && f.error?.startsWith('pi exited')), rows => rows.length === 2);
  assert.equal(offline[1].stderr, '');
});

test('canonical session name survives cancelled switch/failed rename and follows successful switch', async t => {
  const h = await harness(t), c = await h.connect();
  c.rpc('rename', 'set_session_name', {name: 'synthetic A'}); await c.response('rename');
  c.rpc('switch-cancel', 'switch_session', {sessionPath: path.join(h.sessions, 'B.jsonl'), fixtureCancel: true});
  assert.equal((await c.response('switch-cancel')).data.cancelled, true);
  c.rpc('bad-name', 'set_session_name', {name: ' '}); assert.equal((await c.response('bad-name')).ok, false);
  c.rpc('name-A', 'get_session_name'); assert.equal((await c.response('name-A')).data.name, 'synthetic A');
  c.rpc('switch', 'switch_session', {sessionPath: path.join(h.sessions, 'B.jsonl'), fixtureName: 'synthetic B'}); await c.response('switch');
  c.rpc('name-B', 'get_session_name'); assert.equal((await c.response('name-B')).data.name, 'synthetic B');
  c.rpc('new', 'new_session'); await c.response('new');
  c.rpc('name-empty', 'get_session_name'); assert.equal((await c.response('name-empty')).data.name, '');
});

for (const tokenMode of ['missing', 'empty']) {
  test(`${tokenMode} token fails closed before opening a listener`, async t => {
    const h = await harness(t, {tokenMode});
    const [code] = await h.exited;
    assert.notEqual(code, 0);
    assert(!h.stderr().includes('listening ws://'));
  });
}

test('null/array records cannot crash unauthenticated bridge; wrong token cannot dispatch', async t => {
  const h = await harness(t), c = await h.connect(false);
  c.send(null); c.send([]);
  await sleep(30);
  c.send({auth: 'wrong-synthetic-token'});
  await waitFor(() => c.frames, frames => frames.some(f => f.error === 'bad token'));
  assert.equal(h.processBridge.exitCode, null); assert.deepEqual(await h.commands(), []);
  const valid = await h.connect(); valid.rpc('ok', 'get_state'); assert.equal((await valid.response('ok')).ok, true);
});

test('spawn failure rejects pending command without unhandled stdin error', async t => {
  const h = await harness(t, {piMissing: true}), c = await h.connect();
  c.rpc('fail', 'get_state'); assert.equal((await c.response('fail')).ok, false);
  await sleep(50); assert.equal(h.processBridge.exitCode, null);
});

test('session deletion refuses a symlink parent escape and a leaf symlink', async t => {
  const h = await harness(t), c = await h.connect();
  const outside = path.join(h.dir, 'outside'); await fs.mkdir(outside);
  const victim = path.join(outside, 'synthetic.jsonl'); await fs.writeFile(victim, 'synthetic fixture');
  await fs.symlink(outside, path.join(h.sessions, 'escape'));
  await fs.symlink(victim, path.join(h.sessions, 'leaf.jsonl'));
  for (const [id, target] of [['parent', path.join(h.sessions, 'escape/synthetic.jsonl')], ['leaf', path.join(h.sessions, 'leaf.jsonl')]]) {
    c.rpc(id, 'delete_session', {sessionPath: target}); assert.equal((await c.response(id)).ok, false);
  }
  assert.equal(await fs.readFile(victim, 'utf8'), 'synthetic fixture');
  const normal = path.join(h.sessions, 'normal.jsonl'); await fs.writeFile(normal, '{}\n');
  c.rpc('normal', 'delete_session', {sessionPath: normal}); assert.equal((await c.response('normal')).ok, true);
  await assert.rejects(fs.access(normal)); assert.deepEqual(await h.commands(), []);
});

test('Pocket local RPCs: list_project_files + git_status (bridge-local, not Pi)', async t => {
  const h = await harness(t), c = await h.connect();
  await fs.writeFile(path.join(h.dir, 'pocket-a.txt'), 'hello\n');
  await fs.mkdir(path.join(h.dir, 'sub'));
  await fs.writeFile(path.join(h.dir, 'sub', 'pocket-b.java'), 'class B {}\n');
  c.rpc('files', 'list_project_files', {query: 'pocket', limit: 50});
  const files = await c.response('files');
  assert.equal(files.ok, true);
  assert.ok(Array.isArray(files.data.files));
  assert.ok(files.data.files.some(f => String(f.path).includes('pocket-a.txt')));
  c.rpc('read', 'read_project_file', {path: 'pocket-a.txt'});
  const read = await c.response('read');
  assert.equal(read.ok, true);
  assert.match(read.data.content, /hello/);
  // git may be unavailable in the sandbox — failure must not be allow-list reject.
  c.rpc('gst', 'git_status', {});
  const gst = await c.response('gst');
  assert.notEqual(gst.error, '命令不允许: git_status');
});

test('Pocket cwd RPCs: get_cwd / list_cwd_entries / set_cwd persist + inject get_state', async t => {
  const h = await harness(t), c = await h.connect();
  await fs.mkdir(path.join(h.dir, 'workdir'));
  c.rpc('gc', 'get_cwd', {});
  const gc = await c.response('gc');
  assert.equal(gc.ok, true);
  assert.equal(gc.data.cwd, h.dir);
  c.rpc('ls', 'list_cwd_entries', {});
  const ls = await c.response('ls');
  assert.equal(ls.ok, true);
  assert.equal(ls.data.cwd, h.dir);
  assert.ok(ls.data.entries.some(e => e.name === 'workdir' && e.kind === 'dir'));
  c.rpc('st', 'get_state', {});
  const st = await c.response('st');
  assert.equal(st.ok, true);
  assert.equal(st.data.cwd, h.dir);
  const next = path.join(h.dir, 'workdir');
  c.rpc('set', 'set_cwd', {path: next});
  const set = await c.response('set');
  assert.equal(set.ok, true);
  assert.equal(set.data.cwd, next);
  c.rpc('gc2', 'get_cwd', {});
  assert.equal((await c.response('gc2')).data.cwd, next);
  const persisted = path.join(h.dir, '.pi', 'agent', 'pi-android.cwd');
  assert.equal((await fs.readFile(persisted, 'utf8')).trim(), next);
  c.rpc('bad', 'set_cwd', {path: path.join(h.dir, 'no-such-dir')});
  const bad = await c.response('bad');
  assert.equal(bad.ok, false);
  assert.notEqual(bad.error, '命令不允许: set_cwd');
});

test('Pocket session instructions: set/get prepended on prompt', async t => {
  const h = await harness(t), c = await h.connect();
  c.rpc('set', 'set_instructions', {text: 'use pnpm, not npm'});
  const set = await c.response('set');
  assert.equal(set.ok, true);
  assert.equal(set.data.text, 'use pnpm, not npm');
  c.rpc('p', 'prompt', {message: 'install deps'});
  await c.response('p');
  const prompt = (await h.commands()).filter(r => r.type === 'prompt').pop();
  assert.ok(prompt.message.includes('本会话说明'));
  assert.ok(prompt.message.includes('use pnpm, not npm'));
  assert.ok(prompt.message.includes('install deps'));
  c.rpc('clear', 'set_instructions', {text: ''});
  assert.equal((await c.response('clear')).data.text, '');
});

test('Pocket plan mode: set/approve injects instruction on prompt', async t => {
  const h = await harness(t), c = await h.connect();
  c.rpc('on', 'set_plan_mode', {on: true});
  const on = await c.response('on');
  assert.equal(on.ok, true);
  assert.equal(on.data.on, true);
  c.rpc('p', 'prompt', {message: 'look around'});
  await c.response('p');
  const cmds = await h.commands();
  const prompt = cmds.filter(r => r.type === 'prompt').pop();
  assert.ok(prompt?.message?.includes('计划模式') || prompt?.message?.includes('Plan mode'));
  assert.ok(prompt.message.includes('look around'));
  c.rpc('ap', 'approve_plan', {});
  const ap = await c.response('ap');
  assert.equal(ap.ok, true);
  assert.equal(ap.data.on, false);
  c.rpc('g', 'get_plan_mode', {});
  assert.equal((await c.response('g')).data.on, false);
});

test('Pocket schedules: add / list / cancel + parse when', async t => {
  const h = await harness(t), c = await h.connect();
  c.rpc('bad', 'add_schedule', {when: 'in 2h'});
  assert.equal((await c.response('bad')).ok, false); // missing message body
  c.rpc('add', 'add_schedule', {when: 'in 2h check the deploy'});
  const add = await c.response('add');
  assert.equal(add.ok, true);
  assert.ok(add.data.schedule?.id);
  assert.equal(add.data.schedule.text, 'check the deploy');
  assert.ok(add.data.schedule.next > Date.now());
  c.rpc('list', 'list_schedules', {});
  const list = await c.response('list');
  assert.equal(list.ok, true);
  assert.ok(list.data.schedules.some(s => s.id === add.data.schedule.id));
  c.rpc('cancel', 'cancel_schedule', {scheduleId: add.data.schedule.id});
  const cancel = await c.response('cancel');
  assert.equal(cancel.ok, true, cancel.error);
  assert.equal(cancel.data.cancelled, true);
  c.rpc('list2', 'list_schedules', {});
  assert.equal((await c.response('list2')).data.schedules.length, 0);
});

test('Pocket Done-when (/until): set/clear + check after agent_settled', async t => {
  const h = await harness(t), c = await h.connect();
  c.rpc('empty', 'set_until', {command: '  '});
  assert.equal((await c.response('empty')).ok, false);
  c.rpc('set', 'set_until', {command: 'false'});
  const set = await c.response('set');
  assert.equal(set.ok, true);
  assert.equal(set.data.status, 'working');
  assert.equal(set.data.command, 'false');
  // Event broadcast from setUntilGoal
  assert.ok(c.frames.some(f => f.kind === 'event' && f.event?.type === 'until_update'
    && f.event.status === 'working'));
  c.rpc('get', 'get_until', {});
  assert.equal((await c.response('get')).data.status, 'working');
  // Prompt → fake-pi agent_settled → bridge runs `false` → follow-up
  c.rpc('go', 'prompt', {message: 'work on failing check'});
  await c.response('go');
  const checking = await waitFor(
    () => c.frames.filter(f => f.kind === 'event' && f.event?.type === 'until_update'),
    rows => rows.some(f => f.event.status === 'checking' || (f.event.status === 'working' && f.event.tries >= 1)),
  );
  assert.ok(checking.length >= 1);
  c.rpc('stop', 'clear_until', {});
  const cleared = await c.response('stop');
  assert.equal(cleared.ok, true);
  assert.equal(cleared.data.status, 'stopped');
  c.rpc('idle', 'get_until', {});
  assert.equal((await c.response('idle')).data.status, 'idle');
});

test('Pocket MCP RPCs: list_mcp_servers + get_project_trust from mcp.json / trust.json', async t => {
  const h = await harness(t), c = await h.connect();
  const agentDir = path.join(h.dir, '.pi', 'agent');
  await fs.mkdir(agentDir, {recursive: true});
  await fs.writeFile(path.join(agentDir, 'mcp.json'), JSON.stringify({
    mcpServers: {
      docs: {url: 'https://example.com/mcp', description: 'Docs search', enabled: true},
      local: {command: 'npx', args: ['-y', 'noop'], enabled: false},
    },
  }));
  await fs.writeFile(path.join(agentDir, 'trust.json'), JSON.stringify({
    [h.dir]: 'trusted',
  }));
  await fs.mkdir(path.join(h.dir, '.pi'), {recursive: true});
  await fs.writeFile(path.join(h.dir, '.pi', 'mcp.json'), JSON.stringify({
    mcpServers: {
      docs: {url: 'https://example.com/mcp', description: 'Project docs', enabled: true},
    },
  }));
  c.rpc('mcp', 'list_mcp_servers', {});
  const mcp = await c.response('mcp');
  assert.equal(mcp.ok, true);
  assert.ok(Array.isArray(mcp.data.servers));
  assert.ok(mcp.data.servers.some(s => s.name === 'docs' && s.source === 'project'));
  assert.ok(mcp.data.servers.some(s => s.name === 'local' && s.status === 'disabled'));
  assert.equal(mcp.data.projectTrusted, true);
  assert.ok(!JSON.stringify(mcp.data).includes('example-secret'));
  c.rpc('trust', 'get_project_trust', {});
  const trust = await c.response('trust');
  assert.equal(trust.ok, true);
  assert.equal(trust.data.trusted, true);
});
