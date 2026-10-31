param(
    [Parameter(Mandatory = $true)]
    [System.Net.IPAddress]$Ip
)
$ErrorActionPreference = 'Stop'
if ($Ip.AddressFamily -ne [System.Net.Sockets.AddressFamily]::InterNetwork) {
    throw 'Expected Windows LAN IPv4 address'
}
$principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
if (!$principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Start-Process powershell.exe -Verb RunAs -ArgumentList "-NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`" -Ip $Ip"
    exit
}
$name = "PhoneMic-Dev-$Ip"
if (!(Get-NetFirewallRule -Name $name -ErrorAction SilentlyContinue)) {
    New-NetFirewallRule -Name $name -DisplayName 'Phone Mic development (local subnet only)' `
        -Description 'HTTPS audio 8765 and public CA download 8764. Remove when no longer needed.' `
        -Direction Inbound -Action Allow -Protocol TCP -LocalPort 8764,8765 `
        -LocalAddress $Ip.ToString() -RemoteAddress LocalSubnet -Profile Any | Out-Null
}
Get-NetFirewallRule -Name $name | Format-List Name,Enabled,Direction,Action |
    Out-File (Join-Path $PSScriptRoot '.local\firewall.log')
