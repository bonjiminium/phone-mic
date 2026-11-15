"""Persistent local pairing secret. Never silently rotate existing credentials."""
import hashlib
import json
import ssl
import os
from pathlib import Path
import re
import secrets
import tempfile


def pairing_payload(host, port, token, certificate_pem, expires_at=None):
    """App-readable QR schema, containing a leaf pin but never a CA/private key."""
    certificate = ssl.PEM_cert_to_DER_cert(certificate_pem)
    payload = dict(version=1, serverUrl=f'https://{host}:{port}', token=token,
                   certificateSha256=hashlib.sha256(certificate).hexdigest())
    if expires_at is not None:
        payload['certificateExpiresAt'] = expires_at
    return payload


def _read_token(path, *, legacy=False):
    try:
        data = json.loads(path.read_text(encoding='utf-8'))
        token = data['token']
        if not legacy and data.get('version') != 1:
            raise ValueError('Unsupported pairing version')
        if not isinstance(token, str) or not re.fullmatch(r'[A-Za-z0-9_-]{32,128}', token):
            raise ValueError('Invalid token')
        return token
    except (ValueError, KeyError, TypeError) as exc:
        raise ValueError(f'Invalid pairing data in {path.name}; restore it or explicitly use --reset-pairing') from exc


def pairing_token(directory, *, reset=False):
    """Reuse saved secret, adopting an existing APK's legacy token on first use.

    Reset is explicit and invalidates paired APKs. Corrupt/missing data in an
    existing credential file is an error, not a reason to break pairing silently.
    Atomic replacement avoids a partially written credential after interruption.
    """
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    saved = directory / 'pairing.json'
    if not reset and saved.exists():
        return _read_token(saved)
    legacy = directory / 'connection.json'
    token = _read_token(legacy, legacy=True) if not reset and legacy.exists() else secrets.token_urlsafe(24)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode='w', encoding='utf-8', dir=directory,
                                         prefix='.pairing-', suffix='.tmp', delete=False) as stream:
            temporary = Path(stream.name)
            # On Windows this is not an ACL; keep the project private there too.
            os.chmod(temporary, 0o600)
            json.dump({'version': 1, 'token': token}, stream)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, saved)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)
    return token
