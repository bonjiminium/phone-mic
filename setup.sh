#!/usr/bin/env bash
# All Python execution and packages here are Windows-native, managed by uv.
set -euo pipefail
cd -- "$(dirname -- "$0")"
root=$(wslpath -w "$PWD")
powershell.exe -NoProfile -NonInteractive -Command \
  "\$root='$root'; if (!(Test-Path \"\$root\.venv-win\Scripts\python.exe\")) { uv venv --python 3.10 \"\$root\.venv-win\"; if (\$LASTEXITCODE -ne 0) { exit \$LASTEXITCODE } }; uv pip install --link-mode=copy --python \"\$root\.venv-win\Scripts\python.exe\" -r \"\$root\requirements.txt\"; exit \$LASTEXITCODE"
# uv uses Lib; Python 3.10 site.py uses lib. WSL's share is case-sensitive.
if [[ -d .venv-win/Lib ]]; then
  if [[ -d .venv-win/lib ]]; then
    cp -a .venv-win/Lib/. .venv-win/lib/
    rm -rf .venv-win/Lib
  else
    mv .venv-win/Lib .venv-win/lib
  fi
fi
