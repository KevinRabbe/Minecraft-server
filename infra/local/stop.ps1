$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

. (Join-Path $PSScriptRoot "settings.ps1")

$ports = @($LocalNetwork.ProxyPort) + @($LocalNetwork.Servers | ForEach-Object { $_.Port })
$connections = Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
    Where-Object { $ports -contains $_.LocalPort }

$pids = @($connections | Select-Object -ExpandProperty OwningProcess -Unique)
if ($pids.Count -eq 0) {
    Write-Host "No local Minecraft network processes are listening on the configured ports."
    exit 0
}

Write-Warning "This is an emergency fallback. Prefer Ctrl+C in start.ps1 so Paper and Velocity stop gracefully."
foreach ($pid in $pids) {
    try {
        $process = Get-Process -Id $pid -ErrorAction Stop
        Write-Host "Stopping PID $pid ($($process.ProcessName))..."
        Stop-Process -Id $pid -Force
    }
    catch {
        Write-Warning "Could not stop PID $pid: $($_.Exception.Message)"
    }
}
