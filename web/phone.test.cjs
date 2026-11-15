const {test} = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const source = fs.readFileSync(`${__dirname}/phone.js`, 'utf8');
async function start(options = {}) {
  const elements = Object.fromEntries(['#start', '#stop', '#status', '#stats', '#level', '#android'].map(id => [id, {}]));
  const denied = new Error('sensitive browser internal detail');
  denied.name = 'NotAllowedError';
  let closed = false;
  class AudioContext {
    async resume() {}
    async close() { closed = true; }
  }
  const context = {document: {querySelector: id => elements[id], addEventListener() {}},
    location: {hash: options.hash ?? '#secret', host: '192.168.1.2:8765'},
    isSecureContext: options.secure ?? true,
    navigator: {mediaDevices: {getUserMedia: async () => { throw denied; }}},
    window: {addEventListener() {}}, AudioContext, clearTimeout() {}};
  vm.runInNewContext(source, context);
  await elements['#start'].onclick();
  return {elements, closed, context};
}
test('meter uses the same decibel scale as Android and PC without boosting audio', async () => {
  const {context} = await start();
  for (const [rms, expected] of [[0, 0], [.0001, 0], [.001, 0], [.01, 1/3], [.1, 2/3], [1, 1], [2, 1], [NaN, 0], [Infinity, 0]]) {
    assert.ok(Math.abs(context.meterLevel(rms) - expected) < .00001);
  }
});

test('untrusted browser context recommends native app, not CA installation', async () => {
  const {elements} = await start({secure: false});
  assert.match(elements['#status'].textContent, /Android app/);
  assert.match(elements['#status'].textContent, /no CA installation/);
  assert.equal(elements['#start'].disabled, false);
});
test('incomplete browser link points to current setup page', async () => {
  const {elements} = await start({hash: ''});
  assert.match(elements['#status'].textContent, /link is incomplete/);
  assert.match(elements['#status'].textContent, /connect.html/);
});
test('permission denial explains recovery and releases the audio context', async () => {
  const {elements, closed} = await start();
  assert.match(elements['#status'].textContent, /site settings/);
  assert.doesNotMatch(elements['#status'].textContent, /sensitive/);
  assert.equal(elements['#stop'].disabled, true);
  assert.equal(elements['#level'].value, 0);
  assert.equal(closed, true);
});
