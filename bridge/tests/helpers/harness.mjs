import {spawn} from 'node:child_process';
import {randomBytes} from 'node:crypto';
import {once} from 'node:events';
import fs from 'node:fs/promises';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {WebSocket} from 'ws';

const bridgeFile = fileURLToPath(new URL('../../pi-rpc-bridge.mjs', import.meta.url));
const fixtureFile = fileURLToPath(new URL('../fixtures/fake-pi.cjs', import.meta.url));
export const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
export async function waitFor(read, predicate, ms = 4000) {
  const deadline = Date.now() + ms;
  while (Date.now() < deadline) {
    const value = await read();
    if (predicate(value)) return value;
    await sleep(10);
  }
  throw new Error('Synthetic fixture wait timed out');
}
async function availablePort() {
  const server = net.createServer();
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  const port = server.address().port;
  await new Promise(resolve => server.close(resolve));
  return port;
}

export async function harness(t, {tokenMode = 'valid', piMissing = false, bridgeArgs = [], uploadMaxBytes, uploadTimeoutMs, fakeEnv = {}} = {}) {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'pi-bridge-test-'));
  await fs.chmod(dir, 0o700);
  const sessions = path.join(dir, 'sessions');
  await fs.mkdir(sessions);
  const token = randomBytes(24).toString('hex'); // Synthetic; never log it.
  const tokenFile = path.join(dir, 'token');
  if (tokenMode !== 'missing') await fs.writeFile(tokenFile, tokenMode === 'empty' ? '\n' : token, {mode: 0o600});
  const fakePi = path.join(dir, 'fake-pi.cjs');
  await fs.writeFile(fakePi, `#!${process.execPath}\n` + await fs.readFile(fixtureFile, 'utf8'), {mode: 0o700});
  const logFile = path.join(dir, 'synthetic-input.jsonl');
  await fs.writeFile(logFile, '');
  const port = await availablePort(); // Never connects to the production port.
  const uploads = path.join(dir, 'uploads');
  let processBridge, exited, stderr;
  function start() {
    stderr = '';
    processBridge = spawn(process.execPath, [bridgeFile, '--host', '127.0.0.1', '--port', String(port),
      '--cwd', dir, '--sessions-dir', sessions, '--token-file', tokenFile, '--idle-exit', '0',
      '--pi-bin', piMissing ? path.join(dir, 'does-not-exist') : fakePi, ...bridgeArgs], {
      cwd: dir, env: {...process.env, ...fakeEnv, HOME: dir, PI_BRIDGE_TEST_LOG: logFile,
        PI_BRIDGE_EXIT_ON_IDLE: '0', PI_ANDROID_UPLOAD_DIR: uploads,
        PI_ANDROID_UPLOAD_MAX_BYTES: String(uploadMaxBytes ?? 50 * 1024 * 1024),
        PI_ANDROID_UPLOAD_TIMEOUT_MS: String(uploadTimeoutMs ?? 300000)},
      stdio: ['ignore', 'ignore', 'pipe'],
    });
    processBridge.stderr.setEncoding('utf8');
    processBridge.stderr.on('data', s => { stderr += s; });
    exited = once(processBridge, 'exit');
  }
  start();
  const sockets = [];
  t.after(async () => {
    for (const socket of sockets) socket.terminate();
    if (processBridge.exitCode === null && processBridge.signalCode === null) processBridge.kill('SIGTERM');
    await Promise.race([exited, sleep(4000)]);
    if (processBridge.exitCode === null && processBridge.signalCode === null) {
      processBridge.kill('SIGKILL'); await exited;
    }
    await fs.rm(dir, {recursive: true, force: true});
  });
  if (tokenMode === 'valid') {
    await waitFor(() => stderr, s => s.includes('listening ws://'));
  }
  async function connect(auth = true) {
    const ws = new WebSocket(`ws://127.0.0.1:${port}`);
    sockets.push(ws);
    const frames = [];
    ws.on('message', data => { frames.push(JSON.parse(String(data))); });
    ws.on('error', () => {});
    await once(ws, 'open');
    const c = {
      ws, frames,
      send: obj => ws.send(JSON.stringify(obj)),
      rpc: (id, rpc, params = {}) => ws.send(JSON.stringify({id, rpc, params})),
      response: id => waitFor(() => frames.find(f => f.kind === 'response' && f.id === id), Boolean),
      event: method => waitFor(() => frames.find(f => f.kind === 'event' && f.event.method === method), Boolean),
      close: async () => { const closed = once(ws, 'close'); ws.close(); await closed; },
    };
    if (auth) {
      c.send({auth: token});
      await waitFor(() => frames, list => list.some(f => f.kind === 'status' && f.state === 'online'));
    }
    return c;
  }
  async function commands() {
    const s = await fs.readFile(logFile, 'utf8');
    return s.trim() ? s.trim().split('\n').map(line => JSON.parse(line)) : [];
  }
  async function restart(signal = 'SIGTERM') {
    processBridge.kill(signal);
    await exited;
    start();
    await waitFor(() => stderr, s => s.includes('listening ws://'));
  }
  return {dir, sessions, uploads, connect, commands, restart,
    get processBridge() { return processBridge; }, get exited() { return exited; }, stderr: () => stderr};
}

