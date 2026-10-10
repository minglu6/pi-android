import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import {createHash, randomBytes} from 'node:crypto';
import {harness, sleep, waitFor} from './helpers/harness.mjs';
import {MAX_CHUNK_BYTES} from '../attachment-store.mjs';

const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jP1sAAAAASUVORK5CYII=', 'base64');
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
let seq = 0;
async function rpc(c, command, params = {}) {
  const id = `attachment-test-${++seq}`;
  c.rpc(id, command, params);
  const r = await c.response(id);
  assert.equal(r.command, command);
  return r;
}
async function ok(c, command, params) {
  const r = await rpc(c, command, params);
  assert.equal(r.ok, true, r.error);
  return r.data;
}
async function bad(c, command, params, pattern) {
  const r = await rpc(c, command, params);
  assert.equal(r.ok, false);
  if (pattern) assert.match(r.error, pattern);
  return r;
}
const fileParams = (size, name = 'fixture.txt') => ({name, mimeType: 'text/plain', size, kind: 'file'});
async function upload(c, bytes, params = fileParams(bytes.length)) {
  const {uploadId, maxChunkBytes} = await ok(c, 'upload_begin', params);
  assert.equal(maxChunkBytes, MAX_CHUNK_BYTES);
  for (let offset = 0; offset < bytes.length; offset += maxChunkBytes) {
    const part = bytes.subarray(offset, offset + maxChunkBytes);
    assert.deepEqual(await ok(c, 'upload_chunk', {uploadId, offset, data: part.toString('base64')}), {received: offset + part.length});
  }
  const {attachment} = await ok(c, 'upload_finish', {uploadId, sha256: sha(bytes)});
  return {uploadId, attachment};
}
const partials = h => fs.readdir(h.uploads).then(names => names.filter(n => n.startsWith('.partial-')));

test('unauthenticated upload cannot create files, spawn Pi, or disclose upload response', async t => {
  const h = await harness(t), c = await h.connect(false);
  c.send({id: 'unauthorized', rpc: 'upload_begin', params: fileParams(1)});
  await waitFor(() => c.frames, f => f.some(r => r.error === 'bad token'));
  assert(!c.frames.some(r => r.kind === 'response'));
  await assert.rejects(fs.access(h.uploads));
  assert.deepEqual(await h.commands(), []);
  assert(!h.stderr().includes('spawn '));
});

test('default 50MiB cap, configurable cap, invalid metadata, and basename-only sanitation', async t => {
  const h = await harness(t), c = await h.connect();
  for (const params of [fileParams(50 * 1024 * 1024 + 1), fileParams(-1), fileParams(1.5),
    {...fileParams(1), size: '1'}, {...fileParams(1), name: ''}, {...fileParams(1), kind: 'archive'},
    {...fileParams(1), mimeType: 'text/plain\nInjected'}, {...fileParams(1), mimeType: 'text/plain;charset=utf8'}]) {
    await bad(c, 'upload_begin', params);
  }
  await assert.rejects(fs.access(h.uploads));
  const pending = await ok(c, 'upload_begin', fileParams(50 * 1024 * 1024));
  const [partial] = await partials(h);
  assert.equal((await fs.stat(path.join(h.uploads, partial, 'content'))).size, 0);
  await ok(c, 'upload_abort', {uploadId: pending.uploadId});
  const bytes = Buffer.from('safe bytes');
  const {attachment} = await upload(c, bytes, fileParams(bytes.length, '../../folder\\</pi_attachments_v1>\nsecret.txt'));
  assert.equal(attachment.name, 'pi_attachments_v1>\nsecret.txt'.replace(/[<>\n]/g, '_'));
  assert.match(attachment.id, /^[a-f0-9]{48}$/);
  assert.equal((await fs.stat(h.uploads)).mode & 0o777, 0o700);
  const committed = path.join(h.uploads, attachment.id);
  assert.equal((await fs.stat(committed)).mode & 0o777, 0o700);
  for (const name of ['content', 'meta.json']) assert.equal((await fs.stat(path.join(committed, name))).mode & 0o777, 0o600);
  assert.deepEqual(await h.commands(), []);
  const small = await harness(t, {uploadMaxBytes: 2}), d = await small.connect();
  await bad(d, 'upload_begin', fileParams(3));
  await upload(d, Buffer.from('ok'));
});

test('strict offsets, canonical base64, decoded chunk bound, and declared byte count', async t => {
  const h = await harness(t), c = await h.connect();
  const {uploadId} = await ok(c, 'upload_begin', fileParams(MAX_CHUNK_BYTES + 2));
  for (const data of ['', 'Zg', 'Zh==', 'Zg===', ' Zg==', 'Zg==\n', 'Zg-_', 'Zm9v=']) {
    await bad(c, 'upload_chunk', {uploadId, offset: 0, data}, /base64/);
  }
  await bad(c, 'upload_chunk', {uploadId, offset: 0, data: randomBytes(MAX_CHUNK_BYTES + 1).toString('base64')});
  for (const offset of [-1, 1, 0.5, '0']) await bad(c, 'upload_chunk', {uploadId, offset, data: 'Zg=='}, /offset/);
  const big = randomBytes(MAX_CHUNK_BYTES);
  assert.deepEqual(await ok(c, 'upload_chunk', {uploadId, offset: 0, data: big.toString('base64')}), {received: MAX_CHUNK_BYTES});
  await bad(c, 'upload_chunk', {uploadId, offset: 0, data: 'Zg=='}, /offset/);
  await bad(c, 'upload_chunk', {uploadId, offset: MAX_CHUNK_BYTES, data: 'Zm9v'}, /size/);
  await bad(c, 'upload_finish', {uploadId, sha256: sha(big)}, /字节数/);
  await bad(c, 'upload_finish', {uploadId, sha256: 'not-a-hash'}, /sha256/);
  await ok(c, 'upload_chunk', {uploadId, offset: MAX_CHUNK_BYTES, data: 'b2s='});
  const bytes = Buffer.concat([big, Buffer.from('ok')]);
  const {attachment} = await ok(c, 'upload_finish', {uploadId, sha256: sha(bytes)});
  assert.deepEqual(await fs.readFile(path.join(h.uploads, attachment.id, 'content')), bytes);
  await bad(c, 'upload_finish', {uploadId, sha256: sha(bytes)});
  assert.deepEqual(await h.commands(), []);
});

test('hash mismatch destroys partial; empty files complete with SHA256 of empty bytes', async t => {
  const h = await harness(t), c = await h.connect();
  const {uploadId} = await ok(c, 'upload_begin', fileParams(1));
  await ok(c, 'upload_chunk', {uploadId, offset: 0, data: 'YQ=='});
  await bad(c, 'upload_finish', {uploadId, sha256: sha(Buffer.from('b'))}, /SHA256/);
  assert.deepEqual(await partials(h), []);
  await bad(c, 'upload_chunk', {uploadId, offset: 1, data: 'Yg=='});
  const {attachment} = await upload(c, Buffer.alloc(0));
  assert.equal(attachment.size, 0);
});

test('ws-scoped partial ownership; abort is idempotent and cannot delete completed prompt files', async t => {
  const h = await harness(t), a = await h.connect(), b = await h.connect();
  const {uploadId} = await ok(a, 'upload_begin', fileParams(1));
  await bad(b, 'upload_chunk', {uploadId, offset: 0, data: 'YQ=='});
  await bad(b, 'upload_finish', {uploadId, sha256: sha(Buffer.from('a'))});
  await bad(b, 'upload_abort', {uploadId});
  assert.deepEqual(await ok(a, 'upload_abort', {uploadId}), {cancelled: true});
  assert.deepEqual(await partials(h), []);
  assert.deepEqual(await ok(a, 'upload_abort', {uploadId}), {cancelled: true});
  const bytes = Buffer.from('never remove committed');
  const completed = await upload(a, bytes);
  await ok(a, 'prompt', {message: 'fixture prompt', attachmentIds: [completed.attachment.id]});
  await ok(b, 'upload_abort', {uploadId: completed.uploadId});
  await a.close();
  await ok(b, 'prompt', {attachmentIds: [completed.attachment.id]});
  assert.deepEqual(await fs.readFile(path.join(h.uploads, completed.attachment.id, 'content')), bytes);
});

test('disconnect cleans only owner partials; timeout cleans abandoned partials; shutdown cleans all', async t => {
  const h = await harness(t, {uploadTimeoutMs: 700}), a = await h.connect(), b = await h.connect();
  await ok(a, 'upload_begin', fileParams(1));
  const second = await ok(b, 'upload_begin', fileParams(1));
  await a.close();
  await waitFor(() => partials(h), names => names.length === 1);
  await waitFor(() => partials(h), names => names.length === 0);
  await bad(b, 'upload_chunk', {uploadId: second.uploadId, offset: 0, data: 'YQ=='});
  await ok(b, 'upload_begin', fileParams(1));
  h.processBridge.kill('SIGTERM');
  await waitFor(() => partials(h), names => names.length === 0);
});

test('completed ID survives bridge restart and is reusable after reconnect; crash leftovers recovered', async t => {
  const h = await harness(t), a = await h.connect();
  const bytes = Buffer.from('durable attachment\u2028\u2029\n');
  const {attachment} = await upload(a, bytes);
  await ok(a, 'upload_begin', fileParams(2));
  await h.restart('SIGKILL'); // No Pi has been started: never leaves even a synthetic child orphaned.
  const b = await h.connect();
  await ok(b, 'prompt', {attachmentIds: [attachment.id]});
  assert.deepEqual(await partials(h), []);
  const saved = path.join(h.uploads, attachment.id, 'content');
  assert.deepEqual(await fs.readFile(saved), bytes);
  await h.restart();
  const c = await h.connect();
  await ok(c, 'prompt', {message: 'again', attachmentIds: [attachment.id]});
  assert.deepEqual(await fs.readFile(saved), bytes);
});

test('file prompt marker contains exact absolute path, name, MIME, byte size and kind; no local RPC forwarded', async t => {
  const h = await harness(t), c = await h.connect();
  const bytes = Buffer.from([0, 255, 128, 10, 13, 60, 62, 1]);
  const {attachment} = await upload(c, bytes, {name: 'quoted "报告".bin', mimeType: 'application/octet-stream', size: bytes.length, kind: 'file'});
  const text = 'Read only; do not execute.\u2028Keep separators\u2029';
  const r = await rpc(c, 'prompt', {message: text, attachmentIds: [attachment.id], streamingBehavior: 'followUp'});
  assert.equal(r.ok, true); assert.deepEqual(r.data, {disposition: 'started'});
  const commands = await h.commands();
  assert.equal(commands.length, 1);
  const command = commands[0];
  const saved = path.join(await fs.realpath(h.uploads), attachment.id, 'content');
  const descriptors = [{name: attachment.name, path: saved, mimeType: attachment.mimeType, size: bytes.length, kind: 'file'}];
  assert.equal(command.message, text + '\n\n<pi_attachments_v1>\n' + JSON.stringify(descriptors) + '\n</pi_attachments_v1>');
  assert.equal(command.type, 'prompt'); assert.equal(command.streamingBehavior, 'followUp');
  assert(!Object.hasOwn(command, 'attachmentIds')); assert(!Object.hasOwn(command, 'images'));
  assert.deepEqual(await fs.readFile(saved), bytes);
  await waitFor(() => c.frames, f => f.some(r => r.event?.type === 'agent_settled'));
  assert(c.frames.some(r => r.event?.type === 'agent_end'));
  assert.equal(c.frames.filter(f => f.kind === 'response' && f.id === r.id).length, 1);
  assert(!h.stderr().includes(bytes.toString('base64')));
});

test('native image schema preserves exact MIME/base64 bytes, with no path marker or bridge fields', async t => {
  const h = await harness(t), a = await h.connect(), b = await h.connect();
  const {attachment} = await upload(a, png, {name: 'fixture.png', mimeType: 'image/png', size: png.length, kind: 'image'});
  a.rpc('same-id', 'prompt', {message: '', attachmentIds: [attachment.id]});
  b.rpc('same-id', 'get_messages', {fixtureTag: 'independent'});
  assert.equal((await a.response('same-id')).ok, true);
  assert.equal((await b.response('same-id')).data.tag, 'independent');
  const commands = await h.commands();
  const prompt = commands.find(r => r.type === 'prompt');
  assert.deepEqual(prompt.images, [{type: 'image', data: png.toString('base64'), mimeType: 'image/png'}]);
  assert.equal(prompt.message, '请查看这些附件。');
  assert(!Object.hasOwn(prompt, 'attachmentIds')); assert(!prompt.message.includes('<pi_attachments_v1>'));
  assert.equal(commands.filter(r => r.type === 'get_state').length, 1);
  await waitFor(() => a.frames, f => f.some(r => r.event?.type === 'agent_settled'));
  const user = a.frames.find(f => f.event?.type === 'message_end').event.message;
  assert.deepEqual(user.content[1], prompt.images[0]);
  assert.deepEqual(Buffer.from(user.content[1].data, 'base64'), png);
  assert.equal(a.frames.filter(r => r.kind === 'response' && r.id === 'same-id').length, 1);
});

test('mixed file and image attachments use distinct channels and max 8 is enforced before Pi starts', async t => {
  const h = await harness(t), c = await h.connect();
  const file = (await upload(c, Buffer.from('file'))).attachment;
  const image = (await upload(c, png, {name: 'pixel.png', mimeType: 'image/png', size: png.length, kind: 'image'})).attachment;
  await bad(c, 'prompt', {attachmentIds: Array.from({length: 9}, () => file.id)});
  await bad(c, 'prompt', {attachmentIds: [file.id, file.id]});
  await bad(c, 'prompt', {attachmentIds: file.id});
  await bad(c, 'prompt', {attachmentIds: [image.id], images: [{type: 'image'}]});
  await bad(c, 'steer', {attachmentIds: [file.id]});
  assert.deepEqual(await h.commands(), []);
  await ok(c, 'prompt', {attachmentIds: [file.id, image.id]});
  const prompt = (await h.commands()).find(r => r.type === 'prompt');
  const marker = JSON.parse(prompt.message.split('<pi_attachments_v1>\n')[1].split('\n</pi_attachments_v1>')[0]);
  assert.equal(marker.length, 1); assert.equal(marker[0].kind, 'file');
  assert.equal(prompt.images.length, 1);
  assert.equal(prompt.message.startsWith('请查看这些附件。'), true);
});

test('eight sequential attachments accepted; ninth parallel upload refused without extra file', async t => {
  const h = await harness(t), c = await h.connect();
  const ids = [];
  for (let i = 0; i < 8; i++) ids.push((await upload(c, Buffer.from([i]))).attachment.id);
  await ok(c, 'prompt', {message: 'all eight', attachmentIds: ids});
  for (let i = 0; i < 8; i++) await ok(c, 'upload_begin', fileParams(1));
  await bad(c, 'upload_begin', fileParams(1), /数量/);
  assert.equal((await partials(h)).length, 8);
});

test('unsupported image MIME rejected; declared non-vision model clearly rejected, never silently forwarded', async t => {
  const h = await harness(t), c = await h.connect();
  for (const mimeType of ['image/svg+xml', 'image/heic', 'application/pdf', 'image/bmp']) {
    await bad(c, 'upload_begin', {name: 'fixture', mimeType, size: 1, kind: 'image'}, /不支持/);
  }
  await bad(c, 'upload_begin', {name: 'empty.png', mimeType: 'image/png', size: 0, kind: 'image'});
  const {attachment} = await upload(c, png, {name: 'fixture.png', mimeType: 'image/png', size: png.length, kind: 'image'});
  await ok(c, 'set_model', {provider: 'fake', modelId: 'fixture-text'});
  await bad(c, 'prompt', {attachmentIds: [attachment.id]}, /模型不支持图片/);
  assert(!(await h.commands()).some(r => r.type === 'prompt'));
  await ok(c, 'set_model', {provider: 'fake', modelId: 'fixture-vision'});
  await ok(c, 'prompt', {attachmentIds: [attachment.id]});
  assert.equal((await h.commands()).filter(r => r.type === 'prompt').length, 1);
});

for (const [label, fakeEnv, succeeds] of [
  ['unknown capabilities', {PI_BRIDGE_FAKE_UNKNOWN_MODEL: '1'}, true],
  ['state query failure', {PI_BRIDGE_FAKE_STATE_FAIL: '1'}, false],
]) {
  test(`image model preflight: ${label}`, async t => {
    const h = await harness(t, {fakeEnv}), c = await h.connect();
    const {attachment} = await upload(c, png, {name: 'pixel.png', mimeType: 'image/png', size: png.length, kind: 'image'});
    const r = await rpc(c, 'prompt', {attachmentIds: [attachment.id]});
    assert.equal(r.ok, succeeds);
    assert.equal((await h.commands()).filter(r => r.type === 'prompt').length, succeeds ? 1 : 0);
  });
}

test('disconnect while model probe pending drops only its prompt, not another client response', async t => {
  const h = await harness(t, {fakeEnv: {PI_BRIDGE_FAKE_STATE_DELAY: '300'}}), a = await h.connect(), b = await h.connect();
  const {attachment} = await upload(a, png, {name: 'pixel.png', mimeType: 'image/png', size: png.length, kind: 'image'});
  a.rpc('image', 'prompt', {attachmentIds: [attachment.id]});
  await waitFor(h.commands, rows => rows.some(r => r.type === 'get_state'));
  await a.close();
  await ok(b, 'get_messages'); await sleep(350);
  assert(!(await h.commands()).some(r => r.type === 'prompt'));
  assert(!b.frames.some(r => r.kind === 'response' && r.id === 'image'));
});

test('opaque IDs reject traversal/arbitrary paths; symlinks and changed metadata/content fail closed after restart', async t => {
  const h = await harness(t), c = await h.connect();
  for (const id of ['../outside', '/etc/passwd', 'a'.repeat(47), 'g'.repeat(48), null]) {
    await bad(c, 'prompt', {attachmentIds: [id]});
    await bad(c, 'upload_abort', {uploadId: id});
  }
  const bytes = Buffer.from('original bytes');
  const first = (await upload(c, bytes)).attachment;
  const outside = path.join(h.dir, 'outside'); await fs.writeFile(outside, bytes, {mode: 0o600});
  const firstFile = path.join(h.uploads, first.id, 'content');
  await fs.unlink(firstFile); await fs.symlink(outside, firstFile);
  await bad(c, 'prompt', {attachmentIds: [first.id]});
  const second = (await upload(c, bytes)).attachment;
  const secondFile = path.join(h.uploads, second.id, 'content');
  await fs.writeFile(secondFile, Buffer.alloc(bytes.length));
  await bad(c, 'prompt', {attachmentIds: [second.id]}, /SHA256/);
  const third = (await upload(c, bytes)).attachment;
  const thirdDir = path.join(h.uploads, third.id);
  await fs.rename(thirdDir, path.join(h.dir, 'displaced')); await fs.symlink(path.join(h.dir, 'displaced'), thirdDir);
  await bad(c, 'prompt', {attachmentIds: [third.id]});
  const fourth = (await upload(c, bytes)).attachment;
  await fs.writeFile(path.join(h.uploads, fourth.id, 'meta.json'), JSON.stringify({v: 1, attachment: {...fourth, id: '../outside'}, sha256: sha(bytes)}));
  await bad(c, 'prompt', {attachmentIds: [fourth.id]});
  assert.deepEqual(await h.commands(), []);
  await h.restart();
  const d = await h.connect();
  for (const item of [first, second, third, fourth]) await bad(d, 'prompt', {attachmentIds: [item.id]});
  assert.deepEqual(await fs.readFile(outside), bytes);
  assert.deepEqual(await h.commands(), []);
});

test('upload root symlink is not followed; invalid local params cannot be forwarded to Pi', async t => {
  const h = await harness(t), c = await h.connect();
  const outside = path.join(h.dir, 'outside'); await fs.mkdir(outside, {mode: 0o700});
  await fs.symlink(outside, h.uploads);
  await bad(c, 'upload_begin', fileParams(1));
  for (const command of ['upload_begin', 'upload_chunk', 'upload_finish', 'upload_abort']) {
    await bad(c, command, {type: 'prompt'});
    await bad(c, command, {id: 'override'});
    await bad(c, command, []);
  }
  assert.deepEqual(await fs.readdir(outside), []);
  assert.deepEqual(await h.commands(), []);
});

test('text-only forwarding regression: no image probe, no marker/default text, normal correlation and lifecycle', async t => {
  const h = await harness(t), c = await h.connect();
  const params = {message: 'hello\u2028world\u2029', streamingBehavior: 'steer'};
  await ok(c, 'prompt', params);
  await ok(c, 'prompt', {message: '', attachmentIds: []});
  const commands = await h.commands();
  assert.equal(commands.length, 2);
  assert.deepEqual({...commands[0], id: 'ignored'}, {...params, type: 'prompt', id: 'ignored'});
  assert.equal(commands[1].message, '');
  assert(!Object.hasOwn(commands[1], 'attachmentIds'));
  await assert.rejects(fs.access(h.uploads));
});


test('declared PNG with unsupported bytes fails on finish; no committed file survives', async t => {
  const h = await harness(t), c = await h.connect();
  const bytes = Buffer.from('<svg><script>fixture only</script></svg>');
  const {uploadId} = await ok(c, 'upload_begin', {name: 'claimed.png', mimeType: 'image/png', size: bytes.length, kind: 'image'});
  await ok(c, 'upload_chunk', {uploadId, offset: 0, data: bytes.toString('base64')});
  await bad(c, 'upload_finish', {uploadId, sha256: sha(bytes)}, /MIME/);
  assert.deepEqual(await fs.readdir(h.uploads), []);
  assert.deepEqual(await h.commands(), []);
});

test('legacy inline official images keep exact bytes but cannot bypass count, MIME, or non-vision checks', async t => {
  const h = await harness(t), c = await h.connect();
  const image = {type: 'image', data: png.toString('base64'), mimeType: 'image/png'};
  await bad(c, 'prompt', {message: 'nine', images: Array(9).fill(image)});
  await bad(c, 'prompt', {message: 'unsupported', images: [{...image, mimeType: 'image/svg+xml'}]});
  await bad(c, 'prompt', {message: 'invalid', images: [{...image, data: image.data + '\n'}]});
  assert.deepEqual(await h.commands(), []);
  await ok(c, 'set_model', {provider: 'fake', modelId: 'fixture-text'});
  await bad(c, 'prompt', {message: 'pixel', images: [image]}, /模型不支持图片/);
  await ok(c, 'set_model', {provider: 'fake', modelId: 'fixture-vision'});
  await ok(c, 'prompt', {message: 'pixel', images: [image]});
  const prompt = (await h.commands()).find(r => r.type === 'prompt');
  assert.equal(prompt.message, 'pixel'); assert.deepEqual(prompt.images, [image]);
  await assert.rejects(fs.access(h.uploads));
});

test('image prompt handled disposition stays handled, with only one correlated response', async t => {
  const h = await harness(t), c = await h.connect();
  const {attachment} = await upload(c, png, {name: 'pixel.png', mimeType: 'image/png', size: png.length, kind: 'image'});
  const r = await rpc(c, 'prompt', {message: '/fixture-unmatched', attachmentIds: [attachment.id]});
  assert.equal(r.ok, true); assert.equal(r.data.disposition, 'handled');
  assert.equal(c.frames.filter(f => f.kind === 'response' && f.id === r.id).length, 1);
  assert(!c.frames.some(f => f.event?.type === 'response'));
});

test('image model query timeout fails original prompt once, and late state response is discarded', async t => {
  const h = await harness(t, {fakeEnv: {PI_BRIDGE_FAKE_STATE_DELAY: '11000'}}), c = await h.connect();
  const {attachment} = await upload(c, png, {name: 'pixel.png', mimeType: 'image/png', size: png.length, kind: 'image'});
  c.rpc('probe-timeout', 'prompt', {attachmentIds: [attachment.id]});
  const r = await waitFor(() => c.frames.find(f => f.kind === 'response' && f.id === 'probe-timeout'), Boolean, 12000);
  assert.equal(r.ok, false); assert.match(r.error, /超时/);
  await sleep(1200);
  assert.equal(c.frames.filter(f => f.kind === 'response' && f.id === 'probe-timeout').length, 1);
  assert(!(await h.commands()).some(r => r.type === 'prompt'));
});
