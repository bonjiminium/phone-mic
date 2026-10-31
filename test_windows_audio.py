import unittest

import numpy as np
from windows_audio import RATE, tone, tone_amplitude


class ToneDetectionTest(unittest.TestCase):
    def test_detects_expected_tone(self):
        self.assertAlmostEqual(tone_amplitude(tone(1)), 0.1, places=4)

    def test_rejects_silence(self):
        self.assertEqual(tone_amplitude(np.zeros(RATE)), 0)

    def test_rejects_unrelated_tone(self):
        self.assertLess(tone_amplitude(tone(1, frequency=440)), 0.001)

    def test_accepts_arbitrary_phase(self):
        self.assertAlmostEqual(tone_amplitude(tone(2)[123:123 + RATE]), 0.1, places=4)


if __name__ == '__main__':
    unittest.main()
