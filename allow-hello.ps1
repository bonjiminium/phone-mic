# Temporary plain HTTP test: permit only the phone, only port 80, only the LAN IP.
$ErrorActionPreference = 'Stop'
$principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
if (!$principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Start-Process powershell.exe -Verb RunAs -ArgumentList "-NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`""
    exit
}
$name = 'PhoneMic-Hello-Temporary'
if (!(Get-NetFirewallRule -Name $name -ErrorAction SilentlyContinue)) {
    New-NetFirewallRule -Name $name -DisplayName 'Phone Mic temporary HTTP hello test' `
        -Direction Inbound -Action Allow -Protocol TCP -LocalPort 80 `
        -LocalAddress 192.168.1.237 -RemoteAddress 192.168.1.254 -Profile Any | Out-Null
}
Get-NetFirewallRule -Name $name | Format-List Name,Enabled,Action |
    Out-File (Join-Path $PSScriptRoot '.local\hello-firewall.log') -Encoding utf8
# Remove afterward with: Remove-NetFirewallRule -Name 'PhoneMic-Hello-Temporary'
