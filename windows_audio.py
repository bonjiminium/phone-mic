"""First spike: prove Windows playback -> virtual cable -> microphone capture.

Run with Windows Python, not WSL Python. No default devices are changed.
"""
import argparse
import sys
import time

import numpy as np
import sounddevice as sd

RATE = 48000
FREQUENCY = 997


def tone(seconds, frequency=FREQUENCY):
    t = np.arange(round(RATE * seconds)) / RATE
    return (0.1 * np.sin(2 * np.pi * frequency * t)).astype('float32')


def tone_amplitude(samples, frequency=FREQUENCY):
    """Measure a tone, independent of its phase; ignore stream startup."""
    x = np.asarray(samples).reshape(-1)
    t = np.arange(len(x)) / RATE
    window = np.hanning(len(x))
    return float(2 * abs(np.sum(x * window * np.exp(-2j * np.pi * frequency * t))) / window.sum())


def cable_device(name, direction):
    devices = sd.query_devices()
    # WASAPI avoids duplicate MME/DirectSound aliases and extra conversion.
    matches = [i for i, d in enumerate(devices)
               if name.lower() in d['name'].lower()
               and d['max_' + direction + '_channels'] > 0
               and sd.query_hostapis(d['hostapi'])['name'] == 'Windows WASAPI']
    if len(matches) != 1:
        raise RuntimeError(f'Expected one WASAPI {name} endpoint; found {len(matches)}. '
                           'Install VB-CABLE, reboot if requested, then run --list.')
    return matches[0]


def check():
    playback = cable_device('CABLE Input', 'output')
    capture = cable_device('CABLE Output', 'input')
    print(f'Playback: {sd.query_devices(playback)["name"]}')
    print(f'Capture:  {sd.query_devices(capture)["name"]}')
    frames = []
    problems = []

    def receive(data, frame_count, timing, status):
        if status:
            problems.append(str(status))
        frames.append(data.copy())

    # Start capture first, send tone, then allow the cable buffer to drain.
    with sd.InputStream(device=capture, channels=2, samplerate=RATE,
                        dtype='float32', callback=receive):
        time.sleep(0.3)
        signal = np.repeat(tone(3)[:, None], 2, axis=1)
        sd.play(signal, samplerate=RATE, device=playback, blocking=True)
        time.sleep(0.3)
    if not frames:
        raise RuntimeError('No audio frames captured.')
    recorded = np.concatenate(frames)[:, 0]
    # Scan for the strongest one-second region, ignoring initial silence.
    amplitude = max(tone_amplitude(recorded[start:start + RATE])
                    for start in range(0, len(recorded) - RATE + 1, RATE // 4))
    print(f'Received {FREQUENCY} Hz amplitude: {amplitude:.4f} (sent 0.1000)')
    if problems:
        raise RuntimeError('Audio stream errors: ' + '; '.join(problems))
    if amplitude < 0.02:
        raise RuntimeError('FAIL: expected tone not detected through the virtual microphone.')
    print('PASS: generated audio reached the Windows microphone endpoint.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--list', action='store_true', help='List Windows audio endpoints')
    args = parser.parse_args()
    if args.list:
        print(sd.query_devices())
    else:
        check()


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, sd.PortAudioError) as exc:
        print(str(exc), file=sys.stderr)
        sys.exit(1)
