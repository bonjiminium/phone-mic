import hashlib
import json
from pathlib import Path
import ssl
import tempfile
import unittest
from unittest.mock import patch
from urllib.parse import parse_qs, urlsplit

from connection_page import write_page
from pairing import pairing_payload


class ConnectionPageTest(unittest.TestCase):
    def test_pairing_schema_pins_exact_leaf_not_ca(self):
        der = b'public certificate fixture'
        payload = pairing_payload('192.168.1.237', 8765, 'a' * 32, ssl.DER_cert_to_PEM_cert(der))
        self.assertEqual(payload, dict(version=1, serverUrl='https://192.168.1.237:8765',
                                       token='a' * 32, certificateSha256=hashlib.sha256(der).hexdigest()))

    def test_private_setup_page_has_separate_install_and_in_app_pairing_qrs(self):
        payload = dict(version=1, serverUrl='https://192.168.1.237:8765',
                       token='a' * 32, certificateSha256='ab' * 32)
        with tempfile.TemporaryDirectory() as folder, patch('connection_page.qr_svg', return_value='<svg/>') as qr:
            target = Path(folder) / 'connect.html'
            write_page(target, 'http://192.168.1.237:8764/phone-mic-ca.crt',
                       'https://192.168.1.237:8765/#' + payload['token'], 'CA-FINGERPRINT', payload)
            self.assertEqual(qr.call_count, 2)
            download = urlsplit(qr.call_args_list[0].args[0])
            self.assertEqual(download.scheme, 'https')
            self.assertEqual(download.path, '/android.apk')
            self.assertEqual(parse_qs(download.query)['token'], [payload['token']])
            self.assertEqual(json.loads(qr.call_args_list[1].args[0]), payload)
            html = target.read_text(encoding='utf-8')
            self.assertIn('No CA installation is needed', html)
            self.assertIn('Scan pairing QR', html)
            self.assertIn(payload['certificateSha256'], html)
            self.assertNotIn('PRIVATE KEY', html)
