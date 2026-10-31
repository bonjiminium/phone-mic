import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from pairing import pairing_token


class PairingTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.root = Path(self.folder.name)

    def write(self, name, data):
        (self.root / name).write_text(json.dumps(data), encoding='utf-8')

    def test_first_start_generates_and_persists_secret(self):
        token = pairing_token(self.root)
        self.assertRegex(token, r'^[A-Za-z0-9_-]{32}$')
        self.assertEqual(pairing_token(self.root), token)
        self.assertEqual(json.loads((self.root / 'pairing.json').read_text())['token'], token)

    def test_upgrade_adopts_current_apk_token(self):
        old = 'a' * 32
        self.write('connection.json', {'token': old, 'host': '192.168.1.237', 'port': 8765})
        self.assertEqual(pairing_token(self.root), old)
        (self.root / 'connection.json').unlink()
        self.assertEqual(pairing_token(self.root), old)

    def test_saved_pairing_takes_priority_over_stale_connection_metadata(self):
        token = pairing_token(self.root)
        self.write('connection.json', {'token': 'b' * 32})
        self.assertEqual(pairing_token(self.root), token)

    def test_explicit_reset_invalidates_old_token_and_remains_persistent(self):
        old = pairing_token(self.root)
        new = pairing_token(self.root, reset=True)
        self.assertNotEqual(old, new)
        self.assertEqual(pairing_token(self.root), new)

    def test_corrupt_pairing_fails_closed_without_replacing_it(self):
        for bad in ('not json', '[]', '{"version":2,"token":"' + 'a' * 32 + '"}',
                    '{"version":1,"token":"short"}'):
            with self.subTest(bad=bad):
                path = self.root / 'pairing.json'
                path.write_text(bad)
                with self.assertRaisesRegex(ValueError, '--reset-pairing'):
                    pairing_token(self.root)
                self.assertEqual(path.read_text(), bad)

    def test_corrupt_legacy_data_requires_explicit_reset(self):
        self.write('connection.json', {'token': 'invalid'})
        with self.assertRaises(ValueError):
            pairing_token(self.root)
        token = pairing_token(self.root, reset=True)
        self.assertEqual(pairing_token(self.root), token)

    def test_failed_atomic_reset_preserves_previous_pairing(self):
        old = pairing_token(self.root)
        with patch('pairing.os.replace', side_effect=OSError('simulated disk error')):
            with self.assertRaises(OSError):
                pairing_token(self.root, reset=True)
        self.assertEqual(pairing_token(self.root), old)
        self.assertEqual(list(self.root.glob('.pairing-*.tmp')), [])
