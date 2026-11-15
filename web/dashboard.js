'use strict';
// Loopback dashboard. Secret stays in fragment/Bearer header, never query/logs.
const token = location.hash.slice(1);
const state = document.querySelector('#state');
const input = document.querySelector('#input');
const output = document.querySelector('#output');
const hint = document.querySelector('#hint');
const details = document.querySelector('#details');
async function poll() {
  try {
    if (!token) throw new Error('missing-pairing');
    const response = await fetch('/status', {
      headers: {Authorization: `Bearer ${token}`}, cache: 'no-store', signal: AbortSignal.timeout(2500)
    });
    if (response.status === 401) throw new Error('stale-pairing');
    if (!response.ok) throw new Error('unavailable');
    const data = await response.json();
    state.textContent = data.receiving ? 'Receiving audio from your phone' :
      data.connected ? 'Phone connected — waiting for audio' : 'Receiver ready — waiting for your phone';
    input.value = data.level;
    output.value = data.playback_level;
    hint.textContent = data.receiving ? 'Now select CABLE Output in your Windows app and check its microphone meter.' :
      'On your phone, tap Start microphone (or scan the pairing QR if not paired).';
    details.textContent = `Packets: ${data.packets}\nBuffer: ${data.queued_ms} ms\nGaps: ${data.underruns}\nDropped samples: ${data.dropped_samples}\nDevice errors: ${data.callback_errors}`;
  } catch (error) {
    input.value = output.value = 0;
    details.textContent = '';
    state.textContent = error.message === 'stale-pairing' || error.message === 'missing-pairing' ?
      'Setup page is outdated — reopen .local/connect.html' : 'Receiver offline or unavailable';
    hint.textContent = 'Run ./run.sh serve on this PC, then reopen .local/connect.html. Leave the terminal open.';
  } finally {
    setTimeout(poll, 500);
  }
}
poll();
