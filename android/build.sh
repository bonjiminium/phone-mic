#!/usr/bin/env bash
# Reusable APK: pairing is scanned and stored on the phone, never built in.
set -euo pipefail
cd -- "$(dirname -- "$0")"
TOOLS=${PHONE_MIC_TOOLS:-$HOME/.cache/phone-mic-android}
export JAVA_HOME="$TOOLS/jdk"
export ANDROID_HOME="$TOOLS/sdk"
export PATH="$JAVA_HOME/bin:$TOOLS/gradle-9.3.1/bin:$PATH"
if [[ ${PHONE_MIC_TEST_RECEIVER:-0} == 1 ]]; then
  python3 - <<'PY'
import hashlib, json, pathlib, ssl, sys
sys.path.insert(0, str(pathlib.Path('..').resolve()))
from pairing import pairing_payload
p = pathlib.Path('../.local').resolve()
info = json.loads((p / 'connection.json').read_text())
cert = ssl.PEM_cert_to_DER_cert((p / 'server.crt').read_text())
pin = hashlib.sha256(cert).hexdigest()
pairing = pairing_payload(info['host'], info['port'], info['token'], (p / 'server.crt').read_text())
(p / 'android-test.properties').write_text(f'statusUrl=https://{info["host"]}:{info["port"]}/status\ntoken={info["token"]}\npin={pin}\npairingJson={json.dumps(pairing)}\n')
(p / 'android-test.properties').chmod(0o600)
PY
  export PHONE_MIC_RECEIVER_TEST_CONFIG="$(realpath ../.local/android-test.properties)"
fi
gradle --no-daemon testDebugUnitTest lintDebug assembleDebug
python3 - <<'PY'
import json, pathlib, zipfile
apk = pathlib.Path('app/build/outputs/apk/debug/app-debug.apk')
forbidden = []
for file, fields in [('../.local/connection.json', ['token']),
                     ('../.local/android-config.json', ['certificateSha256'])]:
    path = pathlib.Path(file)
    if path.exists():
        data = json.loads(path.read_text())
        forbidden.extend(data[key].encode() for key in fields if key in data)
with zipfile.ZipFile(apk) as archive:
    assert not any(name.startswith('assets/') for name in archive.namelist()), 'Unexpected APK assets: check for old pairing config'
    for name in archive.namelist():
        content = archive.read(name)
        assert not any(secret in content for secret in forbidden), 'Pairing credential found in APK'
print('APK audit PASS: no bundled pairing assets or known local credentials.')
PY
mkdir -p ../.local
install -m 600 app/build/outputs/apk/debug/app-debug.apk ../.local/phone-mic-debug.apk
sha256sum ../.local/phone-mic-debug.apk
