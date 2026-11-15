# Return usable IPv4 addresses on connected physical Windows LAN adapters.
# Exclude WSL/Hyper-V/VPN adapters, loopback and link-local addresses.
$ErrorActionPreference = 'Stop'
try {
    $physical = @(Get-NetAdapter -Physical | Where-Object Status -eq 'Up' |
        Select-Object -ExpandProperty InterfaceIndex)
    Get-NetIPConfiguration | Where-Object {
        $_.InterfaceIndex -in $physical -and $_.IPv4DefaultGateway
    } | ForEach-Object {
        $config = $_
        $_.IPv4Address | Where-Object {
            $_.IPAddress -notmatch '^(127\.|169\.254\.|0\.)'
        } | ForEach-Object {
            '{0}|{1}' -f $_.IPAddress, $config.InterfaceAlias
        }
    } | Sort-Object -Unique
} catch {
    Write-Error "Could not detect Windows LAN addresses: $_"
    exit 1
}
