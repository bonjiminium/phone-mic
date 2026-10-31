const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

function processor() {
  const packets = [];
  let Capture;
  const context = {
    AudioWorkletProcessor: class { constructor() { this.port = {postMessage: data => packets.push(data)}; } },
    registerProcessor: (name, ctor) => { assert.equal(name, 'mic-capture'); Capture = ctor; },
    Float32Array, ArrayBuffer, DataView,
  };
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, 'capture.js'), 'utf8'), context);
  return {capture: new Capture(), packets};
}

test('worklet emits 20 ms little-endian packets across 128-frame render quanta', () => {
  const {capture, packets} = processor();
  for (let block = 0; block < 15; block++) {
    const mono = Float32Array.from({length: 128}, (_, i) => (block * 128 + i) / 1920);
    assert.equal(capture.process([[mono]]), true);
  }
  assert.equal(packets.length, 2);
  for (let packet = 0; packet < 2; packet++) {
    assert.equal(packets[packet].byteLength, 3840);
    const view = new DataView(packets[packet]);
    for (let i = 0; i < 960; i++) {
      assert.ok(Math.abs(view.getFloat32(i * 4, true) - (packet * 960 + i) / 1920) < 1e-6);
    }
  }
});

test('stereo input is downmixed; absent input produces no packets', () => {
  const {capture, packets} = processor();
  capture.process([[]]);
  assert.equal(packets.length, 0);
  capture.process([[new Float32Array(960).fill(.8), new Float32Array(960).fill(.2)]]);
  assert.equal(packets.length, 1);
  assert.equal(new DataView(packets[0]).getFloat32(0, true), .5);
});
