#!/usr/bin/env bash
# Linux build tools only; no Android Studio or Windows-wide installation.
set -euo pipefail
TOOLS=${PHONE_MIC_TOOLS:-$HOME/.cache/phone-mic-android}
mkdir -p "$TOOLS"
export TOOLS
python3 - <<'PY'
import hashlib, json, os, pathlib, urllib.request, xml.etree.ElementTree as ET
p = pathlib.Path(os.environ['TOOLS'])
def fetch(url):
    return urllib.request.urlopen(url, timeout=60).read()
def download(url, name, digest, algorithm='sha256'):
    dest = p / name
    if not dest.exists() or hashlib.new(algorithm, dest.read_bytes()).hexdigest() != digest:
        print('Downloading', name, flush=True)
        with urllib.request.urlopen(url, timeout=120) as response, dest.open('wb') as out:
            while chunk := response.read(1024 * 1024):
                out.write(chunk)
    if hashlib.new(algorithm, dest.read_bytes()).hexdigest() != digest:
        raise RuntimeError('Checksum mismatch: ' + name)
if not (p / 'jdk/bin/java').exists():
    packages = json.loads(fetch('https://api.azul.com/metadata/v1/zulu/packages/?java_version=21&os=linux&arch=x86_64&archive_type=tar.gz&java_package_type=jdk&latest=true&release_status=ga&availability_types=CA'))
    package = next(x for x in packages if x['name'].endswith('-linux_x64.tar.gz') and '-crac-' not in x['name'] and '-fx-' not in x['name'])
    details = json.loads(fetch('https://api.azul.com/metadata/v1/zulu/packages/' + package['package_uuid']))
    download(details['download_url'], 'jdk.tar.gz', details['sha256_hash'])
if not (p / 'gradle-9.3.1/bin/gradle').exists():
    url = 'https://services.gradle.org/distributions/gradle-9.3.1-bin.zip'
    download(url, 'gradle.zip', fetch(url + '.sha256').decode().strip())
if not (p / 'sdk/cmdline-tools/latest/bin/sdkmanager').exists():
    repo = ET.fromstring(fetch('https://dl.google.com/android/repository/repository2-1.xml'))
    package = next(x for x in repo.findall('remotePackage') if x.get('path') == 'cmdline-tools;latest')
    archive = next(x for x in package.findall('./archives/archive') if x.findtext('host-os') == 'linux')
    complete = archive.find('complete')
    download('https://dl.google.com/android/repository/' + complete.findtext('url'),
             'cmdline-tools.zip', complete.findtext('checksum'), 'sha1')
PY
if [[ ! -x "$TOOLS/jdk/bin/java" ]]; then
  mkdir -p "$TOOLS/jdk"
  tar -xzf "$TOOLS/jdk.tar.gz" --strip-components=1 -C "$TOOLS/jdk"
fi
if [[ ! -x "$TOOLS/gradle-9.3.1/bin/gradle" ]]; then
  unzip -q "$TOOLS/gradle.zip" -d "$TOOLS"
fi
if [[ ! -x "$TOOLS/sdk/cmdline-tools/latest/bin/sdkmanager" ]]; then
  mkdir -p "$TOOLS/sdk/cmdline-tools"
  unzip -q "$TOOLS/cmdline-tools.zip" -d "$TOOLS/sdk/cmdline-tools"
  mv "$TOOLS/sdk/cmdline-tools/cmdline-tools" "$TOOLS/sdk/cmdline-tools/latest"
fi
export JAVA_HOME="$TOOLS/jdk"
export PATH="$JAVA_HOME/bin:$PATH"
# Print licenses for review before accepting interactively. Do not silently accept.
"$TOOLS/sdk/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$TOOLS/sdk" --licenses
"$TOOLS/sdk/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$TOOLS/sdk" \
  'platforms;android-36' 'build-tools;36.0.0' 'platform-tools'
