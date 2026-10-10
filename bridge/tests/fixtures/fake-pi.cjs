// Test-only Pi RPC stand-in. The harness copies this behind a Node shebang into
// a private temporary directory; it never imports Pi or reads real sessions.
const fs = require('node:fs');
const logFile = process.env.PI_BRIDGE_TEST_LOG;
let buffer = '';
let name = '';
const dialogs = new Map();
function output(record) { process.stdout.write(JSON.stringify(record) + '\n'); }
function success(record, data) {
  output({type: 'response', id: record.id, command: record.type, success: true, data});
}
function handle(record) {
  fs.appendFileSync(logFile, JSON.stringify(record) + '\n');
  if (record.type === 'extension_ui_response') {
    const origin = dialogs.get(record.id);
    if (origin) { dialogs.delete(record.id); success(origin, {disposition: 'handled'}); }
    return; // Official UI responses do not have command responses.
  }
  if (record.type === 'prompt' && record.message === '/fixture-ui') {
    dialogs.set('fixture-ui-id', record);
    output({type: 'extension_ui_request', id: 'fixture-ui-id', method: 'confirm', title: 'Synthetic dialog'});
    return;
  }
  if (record.type === 'prompt' && record.message === '/fixture-exit') {
    process.stdout.write('{"oldPartial":');
    process.stderr.write('synthetic old stderr\n');
    setTimeout(() => process.exit(23), 10);
    return;
  }
  if (record.type === 'prompt' && record.message === '/fixture-unmatched') {
    output({type: 'response', id: 'unknown-old-id', command: 'get_messages', success: true, data: {tag: 'stale'}});
    output({type: 'response', command: 'get_messages', success: true, data: {tag: 'missing-id'}});
    success(record, {disposition: 'handled'});
    return;
  }
  if (record.type === 'set_session_name') {
    if (!record.name || !record.name.trim()) {
      output({type: 'response', id: record.id, command: record.type, success: false, error: 'Synthetic invalid name'});
    } else {
      name = record.name.trim();
      output({type: 'session_info_changed', name});
      success(record);
    }
    return;
  }
  if (['switch_session', 'new_session', 'fork', 'clone'].includes(record.type)) {
    if (!record.fixtureCancel) name = record.fixtureName || '';
    success(record, {cancelled: !!record.fixtureCancel});
    return;
  }
  const respond = () => {
    if (record.type === 'get_state') success(record, {
      ...(name ? {sessionName: name} : {}), fixtureArgs: process.argv.slice(2),
    });
    else if (record.type === 'get_messages') success(record, {tag: record.fixtureTag || '', messages: []});
    else success(record, {});
  };
  if (record.fixtureDelay) setTimeout(respond, record.fixtureDelay);
  else respond();
}
process.stdin.setEncoding('utf8');
process.stdin.on('data', chunk => {
  buffer += chunk;
  let nl;
  while ((nl = buffer.indexOf('\n')) !== -1) {
    const line = buffer.slice(0, nl).replace(/\r$/, '');
    buffer = buffer.slice(nl + 1);
    if (line) handle(JSON.parse(line));
  }
});
process.stdin.on('end', () => process.exit(0));
