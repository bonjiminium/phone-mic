import unittest
import numpy as np

from audio_buffer import AudioBuffer, decode_packet, meter_level, PACKET_BYTES, PACKET_SAMPLES


class BufferTest(unittest.TestCase):
    def test_display_level_uses_decibels_without_changing_audio(self):
        for rms, expected in [(0, 0), (.0001, 0), (.001, 0), (.01, 1/3),
                              (.1, 2/3), (1, 1), (2, 1), (float('nan'), 0),
                              (float('inf'), 0)]:
            self.assertAlmostEqual(meter_level(rms), expected)

    def test_requires_prebuffer(self):
        b = AudioBuffer(prebuffer=6, capacity=12)
        b.push(np.ones(3))
        np.testing.assert_array_equal(b.read(2), [0, 0])
        b.push(np.ones(3))
        np.testing.assert_array_equal(b.read(2), [1, 1])
        self.assertEqual(b.stats()['queued_ms'], round(4 / 48, 1))

    def test_underrun_zero_fills_then_reprimes(self):
        b = AudioBuffer(prebuffer=3)
        b.push(np.ones(3))
        np.testing.assert_array_equal(b.read(5), [1, 1, 1, 0, 0])
        self.assertEqual(b.stats()['underruns'], 1)
        b.push(np.ones(2))
        np.testing.assert_array_equal(b.read(2), [0, 0])
        b.push(np.ones(1))
        np.testing.assert_array_equal(b.read(2), [1, 1])

    def test_overflow_discards_oldest_and_bounds_memory(self):
        b = AudioBuffer(prebuffer=1, capacity=6)
        b.push(np.arange(4))
        b.push(np.arange(4, 10))
        self.assertEqual(b.size, 6)
        self.assertEqual(b.stats()['dropped_samples'], 4)
        np.testing.assert_array_equal(b.read(6), np.arange(4, 10))

    def test_reset_discards_stale_audio(self):
        b = AudioBuffer(prebuffer=1)
        b.push(np.ones(960))
        b.reset()
        np.testing.assert_array_equal(b.read(10), np.zeros(10))
        self.assertEqual(b.stats()['packets'], 0)

    def test_decode_requires_fixed_packet_size(self):
        with self.assertRaises(ValueError):
            decode_packet(b'bad')

    def test_decode_rejects_nan_and_infinity(self):
        for bad in (float('nan'), float('inf')):
            with self.assertRaises(ValueError):
                decode_packet(np.full(PACKET_SAMPLES, bad, dtype='<f4').tobytes())

    def test_decode_is_little_endian_and_clips(self):
        samples = np.full(PACKET_SAMPLES, 2.0, dtype='<f4')
        self.assertEqual(len(samples.tobytes()), PACKET_BYTES)
        np.testing.assert_array_equal(decode_packet(samples.tobytes()), np.ones(PACKET_SAMPLES))


if __name__ == '__main__':
    unittest.main()
