"""WSL startup certificate maintenance. Never replace an existing CA or token."""
import argparse
import ipaddress
import os
from pathlib import Path
import ssl
import subprocess
import tempfile
import time


def openssl(*args):
    return subprocess.run(['openssl', *map(str, args)], check=True, capture_output=True,
                          text=True).stdout


def needs_renewal(certificate, host):
    if not certificate.exists():
        return 'missing certificate'
    info = ssl._ssl._test_decode_cert(str(certificate))
    if ssl.cert_time_to_seconds(info['notAfter']) <= time.time():
        return 'expired certificate'
    if ssl.cert_time_to_seconds(info['notBefore']) > time.time():
        raise ValueError('Certificate is not valid yet. Check the PC clock.')
    if ('IP Address', host) not in info.get('subjectAltName', ()):
        return 'changed network address'
    return ''


def ensure_certificate(local, host):
    ipaddress.IPv4Address(host)
    local = Path(local).resolve()
    local.mkdir(mode=0o700, parents=True, exist_ok=True)
    ca_key, ca_cert = local / 'ca.key', local / 'phone-mic-ca.crt'
    if ca_key.exists() != ca_cert.exists() or (not ca_key.exists() and (local / 'server.crt').exists()):
        raise ValueError('Local CA is incomplete or missing. Restore ca.key and phone-mic-ca.crt from backup; nothing was replaced.')
    reason = needs_renewal(local / 'server.crt', host)
    if not reason:
        # Detect a missing/mismatched key before launching the receiver.
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(local / 'server.crt', local / 'server.key')
        return ''
    with tempfile.TemporaryDirectory(prefix='.certificate-', dir=local) as folder:
        work = Path(folder)
        if not ca_key.exists():
            openssl('req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-sha256', '-days', '3650',
                    '-keyout', work / 'ca.key', '-out', work / 'ca.crt',
                    '-subj', '/CN=Phone Mic Local CA',
                    '-addext', 'basicConstraints=critical,CA:TRUE,pathlen:0',
                    '-addext', 'keyUsage=critical,keyCertSign,cRLSign')
            os.chmod(work / 'ca.key', 0o600)
            os.replace(work / 'ca.key', ca_key)
            os.replace(work / 'ca.crt', ca_cert)
        key = local / 'server.key'
        new_key = not key.exists()
        if new_key:
            key = work / 'server.key'
            openssl('genpkey', '-algorithm', 'RSA', '-pkeyopt', 'rsa_keygen_bits:2048', '-out', key)
            os.chmod(key, 0o600)
        openssl('req', '-new', '-key', key, '-out', work / 'server.csr', '-subj', '/CN=Phone Mic')
        extensions = work / 'server.ext'
        extensions.write_text(f'subjectAltName=IP:{host},IP:127.0.0.1,DNS:localhost\n'
                              'basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\n'
                              'extendedKeyUsage=serverAuth\n')
        openssl('x509', '-req', '-in', work / 'server.csr', '-CA', ca_cert,
                '-CAkey', ca_key, '-set_serial', '0x' + os.urandom(16).hex(),
                '-out', work / 'server.crt', '-days', '1095', '-sha256', '-extfile', extensions)
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(work / 'server.crt', key)
        if new_key:
            os.replace(key, local / 'server.key')
        os.replace(work / 'server.crt', local / 'server.crt')
    verb = 'Created' if reason == 'missing certificate' else 'Renewed'
    return f'{verb} server certificate ({reason}); valid for 3 years. Scan the updated pairing QR on your phone. Existing CA and pairing secret preserved.'


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('host')
    args = parser.parse_args()
    try:
        message = ensure_certificate(Path(__file__).parent / '.local', args.host)
        if message:
            print(message)
    except (ValueError, OSError, ssl.SSLError, subprocess.SubprocessError) as error:
        detail = error.stderr.strip() if isinstance(error, subprocess.CalledProcessError) else str(error)
        parser.exit(1, f'Certificate setup failed: {detail}\nRestore the local certificate files or check OpenSSL and try again.\n')
