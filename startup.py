"""User-facing startup checks, shared by receiver and doctor."""
from pathlib import Path
import socket
import ssl
import time


def certificate_info(path):
    info = ssl._ssl._test_decode_cert(str(path))
    return info, int(ssl.cert_time_to_seconds(info['notAfter']) * 1000)


def preflight(root, host, port, cable_check):
    local = Path(root) / '.local'
    info, expiry = certificate_info(local / 'server.crt')
    if expiry <= time.time() * 1000:
        raise ValueError('Certificate expired. Restart with ./run.sh serve to renew it, then scan the updated pairing QR.')
    if ('IP Address', host) not in info.get('subjectAltName', ()):
        raise ValueError('Certificate does not match this network address. Run ./run.sh serve to update it, then re-pair.')
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(local / 'server.crt', local / 'server.key')
    sockets = []
    try:
        for address, number in ((host, port), (host, port - 1), ('127.0.0.1', port + 1)):
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            sockets.append(sock)
            try:
                sock.bind((address, number))
            except OSError as error:
                raise ValueError(f'Cannot listen on {address}:{number}. Is another receiver already running? '
                                 'Stop it, or check that this address still belongs to your PC.') from error
        cable_check('CABLE Input', 'output')
        cable_check('CABLE Output', 'input')
    finally:
        for sock in sockets:
            sock.close()
    return expiry


def troubleshooting():
    return '''Connection checklist:
  1. Leave ./run.sh serve running. Put phone and PC on the same Wi-Fi/LAN.
  2. Avoid guest Wi-Fi/client isolation; a VPN may block local traffic.
  3. Select CABLE Output (VB-Audio Virtual Cable) in your Windows app.
  4. Test the cable: ./run.sh check (stop phone capture first).
  5. Check Windows Firewall. If needed, run allow-firewall.ps1 -Ip YOUR_WINDOWS_LAN_IP
     from Windows PowerShell. It asks for admin approval and allows only local-subnet
     traffic on TCP 8764/8765. Never disable the firewall.
  6. If pairing or the certificate changed, reopen .local/connect.html and scan its QR.
The PC dashboard uses loopback TCP 8766 only; do not open it in the firewall.'''
