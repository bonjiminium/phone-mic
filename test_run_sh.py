"""Launcher tests with mocked Windows commands; run with WSL/Linux Python."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


@unittest.skipUnless(os.name == 'posix' and shutil.which('bash'), 'Requires Bash on POSIX')
class LauncherTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        bin_dir = Path(self.temp.name)
        scripts = {
            'wslpath': '#!/bin/sh\nprintf "C:\\\\phone-mic\\n"\n',
            'python3': '#!/bin/sh\nexit "${TEST_CERT_EXIT:-0}"\n',
            'powershell.exe': '''#!/bin/sh
case "$*" in
  *lan-addresses.ps1*)
    printf '%s' "$TEST_ADDRESSES"
    exit "${TEST_DETECT_EXIT:-0}" ;;
  *) printf 'LAUNCH: %s\\n' "$*" ;;
esac
''',
        }
        for name, content in scripts.items():
            path = bin_dir / name
            path.write_text(content)
            path.chmod(0o755)
        self.env = dict(os.environ, PATH=f'{bin_dir}:{os.environ["PATH"]}',
                        TEST_ADDRESSES='192.168.1.20|Wi-Fi\r\n')
        self.script = str(Path(__file__).with_name('run.sh'))

    def run_launcher(self, *args):
        return subprocess.run(['bash', self.script, *args], env=self.env,
                              stdin=subprocess.DEVNULL, capture_output=True, text=True)

    def test_help_has_no_windows_side_effects(self):
        for args in [(), ('help',), ('--help',)]:
            result = self.run_launcher(*args)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn('./run.sh serve', result.stdout)
            self.assertIn('.local/connect.html', result.stdout)
            self.assertNotIn('LAUNCH:', result.stdout)

    def test_auto_detect(self):
        result = self.run_launcher('serve')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('receiver.py --host 192.168.1.20 --open-page', result.stdout)

    def test_explicit_override_skips_detection(self):
        self.env['TEST_DETECT_EXIT'] = '1'
        result = self.run_launcher('serve', '10.0.0.4', '--reuse-session')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('receiver.py --host 10.0.0.4 --open-page --reuse-session', result.stdout)

    def test_flag_without_ip(self):
        result = self.run_launcher('serve', '--reset-pairing')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('receiver.py --host 192.168.1.20 --open-page --reset-pairing', result.stdout)

    def test_no_candidates(self):
        self.env['TEST_ADDRESSES'] = '\r\n'
        result = self.run_launcher('serve')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('No Windows LAN address', result.stderr)
        self.assertNotIn('LAUNCH:', result.stdout)

    def test_certificate_failure_does_not_launch_receiver(self):
        self.env['TEST_CERT_EXIT'] = '1'
        result = self.run_launcher('serve')
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn('LAUNCH:', result.stdout)

    def test_doctor_shows_copyable_firewall_command_without_changing_rules(self):
        result = self.run_launcher('doctor')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('allow-firewall.ps1" -Ip 192.168.1.20', result.stdout)
        self.assertIn('receiver.py --doctor', result.stdout)

    def test_detection_failure(self):
        self.env['TEST_DETECT_EXIT'] = '1'
        result = self.run_launcher('serve')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('LAN detection failed', result.stderr)

    def test_multiple_candidates_noninteractive(self):
        self.env['TEST_ADDRESSES'] += '10.0.0.4|Ethernet\r\n'
        result = self.run_launcher('serve')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('192.168.1.20|Wi-Fi', result.stderr)
        self.assertIn('10.0.0.4|Ethernet', result.stderr)
        self.assertNotIn('LAUNCH:', result.stdout)

    def test_interactive_choice(self):
        import pty
        self.env['TEST_ADDRESSES'] += '10.0.0.4|Ethernet\r\n'
        master, slave = pty.openpty()
        try:
            process = subprocess.Popen(['bash', self.script, 'serve'], env=self.env,
                                       stdin=slave, stdout=subprocess.PIPE,
                                       stderr=subprocess.PIPE, text=True)
            try:
                os.write(master, b'2\n')
                stdout, stderr = process.communicate(timeout=10)
                self.assertEqual(process.returncode, 0, stderr)
                self.assertIn('receiver.py --host 10.0.0.4', stdout)
            finally:
                if process.poll() is None:
                    process.kill()
                    process.communicate()
        finally:
            os.close(master)
            os.close(slave)

    def test_invalid_arguments(self):
        for args in [('serve', 'garbage'), ('serve', '--unknown'),
                     ('serve', '10.0.0.4', '--reuse-session', 'extra')]:
            with self.subTest(args=args):
                result = self.run_launcher(*args)
                self.assertNotEqual(result.returncode, 0)
                self.assertNotIn('LAUNCH:', result.stdout)


if __name__ == '__main__':
    unittest.main()
