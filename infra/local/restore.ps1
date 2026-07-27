param(
    [Parameter(Mandatory = $true)]
    [string]$BackupPath,
    [switch]$ConfirmRestore,
    [switch]$AllowVersionMismatch
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

. (Join-Path $PSScriptRoot "settings.ps1")

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$RuntimeRoot = $LocalNetwork.RuntimeRoot
$ServersRoot = Join-Path $RuntimeRoot "servers"
$ComposeRoot = Join-Path $RepoRoot "infra\compose"
$PostgresContainer = "minecraft-postgres"
$DatabaseName = "minecraft"
$DatabaseUser = "minecraft"
$RestoreMarker = Join-Path $RuntimeRoot "restore.in-progress"

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
        throw "Refusing restore while Minecraft processes are reachable: $($open -join ', '). Stop the local network first."
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

function Get-GitState {
    $git = Get-Command "git" -ErrorAction SilentlyContinue
    if (-not $git) {
        return [ordered]@{ commit = $null; dirty = $null }
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

function Assert-Checksums([string]$Root) {
    $checksumPath = Join-Path $Root "checksums.sha256"
    if (-not (Test-Path $checksumPath)) {
        throw "Backup is missing checksums.sha256."
    }
    foreach ($line in Get-Content $checksumPath) {
        if ([string]::IsNullOrWhiteSpace($line)) {
            continue
        }
        if ($line -notmatch '^([0-9a-fA-F]{64})  (.+)$') {
            throw "Malformed checksum line: $line"
        }
        $expected = $Matches[1].ToLowerInvariant()
        $relative = $Matches[2].Replace('/', '\')
        $path = Join-Path $Root $relative
        if (-not (Test-Path $path -PathType Leaf)) {
            throw "Backup checksum references missing file: $relative"
        }
        $actual = (Get-FileHash -Algorithm SHA256 -Path $path).Hash.ToLowerInvariant()
        if ($actual -ne $expected) {
            throw "Backup checksum mismatch for $relative."
        }
    }
}

function Assert-VersionCompatibility($Manifest) {
    $backupCommit = [string]$Manifest.repository.commit
    $backupDirty = $Manifest.repository.dirty
    $current = Get-GitState

    if ($AllowVersionMismatch) {
        return
    }
    if ($backupDirty -eq $true) {
        throw "Backup was created from a dirty repository. Rerun with -AllowVersionMismatch only after intentionally selecting the code/content version that can interpret it."
    }
    if (-not [string]::IsNullOrWhiteSpace($backupCommit)) {
        if ([string]::IsNullOrWhiteSpace([string]$current.commit)) {
            throw "Could not resolve current repository commit; use -AllowVersionMismatch only after manual version verification."
        }
        if ([string]$current.commit -ne $backupCommit) {
            throw "Repository commit does not match backup. Current=$($current.commit), backup=$backupCommit. Checkout the backup commit or use -AllowVersionMismatch after explicit review."
        }
        if ($current.dirty -eq $true) {
            throw "Current repository has uncommitted changes. Restore requires the exact clean backup commit unless -AllowVersionMismatch is explicitly supplied."
        }
    }
}

function Stage-Worlds($Manifest, [string]$Root, [string]$StageRoot) {
    $staged = New-Object System.Collections.Generic.List[object]
    foreach ($world in @($Manifest.worlds)) {
        $serverId = [string]$world.server_id
        $worldName = [string]$world.world_name
        if ([string]::IsNullOrWhiteSpace($serverId) -or [string]::IsNullOrWhiteSpace($worldName)) {
            throw "Backup manifest contains an invalid world entry."
        }
        if ($serverId -match '[\\/]' -or $worldName -match '[\\/]') {
            throw "Backup manifest world path escapes are not allowed: $serverId / $worldName"
        }
        $source = Join-Path $Root ([string]$world.backup_path).Replace('/', '\')
        if (-not (Test-Path (Join-Path $source "level.dat") -PathType Leaf)) {
            throw "Backup world is missing level.dat: $source"
        }
        $destination = Join-Path (Join-Path $StageRoot $serverId) $worldName
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $destination) | Out-Null
        Copy-Item -Recurse -Force $source $destination
        if (-not (Test-Path (Join-Path $destination "level.dat") -PathType Leaf)) {
            throw "Staged world is incomplete: $destination"
        }
        $staged.Add([ordered]@{
            server_id = $serverId
            world_name = $worldName
            staged_path = $destination
        })
    }
    return @($staged)
}

function Restore-Database([string]$DumpPath, [string]$SnapshotId) {
    $containerDump = "/tmp/minecraft-restore-$SnapshotId.dump"
    & docker cp $DumpPath "$PostgresContainer`:$containerDump"
    if ($LASTEXITCODE -ne 0) {
        throw "docker cp failed while staging PostgreSQL restore dump."
    }
    try {
        Invoke-DockerCompose @(
            "exec", "-T", "postgres",
            "psql", "-U", $DatabaseUser, "-d", "postgres", "-v", "ON_ERROR_STOP=1",
            "-c", "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '$DatabaseName' AND pid <> pg_backend_pid();"
        )
        Invoke-DockerCompose @("exec", "-T", "postgres", "dropdb", "--if-exists", "-U", $DatabaseUser, $DatabaseName)
        Invoke-DockerCompose @("exec", "-T", "postgres", "createdb", "-U", $DatabaseUser, $DatabaseName)
        Invoke-DockerCompose @(
            "exec", "-T", "postgres",
            "pg_restore", "-U", $DatabaseUser, "-d", $DatabaseName,
            "--no-owner", "--no-privileges", "--exit-on-error", $containerDump
        )
    }
    finally {
        Invoke-DockerCompose @("exec", "-T", "postgres", "rm", "-f", $containerDump)
    }
}

function Install-StagedWorlds([object[]]$StagedWorlds) {
    foreach ($world in $StagedWorlds) {
        $serverRoot = Join-Path $ServersRoot $world.server_id
        $target = Join-Path $serverRoot $world.world_name
        New-Item -ItemType Directory -Force -Path $serverRoot | Out-Null
        if (Test-Path $target) {
            Remove-Item -Recurse -Force $target
        }
        Move-Item -Force $world.staged_path $target
    }
}

if (-not $ConfirmRestore) {
    throw "Restore is destructive. Rerun with -ConfirmRestore after verifying that the Minecraft network is stopped and the selected backup is correct."
}
Require-Command "docker" "Install Docker Desktop; PostgreSQL restore uses the local compose service."
Assert-MinecraftStopped

$ResolvedBackup = (Resolve-Path $BackupPath).Path
if (-not (Test-Path (Join-Path $ResolvedBackup "COMPLETE") -PathType Leaf)) {
    throw "Backup is incomplete or untrusted: COMPLETE marker is missing."
}
$manifestPath = Join-Path $ResolvedBackup "manifest.json"
if (-not (Test-Path $manifestPath -PathType Leaf)) {
    throw "Backup is missing manifest.json."
}
$manifest = Get-Content -Raw $manifestPath | ConvertFrom-Json
if ([int]$manifest.schema_version -ne 1 -or [string]$manifest.mode -ne "offline-coherent") {
    throw "Unsupported backup manifest schema/mode."
}
Assert-Checksums $ResolvedBackup
Assert-VersionCompatibility $manifest

$dumpPath = Join-Path $ResolvedBackup ([string]$manifest.database.dump).Replace('/', '\')
if (-not (Test-Path $dumpPath -PathType Leaf)) {
    throw "Backup PostgreSQL dump is missing: $dumpPath"
}

$stageRoot = Join-Path (Join-Path $RuntimeRoot "restore-staging") ([string]$manifest.snapshot_id)
if (Test-Path $stageRoot) {
    Remove-Item -Recurse -Force $stageRoot
}
New-Item -ItemType Directory -Force -Path $stageRoot | Out-Null

Write-Host "Staging world snapshot before destructive restore..."
$stagedWorlds = Stage-Worlds $manifest $ResolvedBackup $stageRoot

$marker = [ordered]@{
    snapshot_id = [string]$manifest.snapshot_id
    backup_path = $ResolvedBackup
    started_at_utc = (Get-Date).ToUniversalTime().ToString("o")
}
$marker | ConvertTo-Json | Set-Content -Encoding UTF8 $RestoreMarker

try {
    Write-Host "Ensuring local PostgreSQL is running..."
    Invoke-DockerCompose @("up", "-d", "postgres")

    Write-Host "Restoring PostgreSQL authority..."
    Restore-Database $dumpPath ([string]$manifest.snapshot_id)

    Write-Host "Installing staged Paper worlds..."
    Install-StagedWorlds $stagedWorlds

    if (Test-Path $stageRoot) {
        Remove-Item -Recurse -Force $stageRoot
    }
    Remove-Item -Force $RestoreMarker

    Write-Host ""
    Write-Host "Restore completed: $($manifest.snapshot_id)" -ForegroundColor Green
    Write-Host "Next: start the local network, reconnect through Velocity, then run /integrity 100 and the representative recovery acceptance checks."
}
catch {
    Write-Warning "Restore did not complete. The Minecraft network must remain stopped."
    Write-Warning "The restore.in-progress marker was intentionally retained; rerun this restore until it completes before starting the network."
    throw
}
