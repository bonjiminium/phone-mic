"""Bounded mono 48 kHz float32 jitter buffer shared by WS and audio threads."""
from collections import deque
from threading import Lock

import numpy as np

RATE = 48000
PACKET_SAMPLES = 960  # 20 ms
PACKET_BYTES = PACKET_SAMPLES * 4


def decode_packet(data):
    if len(data) != PACKET_BYTES:
        raise ValueError(f'Expected {PACKET_BYTES} bytes of mono f32le audio')
    samples = np.frombuffer(data, dtype='<f4')
    if not np.isfinite(samples).all():
        raise ValueError('Audio contains non-finite samples')
    return np.clip(samples, -1, 1)


class AudioBuffer:
    def __init__(self, prebuffer=2880, capacity=9600):
        self.prebuffer = prebuffer  # 60 ms
        self.capacity = capacity  # 200 ms; drop OLD audio rather than grow delay
        self.lock = Lock()
        self._clear()

    def _clear(self):
        self.chunks = deque()
        self.size = 0
        self.primed = False
        self.packets = 0
        self.underruns = 0
        self.dropped = 0
        self.callback_errors = 0

    def reset(self):
        with self.lock:
            self._clear()

    def push(self, samples):
        with self.lock:
            self.chunks.append(samples.copy())
            self.size += len(samples)
            self.packets += 1
            while self.size > self.capacity:
                n = min(self.size - self.capacity, len(self.chunks[0]))
                self._consume(n)
                self.dropped += n

    def _consume(self, n):
        if n == len(self.chunks[0]):
            self.chunks.popleft()
        else:
            self.chunks[0] = self.chunks[0][n:]
        self.size -= n

    def read(self, n, status=False):
        out = np.zeros(n, dtype='float32')
        with self.lock:
            if status:
                self.callback_errors += 1
            if not self.primed:
                if self.size < self.prebuffer:
                    return out
                self.primed = True
            pos = 0
            while pos < n and self.chunks:
                count = min(n - pos, len(self.chunks[0]))
                out[pos:pos + count] = self.chunks[0][:count]
                self._consume(count)
                pos += count
            if pos < n:
                self.underruns += 1
                self.primed = False
        return out

    def stats(self):
        with self.lock:
            return dict(packets=self.packets, queued_ms=round(self.size / 48, 1),
                        underruns=self.underruns, dropped_samples=self.dropped,
                        callback_errors=self.callback_errors)
