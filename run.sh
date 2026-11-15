#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")"
root=$(wslpath -w "$PWD")
case "${1:-check}" in
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
    action="receiver.py --host $ip $option"
    ;;
  network-test) action='test_network_audio.py' ;;
  list) action='windows_audio.py --list' ;;
  check) action='windows_audio.py' ;;
  *) echo "Usage: $0 [test|list|check|serve [IP] [--reset-pairing|--reuse-session]|network-test]" >&2; exit 2 ;;
esac
powershell.exe -NoProfile -NonInteractive -Command \
  "\$root='$root'; Set-Location \$root; & \"\$root\.venv-win\Scripts\python.exe\" $action; exit \$LASTEXITCODE"
