param(
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

. (Join-Path $PSScriptRoot "settings.ps1")

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$ComposeRoot = Join-Path $RepoRoot "infra\compose"
$RuntimeRoot = $LocalNetwork.RuntimeRoot
$RestoreMarker = Join-Path $RuntimeRoot "restore.in-progress"
$LogoutDrainSeconds = 10
if (Test-Path $RestoreMarker) {
    $detail = Get-Content -Raw $RestoreMarker -ErrorAction SilentlyContinue
    throw "Refusing to start the Minecraft network because a restore is incomplete. Rerun infra\local\restore.ps1 for the selected backup until it succeeds. Marker: $detail"
}

& (Join-Path $PSScriptRoot "setup.ps1") -SkipBuild:$SkipBuild

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

function ConvertTo-SqlLiteral([string]$Value) {
    if ($null -eq $Value) {
        return "NULL"
    }
    return "'" + $Value.Replace("'", "''") + "'"
}

function Read-BackendAuthority([hashtable]$Server) {
    $backendId = ConvertTo-SqlLiteral ([string]$Server.Id)
    $zoneId = ConvertTo-SqlLiteral ([string]$Server.Zone)
    $templateVersion = ConvertTo-SqlLiteral ([string]$Server.ZoneTemplate)
    $query = @"
SELECT
    b.status || '|' ||
    zi.status || '|' ||
    COUNT(rs.source_id)::text
FROM backends b
JOIN zone_instances zi
  ON zi.backend_id = b.backend_id
 AND zi.backend_incarnation_id = b.incarnation_id
LEFT JOIN resource_sources rs
  ON rs.instance_id = zi.instance_id
WHERE b.backend_id = $backendId
  AND zi.zone_id = $zoneId
  AND zi.template_version = $templateVersion
  AND zi.status IN ('STARTING', 'ACTIVE', 'DRAINING')
GROUP BY b.status, zi.status
ORDER BY zi.status
"@

    Push-Location $ComposeRoot
    try {
        $output = (& docker compose exec -T postgres `
            psql -X -v ON_ERROR_STOP=1 -U minecraft -d minecraft `
            -At -c $query 2>&1 | Out-String)
        $exitCode = $LASTEXITCODE
    }
    finally {
        Pop-Location
    }

    if ($exitCode -ne 0) {
        throw "Backend authority query failed with exit code $exitCode. Output: $($output.Trim())"
    }

    $lines = @($output -split "`r?`n" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    if ($lines.Count -eq 0) {
        return $null
    }
    if ($lines.Count -ne 1) {
        throw "Backend $($Server.Id) has multiple current zone authority rows for $($Server.Zone)/$($Server.ZoneTemplate): $($lines -join '; ')"
    }

    $fields = @($lines[0].Split('|'))
    if ($fields.Count -ne 3) {
        throw "Backend authority query returned malformed output for $($Server.Id): $($lines[0])"
    }

    $resourceCount = 0
    if (-not [int]::TryParse($fields[2], [ref]$resourceCount)) {
        throw "Backend authority query returned an invalid resource count for $($Server.Id): $($fields[2])"
    }

    return [ordered]@{
        BackendStatus = [string]$fields[0]
        ZoneStatus = [string]$fields[1]
        ResourceSourceCount = $resourceCount
    }
}

function Wait-ForBackendAuthority {
    $deadline = (Get-Date).AddMinutes(2)
    $lastObservation = "No authoritative backend publication observed yet."

    while ((Get-Date) -lt $deadline) {
        $pending = New-Object System.Collections.Generic.List[string]
        foreach ($server in $LocalNetwork.Servers) {
            $entry = $managed | Where-Object { $_.Name -eq $server.Id } | Select-Object -First 1
            if ($entry -and $entry.Process.HasExited) {
                throw "$($server.Id) exited before publishing authoritative readiness (code $($entry.Process.ExitCode))."
            }

            try {
                $authority = Read-BackendAuthority $server
                if ($null -eq $authority) {
                    $pending.Add("$($server.Id): no current $($server.Zone)/$($server.ZoneTemplate) zone row")
                    continue
                }

                if ($authority.BackendStatus -ne "ONLINE" -or $authority.ZoneStatus -ne "ACTIVE") {
                    $pending.Add(
                        "$($server.Id): backend=$($authority.BackendStatus), zone=$($authority.ZoneStatus)"
                    )
                    continue
                }

                $requireResourceContent = $false
                if ($server.ContainsKey("RequireResourceContent")) {
                    $requireResourceContent = [bool]$server.RequireResourceContent
                }
                if ($requireResourceContent -and $authority.ResourceSourceCount -lt 1) {
                    $pending.Add("$($server.Id): required resource authority has 0 registered source rows")
                }
            }
            catch {
                $pending.Add("$($server.Id): $($_.Exception.Message)")
            }
        }

        if ($pending.Count -eq 0) {
            Write-Host "All Paper backends published authoritative ONLINE/ACTIVE readiness." -ForegroundColor Green
            return
        }

        $lastObservation = $pending -join "; "
        Start-Sleep -Milliseconds 500
    }

    throw "Timed out waiting for authoritative Paper readiness before opening Velocity. Last observation: $lastObservation"
}

function Graceful-Shutdown {
    Write-Host ""
    Write-Host "Stopping local network..."

    $velocity = $managed | Where-Object { $_.Name -eq "velocity" } | Select-Object -First 1
    $velocityStopped = $false
    if ($velocity) {
        if (-not $velocity.Process.HasExited) {
            try { $velocity.Process.StandardInput.WriteLine("shutdown") } catch {}

            $velocityDeadline = (Get-Date).AddSeconds(5)
            while (-not $velocity.Process.HasExited -and (Get-Date) -lt $velocityDeadline) {
                Start-Sleep -Milliseconds 100
            }
        }

        if ($velocity.Process.HasExited) {
            $velocityStopped = $true
        }
        else {
            Write-Warning "Velocity did not stop promptly; continuing backend shutdown without the logout drain delay."
        }
    }

    if ($velocityStopped) {
        # Velocity disconnects players before Paper receives its own stop command. Keep the backends alive for the
        # same bounded window Paper uses for controlled final commits so PlayerQuit finalization can finish while the
        # plugin scheduler and persistence executor are still fully available. This also covers an unexpected proxy exit.
        Write-Host "Draining final player logout checkpoints for $LogoutDrainSeconds seconds..."
        Start-Sleep -Seconds $LogoutDrainSeconds
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
            BOOTSTRAP_ZONE_ID = $server.Zone
            BOOTSTRAP_ZONE_TEMPLATE = $server.ZoneTemplate
            BOOTSTRAP_ZONE_SOFT_CAPACITY = $server.ZoneSoftCapacity
            BOOTSTRAP_ZONE_HARD_CAPACITY = $server.ZoneHardCapacity
            DEV_TOOLS_ENABLED = "true"
        } | Out-Null
    }

    Write-Host "Waiting for Paper backend ports..."
    Wait-ForBackends
    Write-Host "Waiting for Paper authoritative publication..."
    Wait-ForBackendAuthority

    Start-JavaProcess "velocity" $VelocityRoot "-Xms256M -Xmx512M -jar velocity.jar" @{
        PERSISTENT_HUB_ZONE_ID = $LocalNetwork.HubZone
    } | Out-Null

    Write-Host ""
    Write-Host "Local network is running." -ForegroundColor Green
    Write-Host "Connect Minecraft to localhost:$($LocalNetwork.ProxyPort)"
    Write-Host "Development transfer proof: /devzone starter-woods"
    Write-Host "Do not use direct /server switching for persistent-state tests; it intentionally lacks a transfer ticket."
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
