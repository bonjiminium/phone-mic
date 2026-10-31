"""Simulate a phone: real TLS/WebSocket -> receiver -> VB-CABLE -> capture.

No recording files are written. Requires a running receiver. Default 8 seconds.
"""
import argparse
import asyncio
import json
import ssl
from pathlib import Path

import aiohttp
import numpy as np
import sounddevice as sd

from windows_audio import RATE, FREQUENCY, cable_device, tone, tone_amplitude

ROOT = Path(__file__).resolve().parent


async def check(seconds):
    info = json.loads((ROOT / '.local' / 'connection.json').read_text())
    context = ssl.create_default_context(cafile=str(ROOT / '.local' / 'phone-mic-ca.crt'))
    base = f'https://{info["host"]}:{info["port"]}'
    capture = cable_device('CABLE Output', 'input')
    chunks, errors = [], []

    def record(data, frames, timing, status):
        chunks.append(data.copy())
        if status:
            errors.append(str(status))

    connector = aiohttp.TCPConnector(ssl=context)
    async with aiohttp.ClientSession(connector=connector) as client:
        async with client.get(base + '/status') as response:
            assert response.status == 401, 'Receiver must reject unauthenticated requests'
        async with client.get(f'http://{info["host"]}:{info["port"] - 1}/phone-mic-ca.crt') as response:
            assert response.status == 200
            assert await response.read() == (ROOT / '.local' / 'phone-mic-ca.crt').read_bytes()
        with sd.InputStream(device=capture, samplerate=RATE, channels=2,
                            dtype='float32', callback=record):
            async with client.ws_connect(base + '/audio?token=' + info['token']) as ws:
                await ws.send_json(dict(type='start', sampleRate=RATE, channels=1, format='f32le'))
                assert (await ws.receive_json())['type'] == 'ready'
                samples = tone(seconds)
                loop = asyncio.get_running_loop()
                start = loop.time()
                for i in range(len(samples) // 960):
                    await asyncio.sleep(max(0, start + i * .020 - loop.time()))
                    await ws.send_bytes(samples[i * 960:(i + 1) * 960].astype('<f4').tobytes())
                async with client.get(base + '/status', headers={'Authorization': 'Bearer ' + info['token']}) as response:
                    assert response.status == 200
                    stats = await response.json()
                await asyncio.sleep(.15)
    recorded = np.concatenate(chunks)[:, 0]
    amplitudes = [tone_amplitude(recorded[i:i + RATE])
                  for i in range(0, len(recorded) - RATE + 1, RATE // 2)]
    peak = max(amplitudes, default=0)
    strong_windows = sum(value > .02 for value in amplitudes)
    print(f'Captured {FREQUENCY} Hz peak amplitude: {peak:.4f}; strong windows: {strong_windows}/{len(amplitudes)}')
    print(f'Receiver metrics: {stats}')
    if errors:
        raise RuntimeError(f'Capture errors: {errors}')
    if stats['callback_errors']:
        raise RuntimeError('Playback callback reported device errors')
    if peak < .02 or strong_windows < len(amplitudes) * .8:
        raise RuntimeError('FAIL: expected continuous streamed tone not captured')
    print('PASS: real HTTPS/WebSocket audio reached the Windows microphone endpoint.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--seconds', type=int, default=8)
    args = parser.parse_args()
    if not 3 <= args.seconds <= 600:
        parser.error('--seconds must be between 3 and 600')
    asyncio.run(check(args.seconds))
