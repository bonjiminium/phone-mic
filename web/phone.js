'use strict';
const startButton = document.querySelector('#start');
const stopButton = document.querySelector('#stop');
const status = document.querySelector('#status');
const stats = document.querySelector('#stats');
const level = document.querySelector('#level');
const token = location.hash.slice(1);
let current = null;

async function stop(message = 'Stopped') {
  const session = current;
  current = null;
  if (session) {
    clearTimeout(session.timeout);
    session.node?.disconnect();
    session.source?.disconnect();
    session.media?.getTracks().forEach(track => track.stop());
    session.ws?.close();
    await session.context?.close().catch(() => {});
    await session.wake?.release().catch(() => {});
  }
  startButton.disabled = false;
  stopButton.disabled = true;
  level.value = 0;
  status.textContent = message;
}

async function wake(session) {
  if (navigator.wakeLock && current === session && document.visibilityState === 'visible') {
    try {
      const lock = await navigator.wakeLock.request('screen');
      if (current !== session) await lock.release();
      else session.wake = lock;
    } catch { /* Keeping the screen awake manually remains an option. */ }
  }
}

startButton.onclick = async () => {
  if (current) return;
  const session = {};
  current = session;
  startButton.disabled = true;
  stopButton.disabled = false;
  stats.textContent = '';
  status.textContent = 'Requesting microphone…';
  try {
    if (!isSecureContext || !navigator.mediaDevices?.getUserMedia) {
      throw new Error('HTTPS is not trusted. Install the local CA certificate, then reopen this page.');
    }
    if (!token) throw new Error('Open the full phone URL, including the # connection token.');
    session.context = new AudioContext({sampleRate: 48000, latencyHint: 'interactive'});
    await session.context.resume();
    session.media = await navigator.mediaDevices.getUserMedia({audio: {
      channelCount: 1, sampleRate: 48000,
      echoCancellation: false, noiseSuppression: false, autoGainControl: false
    }});
    if (current !== session) {
      session.media.getTracks().forEach(track => track.stop());
      return;
    }
    if (session.context.sampleRate !== 48000) throw new Error('This browser cannot provide 48 kHz audio.');
    await session.context.audioWorklet.addModule('/capture.js');
    if (current !== session) return;
    status.textContent = 'Connecting to Windows…';
    const ws = new WebSocket(`wss://${location.host}/audio?token=${encodeURIComponent(token)}`);
    session.ws = ws;
    await new Promise((resolve, reject) => {
      session.timeout = setTimeout(() => reject(new Error('Connection timed out')), 10000);
      ws.onopen = () => ws.send(JSON.stringify({type: 'start', sampleRate: 48000, channels: 1, format: 'f32le'}));
      ws.onerror = () => reject(new Error('Cannot reach Windows receiver'));
      ws.onclose = () => reject(new Error('Connection refused or closed; another phone may be connected'));
      ws.onmessage = event => {
        if (JSON.parse(event.data).type === 'ready') {
          clearTimeout(session.timeout);
          resolve();
        }
      };
    });
    if (current !== session) return;
    let dropped = 0;
    ws.onclose = () => { if (current === session) stop('Disconnected — press Start to reconnect'); };
    ws.onerror = () => { if (current === session) stop('Network error — press Start to reconnect'); };
    ws.onmessage = event => {
      const data = JSON.parse(event.data);
      if (data.type === 'stats') {
        stats.textContent = `Buffer: ${data.queued_ms} ms\nAudio gaps: ${data.underruns}\nReceiver dropped: ${Math.round(data.dropped_samples / 48)} ms\nPhone dropped: ${dropped} packets\nDevice errors: ${data.callback_errors}`;
      }
    };
    session.source = session.context.createMediaStreamSource(session.media);
    session.node = new AudioWorkletNode(session.context, 'mic-capture');
    session.node.port.onmessage = event => {
      if (current !== session || ws.readyState !== WebSocket.OPEN) return;
      // Never queue unlimited delayed speech on a stalled network.
      if (ws.bufferedAmount > 3840 * 10) { dropped++; return; }
      ws.send(event.data);
      const samples = new Float32Array(event.data);
      let sum = 0;
      for (const sample of samples) sum += sample * sample;
      level.value = Math.min(1, Math.sqrt(sum / samples.length) * 4);
    };
    session.source.connect(session.node);
    // Worklet emits silence; this keeps capture processing active without feedback.
    session.node.connect(session.context.destination);
    session.media.getTracks()[0].onended = () => { if (current === session) stop('Microphone stopped'); };
    session.context.onstatechange = () => {
      if (current === session && session.context.state !== 'running') {
        stop('Audio paused by Android — keep the page visible and press Start again');
      }
    };
    await wake(session);
    if (current === session) status.textContent = 'Streaming to Windows';
  } catch (error) {
    if (current === session) await stop(error.message);
  }
};
stopButton.onclick = () => stop();
window.addEventListener('pagehide', () => stop());
document.addEventListener('visibilitychange', () => {
  if (current && document.visibilityState === 'visible') wake(current);
});
