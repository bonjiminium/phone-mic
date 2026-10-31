#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")"
root=$(wslpath -w "$PWD")
case "${1:-check}" in
  test) action='-m unittest -v test_windows_audio' ;;
  list) action='windows_audio.py --list' ;;
  check) action='windows_audio.py' ;;
  *) echo "Usage: $0 [test|list|check]" >&2; exit 2 ;;
esac
powershell.exe -NoProfile -NonInteractive -Command \
  "\$root='$root'; Set-Location \$root; & \"\$root\.venv-win\Scripts\python.exe\" $action; exit \$LASTEXITCODE"
