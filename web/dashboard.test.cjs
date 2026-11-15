const {test} = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const source = fs.readFileSync(`${__dirname}/dashboard.js`, 'utf8');
async function render(data, status = 200, hash = '#test-secret') {
  const elements = Object.fromEntries(['#state', '#input', '#output', '#hint', '#details'].map(id => [id, {}]));
  let request;
  const context = {location: {hash}, document: {querySelector: id => elements[id]},
    AbortSignal, setTimeout() {}, fetch: async (url, options) => {
      request = {url, options};
      if (status === 0) throw new Error('network');
      return {ok: status === 200, status, json: async () => data};
    }};
  vm.runInNewContext(source, context);
  await new Promise(resolve => setImmediate(resolve));
  return {elements, request};
}
test('dashboard shows waiting, receiving, correct meter and app instruction', async () => {
  let result = await render({connected: false});
  assert.match(result.elements['#state'].textContent, /waiting for your phone/);
  result = await render({connected: true, receiving: true, level: .5, playback_level: .4});
  assert.match(result.elements['#state'].textContent, /Receiving audio/);
  assert.equal(result.elements['#input'].value, .5);
  assert.equal(result.elements['#output'].value, .4);
  assert.match(result.elements['#hint'].textContent, /Windows app/);
  assert.equal(result.request.url, '/status');
  assert.equal(result.request.options.headers.Authorization, 'Bearer test-secret');
});
test('offline and outdated pairing never leave stale live meters', async () => {
  for (const status of [0, 401]) {
    const {elements} = await render(null, status);
    assert.equal(elements['#input'].value, 0);
    assert.equal(elements['#output'].value, 0);
    assert.match(elements['#state'].textContent, status === 401 ? /outdated/ : /offline/);
    assert.match(elements['#hint'].textContent, /run.sh serve/);
  }
});
