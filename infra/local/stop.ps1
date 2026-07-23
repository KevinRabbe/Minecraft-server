$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

. (Join-Path $PSScriptRoot "settings.ps1")

$ports = @($LocalNetwork.ProxyPort) + @($LocalNetwork.Servers | ForEach-Object { $_.Port })
$connections = Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
    Where-Object { $ports -contains $_.LocalPort }

$processIds = @($connections | Select-Object -ExpandProperty OwningProcess -Unique)
if ($processIds.Count -eq 0) {
    Write-Host "No local Minecraft network processes are listening on the configured ports."
    exit 0
}

Write-Warning "This is an emergency fallback. Prefer Ctrl+C in start.ps1 so Paper and Velocity stop gracefully."
foreach ($processId in $processIds) {
    try {
        $process = Get-Process -Id $processId -ErrorAction Stop
        Write-Host "Stopping PID $processId ($($process.ProcessName))..."
        Stop-Process -Id $processId -Force
    }
    catch {
        Write-Warning "Could not stop PID $processId: $($_.Exception.Message)"
    }
}
