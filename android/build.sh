#!/usr/bin/env bash
# PRIVATE device spike: APK embeds this receiver session's token and exact cert pin.
set -euo pipefail
cd -- "$(dirname -- "$0")"
TOOLS=${PHONE_MIC_TOOLS:-$HOME/.cache/phone-mic-android}
export JAVA_HOME="$TOOLS/jdk"
export ANDROID_HOME="$TOOLS/sdk"
export PATH="$JAVA_HOME/bin:$TOOLS/gradle-9.3.1/bin:$PATH"
python3 - <<'PY'
import hashlib, json, pathlib, ssl, urllib.parse
root = pathlib.Path('..').resolve()
p = root / '.local'
info = json.loads((p / 'connection.json').read_text())
cert = ssl.PEM_cert_to_DER_cert((p / 'server.crt').read_text())
url = f'wss://{info["host"]}:{info["port"]}/audio?' + urllib.parse.urlencode({'token': info['token']})
config = {'websocketUrl': url, 'certificateSha256': hashlib.sha256(cert).hexdigest()}
(p / 'android-config.json').write_text(json.dumps(config))
(p / 'android-config.json').chmod(0o600)
(p / 'android-test.properties').write_text(f'statusUrl=https://{info["host"]}:{info["port"]}/status\ntoken={info["token"]}\npin={config["certificateSha256"]}\n')
(p / 'android-test.properties').chmod(0o600)
print('Generated private pairing config (token not logged).')
PY
if [[ ${PHONE_MIC_TEST_RECEIVER:-0} == 1 ]]; then
  export PHONE_MIC_RECEIVER_TEST_CONFIG="$(realpath ../.local/android-test.properties)"
fi
gradle --no-daemon testDebugUnitTest lintDebug assembleDebug
install -m 600 app/build/outputs/apk/debug/app-debug.apk ../.local/phone-mic-debug.apk
sha256sum ../.local/phone-mic-debug.apk
