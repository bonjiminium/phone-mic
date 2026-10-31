# Capture only phone TCP traffic to certificate port; never change firewall rules.
$ErrorActionPreference = 'Stop'
$principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
if (!$principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Start-Process powershell.exe -Verb RunAs -WindowStyle Minimized -ArgumentList "-NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`""
    exit
}
$local = Join-Path $PSScriptRoot '.local'
$log = Join-Path $local 'packet-monitor-state.log'
$status = (& pktmon status 2>&1 | Out-String)
$filters = (& pktmon filter list 2>&1 | Out-String)
"$status`n$filters" | Out-File $log -Encoding utf8
if ($status -notmatch 'Packet Monitor is not running' -or $filters -notmatch 'Packet Filters:\s+None') {
    'ABORTED: an existing capture/filter may be in use; left it untouched.' | Out-File $log -Append -Encoding utf8
    exit 1
}
$work = Join-Path $env:TEMP ('phone-mic-network-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory $work | Out-Null
$filterName = 'PhoneMic-Diagnostic'
$started = $false
try {
    & pktmon filter add $filterName -i 192.168.1.254 -t TCP -p 8764 2>&1 | Out-File $log -Append -Encoding utf8
    if ($LASTEXITCODE -ne 0) { throw 'Could not add diagnostic filter' }
    & pktmon start --capture --pkt-size 64 --file-size 8 --file-name "$work\capture.etl" 2>&1 | Out-File $log -Append -Encoding utf8
    if ($LASTEXITCODE -ne 0) { throw 'Could not start capture' }
    $started = $true
    'READY: phone 192.168.1.254 TCP 8764; up to 120 seconds' | Out-File (Join-Path $local 'capture-state.txt') -Encoding utf8
    for ($i = 0; $i -lt 120; $i++) {
        if (Test-Path (Join-Path $local 'stop-capture')) { break }
        Start-Sleep -Seconds 1
    }
} finally {
    if ($started) {
        & pktmon stop 2>&1 | Out-File $log -Append -Encoding utf8
        & pktmon etl2txt "$work\capture.etl" --out "$work\capture.txt" --verbose 2>&1 | Out-File $log -Append -Encoding utf8
        if (Test-Path "$work\capture.txt") {
            Get-Content "$work\capture.txt" | Out-File (Join-Path $local 'phone-packets.txt') -Encoding utf8
        }
    }
    & pktmon filter remove $filterName 2>&1 | Out-File $log -Append -Encoding utf8
    'FINISHED' | Out-File (Join-Path $local 'capture-state.txt') -Encoding utf8
}
