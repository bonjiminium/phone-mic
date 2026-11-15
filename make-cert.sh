#!/usr/bin/env bash
# Optional manual entry point; serve performs the same safe maintenance automatically.
set -euo pipefail
cd -- "$(dirname -- "$0")"
if [[ $# != 1 ]]; then
  echo 'Usage: ./make-cert.sh WINDOWS_LAN_IP (normally just run ./run.sh serve)' >&2
  exit 2
fi
python3 certificates.py "$1"
