#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")"
root=$(wslpath -w "$PWD")
case "${1:-check}" in
  test) action='-m unittest discover -v' ;;
  serve)
    ip=${2:?Usage: ./run.sh serve WINDOWS_LAN_IP}
    [[ "$ip" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo 'Expected IPv4 address' >&2; exit 2; }
    action="receiver.py --host $ip"
    ;;
  network-test) action='test_network_audio.py' ;;
  list) action='windows_audio.py --list' ;;
  check) action='windows_audio.py' ;;
  *) echo "Usage: $0 [test|list|check|serve IP|network-test]" >&2; exit 2 ;;
esac
powershell.exe -NoProfile -NonInteractive -Command \
  "\$root='$root'; Set-Location \$root; & \"\$root\.venv-win\Scripts\python.exe\" $action; exit \$LASTEXITCODE"
