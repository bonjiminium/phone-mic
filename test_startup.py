from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import MagicMock, patch

from startup import preflight


class StartupTest(unittest.TestCase):
    def info(self, expires=None, host='192.168.1.2'):
        return ({'subjectAltName': (('IP Address', host),)}, expires or int(time.time() * 1000 + 100000))

    def test_expired_and_wrong_address_fail_before_audio(self):
        check = MagicMock()
        for info, message in [(self.info(1), 'expired'), (self.info(host='10.0.0.1'), 'does not match')]:
            with patch('startup.certificate_info', return_value=info):
                with self.assertRaisesRegex(ValueError, message):
                    preflight(Path('.'), '192.168.1.2', 8765, check)
        check.assert_not_called()

    def test_port_failure_actionable_and_socket_closed(self):
        sock = MagicMock()
        sock.bind.side_effect = OSError('in use')
        with patch('startup.certificate_info', return_value=self.info()), \
                patch('startup.ssl.SSLContext'), patch('startup.socket.socket', return_value=sock):
            with self.assertRaisesRegex(ValueError, 'another receiver'):
                preflight(Path('.'), '192.168.1.2', 8765, MagicMock())
        sock.close.assert_called_once()

    def test_checks_both_cable_endpoints_and_closes_ports(self):
        sock = MagicMock()
        check = MagicMock()
        with patch('startup.certificate_info', return_value=self.info()), \
                patch('startup.ssl.SSLContext'), patch('startup.socket.socket', return_value=sock):
            preflight(Path('.'), '192.168.1.2', 8765, check)
        self.assertEqual(check.call_count, 2)
        self.assertEqual(sock.close.call_count, 3)
        self.assertIn(('127.0.0.1', 8766), [call.args[0] for call in sock.bind.call_args_list])
