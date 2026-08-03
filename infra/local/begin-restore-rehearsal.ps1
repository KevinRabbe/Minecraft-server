param(
    [string]$Destination
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

. (Join-Path $PSScriptRoot "settings.ps1")

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$RuntimeRoot = $LocalNetwork.RuntimeRoot
$ComposeRoot = Join-Path $RepoRoot "infra\compose"
$RestoreMarker = Join-Path $RuntimeRoot "restore.in-progress"
$EvidenceId = (Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssZ")

function Require-Command([string]$Name, [string]$Help) {
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "$Name was not found. $Help"
    }
}

function Test-TcpPort([int]$Port) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $async = $client.BeginConnect("127.0.0.1", $Port, $null, $null)
        if (-not $async.AsyncWaitHandle.WaitOne(300)) {
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

function Invoke-CapturedExternal(
    [string]$OutputPath,
    [scriptblock]$Command,
    [string]$Description
) {
    $output = (& $Command 2>&1 | Out-String)
    $exitCode = $LASTEXITCODE
    $output | Set-Content -Encoding UTF8 $OutputPath
    if ($exitCode -ne 0) {
        throw "$Description failed with exit code $exitCode. See $OutputPath"
    }
    return $output.Trim()
}

function Write-Template(
    [string]$Path,
    [string]$Template,
    [hashtable]$Values
) {
    foreach ($key in $Values.Keys) {
        $Template = $Template.Replace("__${key}__", [string]$Values[$key])
    }
    $Template | Set-Content -Encoding UTF8 $Path
}

if ([System.Environment]::OSVersion.Platform -ne [System.PlatformID]::Win32NT) {
    throw "The coherent restore rehearsal must be initialized on the Windows development machine that will execute it."
}
if (Test-Path $RestoreMarker) {
    $detail = Get-Content -Raw $RestoreMarker -ErrorAction SilentlyContinue
    throw "A restore is already incomplete. Finish the selected restore before beginning a new rehearsal. Marker: $detail"
}

Require-Command "git" "Install Git and run the rehearsal from an exact clean repository commit."
Require-Command "docker" "Install and start Docker Desktop."

Push-Location $RepoRoot
try {
    $commit = (& git rev-parse HEAD 2>$null | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($commit)) {
        throw "Could not resolve the current repository commit."
    }
    $gitStatus = (& git status --porcelain --untracked-files=normal 2>$null | Out-String)
    if ($LASTEXITCODE -ne 0) {
        throw "Could not inspect the current repository status."
    }
    if (-not [string]::IsNullOrWhiteSpace($gitStatus)) {
        throw "The repository is dirty. Commit or stash all changes before initializing recovery evidence."
    }
}
finally {
    Pop-Location
}

if ([string]::IsNullOrWhiteSpace($Destination)) {
    $evidenceRoot = Join-Path $RuntimeRoot "restore-rehearsal-evidence"
    New-Item -ItemType Directory -Force -Path $evidenceRoot | Out-Null
    $EvidencePath = Join-Path $evidenceRoot $EvidenceId
}
else {
    $EvidencePath = [System.IO.Path]::GetFullPath($Destination)
}

if (Test-Path $EvidencePath) {
    if ((Get-ChildItem -Force $EvidencePath | Measure-Object).Count -gt 0) {
        throw "Evidence destination already exists and is not empty: $EvidencePath"
    }
}

New-Item -ItemType Directory -Force -Path $EvidencePath | Out-Null
foreach ($directory in @("environment", "transcripts", "backup-metadata")) {
    New-Item -ItemType Directory -Force -Path (Join-Path $EvidencePath $directory) | Out-Null
}

$StatusPath = Join-Path $EvidencePath "STATUS.txt"
"INITIALIZING - this is not recovery acceptance evidence yet." | Set-Content -Encoding UTF8 $StatusPath

try {
    $environmentRoot = Join-Path $EvidencePath "environment"
    $gitStatus | Set-Content -Encoding UTF8 (Join-Path $environmentRoot "git-status.txt")

    Invoke-CapturedExternal `
        (Join-Path $environmentRoot "docker-version.txt") `
        { & docker version } `
        "docker version" | Out-Null

    Invoke-CapturedExternal `
        (Join-Path $environmentRoot "docker-compose-version.txt") `
        { & docker compose version } `
        "docker compose version" | Out-Null

    Invoke-CapturedExternal `
        (Join-Path $environmentRoot "postgres-ready.txt") `
        {
            Push-Location $ComposeRoot
            try {
                & docker compose exec -T postgres pg_isready -U minecraft -d minecraft
            }
            finally {
                Pop-Location
            }
        } `
        "PostgreSQL readiness check" | Out-Null

    $postgresVersion = Invoke-CapturedExternal `
        (Join-Path $environmentRoot "postgres-version.txt") `
        {
            Push-Location $ComposeRoot
            try {
                & docker compose exec -T postgres psql -U minecraft -d minecraft -Atc "SHOW server_version;"
            }
            finally {
                Pop-Location
            }
        } `
        "PostgreSQL version query"

    $os = Get-CimInstance -ClassName Win32_OperatingSystem
    $sourcePaths = @(
        "infra/local/begin-restore-rehearsal.ps1",
        "infra/local/backup.ps1",
        "infra/local/restore.ps1",
        "infra/local/setup.ps1",
        "infra/local/start.ps1",
        "infra/local/settings.ps1",
        "infra/local/README.md",
        "docs/testing/DEFERRED_EMPIRICAL_ACCEPTANCE.md"
    )
    $sourceHashes = @($sourcePaths | ForEach-Object {
        $fullPath = Join-Path $RepoRoot $_
        if (-not (Test-Path $fullPath -PathType Leaf)) {
            throw "Required rehearsal source is missing: $_"
        }
        [ordered]@{
            path = $_.Replace('\', '/')
            sha256 = (Get-FileHash -Algorithm SHA256 -Path $fullPath).Hash.ToLowerInvariant()
        }
    })

    $ports = New-Object System.Collections.Generic.List[object]
    $ports.Add([ordered]@{
        process = "velocity"
        port = [int]$LocalNetwork.ProxyPort
        reachable = Test-TcpPort ([int]$LocalNetwork.ProxyPort)
    })
    foreach ($server in $LocalNetwork.Servers) {
        $ports.Add([ordered]@{
            process = [string]$server.Id
            port = [int]$server.Port
            reachable = Test-TcpPort ([int]$server.Port)
        })
    }

    $powerShellEdition = "Desktop"
    if ($PSVersionTable.ContainsKey("PSEdition")) {
        $powerShellEdition = [string]$PSVersionTable.PSEdition
    }

    [ordered]@{
        schema_version = 1
        evidence_id = $EvidenceId
        status = "INITIALIZED_NOT_ACCEPTED"
        created_at_utc = (Get-Date).ToUniversalTime().ToString("o")
        repository = [ordered]@{
            commit = $commit
            dirty = $false
        }
        windows = [ordered]@{
            caption = [string]$os.Caption
            version = [string]$os.Version
            build_number = [string]$os.BuildNumber
        }
        powershell = [ordered]@{
            version = [string]$PSVersionTable.PSVersion
            edition = $powerShellEdition
        }
        docker = [ordered]@{
            version_output = "environment/docker-version.txt"
            compose_version_output = "environment/docker-compose-version.txt"
        }
        postgresql = [ordered]@{
            ready_output = "environment/postgres-ready.txt"
            version = $postgresVersion.Trim()
            version_output = "environment/postgres-version.txt"
        }
        runtime = [ordered]@{
            paper_version = [string]$LocalNetwork.PaperVersion
            velocity_version = [string]$LocalNetwork.VelocityVersion
            observed_ports = @($ports)
        }
        qualified_sources = $sourceHashes
    } | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 (Join-Path $EvidencePath "environment.json")

    $templateValues = @{
        COMMIT = $commit
        EVIDENCE_PATH = $EvidencePath
        WINDOWS = "$($os.Caption) $($os.Version) build $($os.BuildNumber)"
        POSTGRES = $postgresVersion.Trim()
        SHUTDOWN_TRANSCRIPT = Join-Path $EvidencePath "transcripts\shutdown-before-backup.txt"
        RECONNECT_TRANSCRIPT = Join-Path $EvidencePath "transcripts\reconnect-slot-proof.txt"
        BACKUP_TRANSCRIPT = Join-Path $EvidencePath "transcripts\backup.txt"
        RESTORE_TRANSCRIPT = Join-Path $EvidencePath "transcripts\restore.txt"
        INTEGRITY_OUTPUT = Join-Path $EvidencePath "transcripts\integrity-100.txt"
    }

    $stateTemplate = @'
# Restore rehearsal state evidence

Repository commit: __COMMIT__

## Pre-backup representative state

- [ ] carried commodity:
- [ ] individualized item and final inventory slot:
- [ ] pocket Coin:
- [ ] Bank state:
- [ ] skill XP/cap state:
- [ ] market/crafting/provenance evidence:
- [ ] Map state:
- [ ] Bounty state:
- [ ] clan state/custody:
- [ ] vote/Chronicle/world-era state where available:
- [ ] recognizable City world mutation:

## Inventory-slot reconnect proof

- [ ] recognizable carried item moved immediately before controlled shutdown
- [ ] Velocity stopped before Paper
- [ ] bounded ten-second logout drain was visible in the transcript
- [ ] Paper backend stop occurred only after the drain
- [ ] item remained in the final slot after reconnect through Velocity

Observed result:

## Post-backup mutations

Database-backed mutation:

City-world mutation:

## Restored state

- [ ] post-backup database mutation is absent
- [ ] post-backup City-world mutation is absent
- [ ] pre-backup database state returned
- [ ] pre-backup City-world state returned to the same selected point
- [ ] disposable Map/Bounty/competitive worlds were not required to preserve value/history

Observed result:

'@
    Write-Template (Join-Path $EvidencePath "state.md") $stateTemplate $templateValues

    $negativeTemplate = @'
# Negative recovery proofs

- [ ] tampered copied backup rejected by checksum verification before destructive work
- [ ] copied backup without `COMPLETE` rejected before destructive work
- [ ] `runtime/restore.in-progress` caused `setup.ps1` to fail closed
- [ ] `runtime/restore.in-progress` caused `start.ps1` to fail closed
- [ ] repository commit mismatch rejected by default
- [ ] deliberately interrupted first restore was rerun to one coherent final state
- [ ] no duplicated value remained after the repeated restore

Record exact commands, outputs and every ambiguity below.

'@
    Write-Template (Join-Path $EvidencePath "negative-tests.md") $negativeTemplate $templateValues

    $resultTemplate = @'
# Coherent restore rehearsal result

Status: **PENDING — NOT ACCEPTED**

Exact repository commit: __COMMIT__
Backup snapshot ID:
Windows version: __WINDOWS__
Docker version: see `environment/docker-version.txt`
PostgreSQL version: __POSTGRES__
Manual intervention required:
Unexplained CRITICAL integrity issues:
Observed mismatches or ambiguities:

Final result must remain PENDING until every check in `REHEARSAL.md` and issue #58 has objective evidence. This initializer cannot declare PASS.
'@
    Write-Template (Join-Path $EvidencePath "result.md") $resultTemplate $templateValues

    $rehearsalTemplate = @'
# Windows/Docker coherent restore rehearsal

Status: **INITIALIZED — NOT ACCEPTED**
Evidence directory: __EVIDENCE_PATH__
Repository commit: __COMMIT__

This directory contains private operational evidence. Do not commit player/economy data or backup contents to Git.

## 1. Representative state

Complete the pre-backup section in `state.md` while connected through Velocity. Create the multi-authority state and recognizable City mutation required by `docs/testing/DEFERRED_EMPIRICAL_ACCEPTANCE.md`.

## 2. Controlled shutdown and logout drain

```powershell
Start-Transcript -Path '__SHUTDOWN_TRANSCRIPT__'
.\infra\local\start.ps1
# Move the recognizable item to its final slot, then press Ctrl+C.
Stop-Transcript
```

The transcript must prove Velocity-first shutdown, the bounded ten-second drain, and Paper stop only after the drain.

## 3. Reconnect proof

```powershell
Start-Transcript -Path '__RECONNECT_TRANSCRIPT__'
.\infra\local\start.ps1
# Reconnect through Velocity and verify the final slot, then stop cleanly.
Stop-Transcript
```

Record the observation in `state.md`.

## 4. Offline coherent backup

```powershell
Start-Transcript -Path '__BACKUP_TRANSCRIPT__'
.\infra\local\backup.ps1
Stop-Transcript
```

Copy only the selected snapshot's `manifest.json`, `checksums.sha256`, and `COMPLETE` into `backup-metadata/`. Record the snapshot ID in `result.md`. Keep the PostgreSQL dump, worlds and all authoritative backup data in the private backup directory.

## 5. Post-backup mutation

Restart, mutate both database-backed state and the recognizable City world, record exact observations in `state.md`, and stop cleanly.

## 6. Destructive restore

```powershell
Start-Transcript -Path '__RESTORE_TRANSCRIPT__'
.\infra\local\restore.ps1 `
  -BackupPath .\infra\local\runtime\backups\<snapshot> `
  -ConfirmRestore
Stop-Transcript
```

Restart through Velocity and complete the restored-state section in `state.md`.

## 7. Integrity

Capture the complete `/integrity 100` output in:

```text
__INTEGRITY_OUTPUT__
```

Explain every remaining CRITICAL issue in `result.md`; unexplained CRITICAL output is a failure.

## 8. Negative tests

Run only against disposable copied snapshots/state. Record commands and outputs in `negative-tests.md`.

## 9. Final classification

The rehearsal passes only when PostgreSQL and persistent world state recover to the same selected point with no unexplained authority corruption or duplicated value. Manually change `result.md` from PENDING only after all evidence is present. The initializer never creates an acceptance marker.
'@
    Write-Template (Join-Path $EvidencePath "REHEARSAL.md") $rehearsalTemplate $templateValues

    "PENDING - evidence directory initialized; recovery has not been accepted." | Set-Content -Encoding UTF8 $StatusPath

    Write-Host ""
    Write-Host "Restore rehearsal evidence initialized: $EvidencePath" -ForegroundColor Green
    Write-Host "Repository commit: $commit"
    Write-Host "PostgreSQL: $($postgresVersion.Trim())"
    Write-Host "Next: open REHEARSAL.md and execute the real-machine/client procedure."
    Write-Warning "This initializer does not prove backup/restore correctness and does not mark acceptance passed."
}
catch {
    "FAILED INITIALIZATION - no recovery acceptance may be inferred. $($_.Exception.Message)" | Set-Content -Encoding UTF8 $StatusPath
    throw
}
