param(
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

. (Join-Path $PSScriptRoot "settings.ps1")

& (Join-Path $PSScriptRoot "setup.ps1") -SkipBuild:$SkipBuild

$RuntimeRoot = $LocalNetwork.RuntimeRoot
$VelocityRoot = Join-Path $RuntimeRoot "velocity"
$ServersRoot = Join-Path $RuntimeRoot "servers"
$managed = New-Object System.Collections.ArrayList

function Start-JavaProcess(
    [string]$Name,
    [string]$Directory,
    [string]$Arguments,
    [hashtable]$Environment
) {
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = "java"
    $psi.Arguments = $Arguments
    $psi.WorkingDirectory = $Directory
    $psi.UseShellExecute = $false
    $psi.RedirectStandardInput = $true

    foreach ($key in $Environment.Keys) {
        $psi.EnvironmentVariables[$key] = [string]$Environment[$key]
    }

    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $psi
    if (-not $process.Start()) {
        throw "Failed to start $Name."
    }

    [void]$managed.Add(@{ Name = $Name; Process = $process })
    Write-Host "Started $Name (PID $($process.Id))."
    return $process
}

function Test-TcpPort([int]$Port) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $async = $client.BeginConnect("127.0.0.1", $Port, $null, $null)
        if (-not $async.AsyncWaitHandle.WaitOne(500)) {
            return $false
        }
        $client.EndConnect($async)
        return $true
    }
    catch {
        return $false
    }
    finally {
        $client.Dispose()
    }
}

function Wait-ForBackends {
    $deadline = (Get-Date).AddMinutes(2)
    $remaining = @($LocalNetwork.Servers)

    while ($remaining.Count -gt 0) {
        $remaining = @($remaining | Where-Object { -not (Test-TcpPort $_.Port) })
        if ($remaining.Count -eq 0) {
            return
        }
        if ((Get-Date) -gt $deadline) {
            $names = ($remaining | ForEach-Object { "$($_.Id):$($_.Port)" }) -join ", "
            throw "Timed out waiting for backend ports: $names"
        }
        Start-Sleep -Milliseconds 500
    }
}

function Graceful-Shutdown {
    Write-Host ""
    Write-Host "Stopping local network..."

    $velocity = $managed | Where-Object { $_.Name -eq "velocity" } | Select-Object -First 1
    if ($velocity -and -not $velocity.Process.HasExited) {
        try { $velocity.Process.StandardInput.WriteLine("shutdown") } catch {}
    }

    foreach ($entry in @($managed | Where-Object { $_.Name -ne "velocity" })) {
        if (-not $entry.Process.HasExited) {
            try { $entry.Process.StandardInput.WriteLine("stop") } catch {}
        }
    }

    $deadline = (Get-Date).AddSeconds(30)
    foreach ($entry in $managed) {
        $remaining = [Math]::Max(0, [int](($deadline - (Get-Date)).TotalMilliseconds))
        if (-not $entry.Process.HasExited -and $remaining -gt 0) {
            [void]$entry.Process.WaitForExit($remaining)
        }
        if (-not $entry.Process.HasExited) {
            Write-Warning "$($entry.Name) did not stop in time; terminating it."
            $entry.Process.Kill()
        }
    }
}

try {
    foreach ($server in $LocalNetwork.Servers) {
        $directory = Join-Path $ServersRoot $server.Id
        $arguments = "-Xms256M -Xmx$($server.Memory) -jar server.jar nogui"
        Start-JavaProcess $server.Id $directory $arguments @{
            BACKEND_ID = $server.Id
            SERVER_ID = $server.Id
            SERVER_ROLE = $server.Role
        } | Out-Null
    }

    Write-Host "Waiting for Paper backends..."
    Wait-ForBackends

    Start-JavaProcess "velocity" $VelocityRoot "-Xms256M -Xmx512M -jar velocity.jar" @{} | Out-Null

    Write-Host ""
    Write-Host "Local network is running." -ForegroundColor Green
    Write-Host "Connect Minecraft to localhost:$($LocalNetwork.ProxyPort)"
    Write-Host "Use /server to switch between configured backends during M0 testing."
    Write-Host "Press Ctrl+C in this window to stop every Minecraft process cleanly."

    while ($true) {
        foreach ($entry in $managed) {
            if ($entry.Process.HasExited) {
                throw "$($entry.Name) exited unexpectedly with code $($entry.Process.ExitCode)."
            }
        }
        Start-Sleep -Seconds 1
    }
}
finally {
    Graceful-Shutdown
}
