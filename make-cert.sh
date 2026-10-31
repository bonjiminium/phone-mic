#!/usr/bin/env bash
# Generate a short-lived, project-local CA and a server certificate for one LAN IP.
set -euo pipefail
cd -- "$(dirname -- "$0")"
ip=${1:?Usage: ./make-cert.sh WINDOWS_LAN_IP}
python3 -c 'import ipaddress,sys; ipaddress.IPv4Address(sys.argv[1])' "$ip"
mkdir -p .local
chmod 700 .local
if [[ -e .local/ca.key ]]; then
  echo 'Certificates already exist. Refusing to replace the CA trusted by your phone.' >&2
  exit 1
fi
umask 077
openssl req -x509 -newkey rsa:2048 -nodes -sha256 -days 30 \
  -keyout .local/ca.key -out .local/phone-mic-ca.crt \
  -subj '/CN=Phone Mic Local Development CA' \
  -addext 'basicConstraints=critical,CA:TRUE,pathlen:0' \
  -addext 'keyUsage=critical,keyCertSign,cRLSign'
openssl req -new -newkey rsa:2048 -nodes -sha256 \
  -keyout .local/server.key -out .local/server.csr -subj '/CN=Phone Mic'
printf 'subjectAltName=IP:%s,IP:127.0.0.1,DNS:localhost\nbasicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\n' "$ip" > .local/server.ext
openssl x509 -req -in .local/server.csr -CA .local/phone-mic-ca.crt \
  -CAkey .local/ca.key -CAcreateserial -out .local/server.crt \
  -days 14 -sha256 -extfile .local/server.ext
openssl verify -CAfile .local/phone-mic-ca.crt .local/server.crt
openssl x509 -in .local/phone-mic-ca.crt -noout -fingerprint -sha256
