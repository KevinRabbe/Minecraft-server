param(
    [string]$Destination,
    [switch]$AllowDirtyRepository
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

. (Join-Path $PSScriptRoot "settings.ps1")

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$RuntimeRoot = $LocalNetwork.RuntimeRoot
$RestoreMarker = Join-Path $RuntimeRoot "restore.in-progress"
if (Test-Path $RestoreMarker) {
    $detail = Get-Content -Raw $RestoreMarker -ErrorAction SilentlyContinue
    throw "Refusing backup because a restore is incomplete. Finish the selected restore before creating another recovery point. Marker: $detail"
}
$ServersRoot = Join-Path $RuntimeRoot "servers"
$ComposeRoot = Join-Path $RepoRoot "infra\compose"
$DefaultBackupRoot = Join-Path $RuntimeRoot "backups"
$PostgresContainer = "minecraft-postgres"
$DatabaseName = "minecraft"
$DatabaseUser = "minecraft"
$SnapshotId = (Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssZ")

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

function Assert-MinecraftStopped {
    $open = New-Object System.Collections.Generic.List[string]
    if (Test-TcpPort $LocalNetwork.ProxyPort) {
        $open.Add("velocity:$($LocalNetwork.ProxyPort)")
    }
    foreach ($server in $LocalNetwork.Servers) {
        if (Test-TcpPort $server.Port) {
            $open.Add("$($server.Id):$($server.Port)")
        }
    }
    if ($open.Count -gt 0) {
        throw "Refusing coherent backup while Minecraft processes are reachable: $($open -join ', '). Stop the local network first."
    }
}

function Invoke-DockerCompose([string[]]$Arguments) {
    Push-Location $ComposeRoot
    try {
        & docker compose @Arguments
        if ($LASTEXITCODE -ne 0) {
            throw "docker compose $($Arguments -join ' ') failed with exit code $LASTEXITCODE."
        }
    }
    finally {
        Pop-Location
    }
}

function Start-PostgresReady {
    Invoke-DockerCompose -Arguments @("up", "-d", "postgres")
    $deadline = (Get-Date).AddSeconds(60)
    while ((Get-Date) -lt $deadline) {
        Push-Location $ComposeRoot
        try {
            & docker compose exec -T postgres pg_isready -U $DatabaseUser -d $DatabaseName *> $null
            if ($LASTEXITCODE -eq 0) {
                return
            }
        }
        finally {
            Pop-Location
        }
        Start-Sleep -Milliseconds 500
    }
    throw "Timed out waiting for local PostgreSQL to become ready."
}

function Get-GitSnapshot {
    $git = Get-Command "git" -ErrorAction SilentlyContinue
    if (-not $git) {
        return [ordered]@{
            commit = $null
            dirty = $null
        }
    }

    Push-Location $RepoRoot
    try {
        $commit = (& git rev-parse HEAD 2>$null | Out-String).Trim()
        if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($commit)) {
            return [ordered]@{ commit = $null; dirty = $null }
        }
        $status = (& git status --porcelain --untracked-files=normal 2>$null | Out-String)
        if ($LASTEXITCODE -ne 0) {
            return [ordered]@{ commit = $commit; dirty = $null }
        }
        return [ordered]@{
            commit = $commit
            dirty = -not [string]::IsNullOrWhiteSpace($status)
        }
    }
    finally {
        Pop-Location
    }
}

function Copy-VersionedSource([string]$Source, [string]$DestinationPath) {
    if (-not (Test-Path $Source)) {
        throw "Required versioned backup source is missing: $Source"
    }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $DestinationPath) | Out-Null
    Copy-Item -Recurse -Force $Source $DestinationPath
}

function Copy-Worlds([string]$WorldBackupRoot) {
    $worldEntries = New-Object System.Collections.Generic.List[object]
    foreach ($server in $LocalNetwork.Servers) {
        $serverRoot = Join-Path $ServersRoot $server.Id
        if (-not (Test-Path $serverRoot)) {
            continue
        }
        $worldDirectories = @(Get-ChildItem -Path $serverRoot -Directory | Where-Object {
            Test-Path (Join-Path $_.FullName "level.dat")
        })
        foreach ($world in $worldDirectories) {
            $target = Join-Path (Join-Path $WorldBackupRoot $server.Id) $world.Name
            New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target) | Out-Null
            Copy-Item -Recurse -Force $world.FullName $target
            $worldEntries.Add([ordered]@{
                server_id = [string]$server.Id
                world_name = [string]$world.Name
                backup_path = "worlds/$($server.Id)/$($world.Name)"
            })
        }
    }
    return @($worldEntries)
}

function Write-Checksums([string]$BackupPath) {
    $checksumPath = Join-Path $BackupPath "checksums.sha256"
    $prefix = $BackupPath.TrimEnd('\') + '\'
    $lines = New-Object System.Collections.Generic.List[string]
    $files = @(Get-ChildItem -Path $BackupPath -File -Recurse | Where-Object {
        $_.FullName -ne $checksumPath -and $_.Name -ne "COMPLETE"
    } | Sort-Object FullName)
    foreach ($file in $files) {
        $relative = $file.FullName.Substring($prefix.Length).Replace('\', '/')
        $hash = (Get-FileHash -Algorithm SHA256 -Path $file.FullName).Hash.ToLowerInvariant()
        $lines.Add("$hash  $relative")
    }
    $lines | Set-Content -Encoding ASCII $checksumPath
}

Require-Command "docker" "Install Docker Desktop; PostgreSQL backup uses the local compose service."
Assert-MinecraftStopped

$gitSnapshot = Get-GitSnapshot
if ($gitSnapshot.dirty -eq $true -and -not $AllowDirtyRepository) {
    throw "Repository has uncommitted changes. Commit/stash them or rerun with -AllowDirtyRepository; a clean commit is the preferred recovery identity."
}

if ([string]::IsNullOrWhiteSpace($Destination)) {
    New-Item -ItemType Directory -Force -Path $DefaultBackupRoot | Out-Null
    $BackupPath = Join-Path $DefaultBackupRoot $SnapshotId
}
else {
    $BackupPath = [System.IO.Path]::GetFullPath($Destination)
}
if (Test-Path $BackupPath) {
    if ((Get-ChildItem -Force $BackupPath | Measure-Object).Count -gt 0) {
        throw "Backup destination already exists and is not empty: $BackupPath"
    }
}
New-Item -ItemType Directory -Force -Path $BackupPath | Out-Null

$databaseDir = Join-Path $BackupPath "postgres"
$worldsDir = Join-Path $BackupPath "worlds"
$sourceDir = Join-Path $BackupPath "source"
New-Item -ItemType Directory -Force -Path $databaseDir, $worldsDir, $sourceDir | Out-Null

try {
    Write-Host "Ensuring local PostgreSQL is ready..."
    Start-PostgresReady

    $containerDump = "/tmp/minecraft-$SnapshotId.dump"
    Write-Host "Creating PostgreSQL custom-format dump..."
    Invoke-DockerCompose -Arguments @(
        "exec", "-T", "postgres",
        "pg_dump", "-U", $DatabaseUser, "-d", $DatabaseName,
        "--format=custom", "--no-owner", "--no-privileges", "--file=$containerDump"
    )
    try {
        & docker cp "${PostgresContainer}:$containerDump" (Join-Path $databaseDir "minecraft.dump")
        if ($LASTEXITCODE -ne 0) {
            throw "docker cp failed while retrieving PostgreSQL dump."
        }
    }
    finally {
        Invoke-DockerCompose -Arguments @("exec", "-T", "postgres", "rm", "-f", $containerDump)
    }

    Write-Host "Copying stopped Paper world state..."
    $worldEntries = Copy-Worlds $worldsDir

    Copy-VersionedSource (Join-Path $RepoRoot "common\src\main\resources\content") (Join-Path $sourceDir "common-content")
    Copy-VersionedSource (Join-Path $RepoRoot "paper\src\main\resources\content") (Join-Path $sourceDir "paper-content")
    Copy-VersionedSource (Join-Path $RepoRoot "common\src\main\resources\db\migration") (Join-Path $sourceDir "db-migrations")
    Copy-Item -Force (Join-Path $PSScriptRoot "settings.ps1") (Join-Path $sourceDir "local-settings.ps1")

    $manifest = [ordered]@{
        schema_version = 1
        snapshot_id = $SnapshotId
        created_at_utc = (Get-Date).ToUniversalTime().ToString("o")
        mode = "offline-coherent"
        repository = [ordered]@{
            commit = $gitSnapshot.commit
            dirty = $gitSnapshot.dirty
        }
        runtime = [ordered]@{
            paper_version = $LocalNetwork.PaperVersion
            velocity_version = $LocalNetwork.VelocityVersion
        }
        database = [ordered]@{
            engine = "postgresql"
            database = $DatabaseName
            user = $DatabaseUser
            container = $PostgresContainer
            dump = "postgres/minecraft.dump"
        }
        worlds = $worldEntries
        source_snapshots = @(
            "source/common-content",
            "source/paper-content",
            "source/db-migrations",
            "source/local-settings.ps1"
        )
    }
    $manifest | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 (Join-Path $BackupPath "manifest.json")
    Write-Checksums $BackupPath
    "complete" | Set-Content -Encoding ASCII (Join-Path $BackupPath "COMPLETE")

    Write-Host ""
    Write-Host "Coherent backup complete: $BackupPath" -ForegroundColor Green
    Write-Host "Worlds captured: $($worldEntries.Count)"
    Write-Host "Keep this directory private; it contains authoritative player/economy/world state."
}
catch {
    Write-Warning "Backup failed. The directory has no trusted COMPLETE marker and must not be restored."
    throw
}
