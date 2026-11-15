#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")"
usage() {
  printf '%s\n' 'Phone Mic — use your Android phone as a Windows microphone' '' \
    '1. Run ./run.sh serve' \
    '2. Open .local/connect.html on this PC for installation and pairing QR codes.' \
    '3. Pair & start on your phone, then select CABLE Output in your Windows app.' '' \
    'Commands:' \
    '  serve [IP]       Start receiver (automatically detects Windows LAN address)' \
    '  check            Test the virtual cable with a generated tone' \
    '  doctor           Check setup and show network/firewall troubleshooting' \
    '  list             List Windows audio devices' \
    '  test             Run automated Windows tests' \
    '  network-test     Test audio through a running receiver (stop phone first)' '' \
    'serve --reset-pairing revokes ALL existing phone/browser pairings.'
}
case "${1:-help}" in
  help|-h|--help) usage; exit 0 ;;
esac
command -v powershell.exe >/dev/null || { echo 'Windows PowerShell is unavailable. Run this launcher inside WSL with Windows interop enabled.' >&2; exit 1; }
root=$(wslpath -w "$PWD")
case "$1" in
  test) action='-m unittest discover -v' ;;
  serve)
    shift
    ip=''
    if [[ ${1:-} != --* && -n ${1:-} ]]; then
      ip=$1
      shift
    fi
    option=${1:-}
    case "$option" in
      ''|--reset-pairing|--reuse-session) ;;
      *) echo 'Expected --reset-pairing or no option' >&2; exit 2 ;;
    esac
    [[ $# -le 1 ]] || { echo 'Too many serve arguments' >&2; exit 2; }
    if [[ -z "$ip" ]]; then
      addresses=$(powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass \
        -File "$root\\lan-addresses.ps1") || {
        echo 'LAN detection failed. Use ./run.sh serve WINDOWS_LAN_IP instead.' >&2
        exit 1
      }
      candidates=()
      while IFS= read -r line; do
        line=${line//$'\r'/}
        [[ -z "$line" ]] || candidates+=("$line")
      done <<< "$addresses"
      case ${#candidates[@]} in
        0)
          echo 'No Windows LAN address found. Connect to Wi-Fi/Ethernet or use ./run.sh serve WINDOWS_LAN_IP.' >&2
          exit 1 ;;
        1) ip=${candidates[0]%%|*} ;;
        *)
          echo 'Choose the Windows network shared with your phone:' >&2
          if [[ ! -t 0 ]]; then
            printf '  %s\n' "${candidates[@]}" >&2
            echo 'Use ./run.sh serve WINDOWS_LAN_IP to choose explicitly.' >&2
            exit 1
          fi
          select candidate in "${candidates[@]}"; do
            if [[ -n "$candidate" ]]; then
              ip=${candidate%%|*}
              break
            fi
            echo 'Enter one of the listed numbers.' >&2
          done
          [[ -n "$ip" ]] || exit 1 ;;
      esac
    fi
    [[ "$ip" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo 'Expected IPv4 address' >&2; exit 2; }
    echo "Using Windows LAN address: $ip"
    command -v python3 >/dev/null && command -v openssl >/dev/null || {
      echo 'Certificate checks need WSL python3 and openssl. Install them and retry.' >&2; exit 1;
    }
    python3 certificates.py "$ip"
    action="receiver.py --host $ip --open-page $option"
    ;;
  network-test) action='test_network_audio.py' ;;
  list) action='windows_audio.py --list' ;;
  check) action='windows_audio.py' ;;
  doctor)
    echo 'Detected Windows LAN networks (no settings changed):'
    if addresses=$(powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "$root\\lan-addresses.ps1"); then
      while IFS='|' read -r address adapter; do
        address=${address//$'\r'/}; adapter=${adapter//$'\r'/}
        [[ -n "$address" ]] || continue
        printf '  %s: %s\n' "$adapter" "$address"
        printf '  If needed, in Windows PowerShell: & "%s\\allow-firewall.ps1" -Ip %s\n' "$root" "$address"
      done <<< "$addresses"
    else
      echo 'Could not inspect Windows networks. Check Wi-Fi/Ethernet and retry.' >&2
    fi
    action='receiver.py --doctor'
    ;;
  *) echo "Unknown command: $1. Run ./run.sh --help." >&2; exit 2 ;;
esac
powershell.exe -NoProfile -NonInteractive -Command \
  "\$root='$root'; Set-Location \$root; if (!(Test-Path \"\$root\.venv-win\Scripts\python.exe\")) { Write-Host 'Windows Python environment is missing. Run ./setup.sh first.'; exit 1 }; & \"\$root\.venv-win\Scripts\python.exe\" $action; exit \$LASTEXITCODE"
