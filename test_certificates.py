import time
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch

from certificates import ensure_certificate, needs_renewal


@unittest.skipUnless(shutil.which('openssl'), 'Requires OpenSSL')
class CertificateTests(unittest.TestCase):
    def test_create_reuse_and_address_change_preserve_ca_key_and_token(self):
        with tempfile.TemporaryDirectory() as folder:
            local = Path(folder)
            (local / 'pairing.json').write_text('unchanged')
            self.assertIn('created', ensure_certificate(local, '192.168.1.2').lower())
            names = ('ca.key', 'phone-mic-ca.crt', 'server.key', 'server.crt', 'pairing.json')
            before = {n: (local / n).read_bytes() for n in names}
            self.assertEqual(ensure_certificate(local, '192.168.1.2'), '')
            self.assertIn('address', ensure_certificate(local, '192.168.1.3').lower())
            for name in names:
                if name != 'server.crt':
                    self.assertEqual((local / name).read_bytes(), before[name])
            self.assertNotEqual((local / 'server.crt').read_bytes(), before['server.crt'])

    def test_expired_renews_without_changing_ca(self):
        with tempfile.TemporaryDirectory() as folder:
            local = Path(folder)
            ensure_certificate(local, '192.168.1.2')
            ca = (local / 'ca.key').read_bytes()
            with patch('certificates.time.time', return_value=time.time() + 4 * 366 * 86400):
                self.assertEqual(needs_renewal(local / 'server.crt', '192.168.1.2'), 'expired certificate')
                self.assertIn('expired', ensure_certificate(local, '192.168.1.2'))
            self.assertEqual((local / 'ca.key').read_bytes(), ca)

    def test_missing_key_on_valid_certificate_does_not_silently_repair(self):
        with tempfile.TemporaryDirectory() as folder:
            local = Path(folder)
            ensure_certificate(local, '192.168.1.2')
            cert = (local / 'server.crt').read_bytes()
            (local / 'server.key').unlink()
            with self.assertRaises(OSError):
                ensure_certificate(local, '192.168.1.2')
            self.assertEqual((local / 'server.crt').read_bytes(), cert)

    def test_incomplete_ca_fails_without_overwriting(self):
        with tempfile.TemporaryDirectory() as folder:
            local = Path(folder)
            (local / 'ca.key').write_text('do not replace')
            with self.assertRaisesRegex(ValueError, 'incomplete'):
                ensure_certificate(local, '192.168.1.2')
            self.assertEqual((local / 'ca.key').read_text(), 'do not replace')


if __name__ == '__main__':
    unittest.main()
