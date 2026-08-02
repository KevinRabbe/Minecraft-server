param(
    [Parameter(Mandatory = $true)]
    [string]$EvidencePath,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[a-z0-9][a-z0-9-]{0,62}$')]
    [string]$Label
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

. (Join-Path $PSScriptRoot "settings.ps1")

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$ComposeRoot = Join-Path $RepoRoot "infra\compose"
$ResolvedEvidencePath = [System.IO.Path]::GetFullPath($EvidencePath)
$SnapshotPath = Join-Path $ResolvedEvidencePath ("authority-" + $Label)

function Require-Command([string]$Name, [string]$Help) {
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "$Name was not found. $Help"
    }
}

function Invoke-ReadOnlyCsv(
    [string]$OutputPath,
    [string]$Query,
    [string]$Description
) {
    Push-Location $ComposeRoot
    try {
        $output = (& docker compose exec -T postgres `
            psql -X -v ON_ERROR_STOP=1 -U minecraft -d minecraft `
            -c "COPY ($Query) TO STDOUT WITH (FORMAT CSV, HEADER TRUE)" 2>&1 | Out-String)
        $exitCode = $LASTEXITCODE
    }
    finally {
        Pop-Location
    }

    if ($exitCode -ne 0) {
        throw "$Description failed with exit code $exitCode. Output: $output"
    }

    $output | Set-Content -Encoding UTF8 $OutputPath
}

if ([System.Environment]::OSVersion.Platform -ne [System.PlatformID]::Win32NT) {
    throw "Runtime authority evidence must be captured on the Windows development machine running the rehearsal."
}

Require-Command "git" "Install Git and run from the qualified repository checkout."
Require-Command "docker" "Install and start Docker Desktop."

if (-not (Test-Path $ResolvedEvidencePath -PathType Container)) {
    throw "Evidence directory does not exist: $ResolvedEvidencePath"
}
if (Test-Path $SnapshotPath) {
    throw "Authority snapshot already exists for label '$Label': $SnapshotPath"
}

Push-Location $RepoRoot
try {
    $commit = (& git rev-parse HEAD 2>$null | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($commit)) {
        throw "Could not resolve the current repository commit."
    }
    $gitStatus = (& git status --porcelain --untracked-files=normal 2>$null | Out-String)
    if ($LASTEXITCODE -ne 0) {
        throw "Could not inspect repository status."
    }
    if (-not [string]::IsNullOrWhiteSpace($gitStatus)) {
        throw "The repository is dirty. Authority evidence must identify one exact clean commit."
    }
}
finally {
    Pop-Location
}

New-Item -ItemType Directory -Path $SnapshotPath | Out-Null

try {
    Invoke-ReadOnlyCsv `
        (Join-Path $SnapshotPath "backends.csv") `
        @"
SELECT
    backend_id,
    incarnation_id,
    status,
    started_at,
    last_heartbeat_at,
    player_count
FROM backends
ORDER BY backend_id
"@ `
        "Backend authority query"

    Invoke-ReadOnlyCsv `
        (Join-Path $SnapshotPath "zones.csv") `
        @"
SELECT
    instance_id,
    backend_id,
    backend_incarnation_id,
    zone_id,
    status,
    last_heartbeat_at,
    player_count
FROM zone_instances
ORDER BY backend_id, zone_id, instance_id
"@ `
        "Zone authority query"

    Invoke-ReadOnlyCsv `
        (Join-Path $SnapshotPath "live-sessions.csv") `
        @"
SELECT
    network_session_id,
    player_id,
    owner_backend_id,
    owner_backend_incarnation_id,
    status,
    lease_expires_at,
    state_version
FROM player_sessions
WHERE status IN ('ACTIVE', 'RECOVERING', 'TRANSFERRING')
ORDER BY owner_backend_id, player_id, network_session_id
"@ `
        "Live session authority query"

    $violationsPath = Join-Path $SnapshotPath "authority-violations.csv"
    Invoke-ReadOnlyCsv `
        $violationsPath `
        @"
SELECT
    'ZONE_INCARNATION_MISMATCH'::text AS violation_kind,
    zone_instance.instance_id::text AS authority_id,
    zone_instance.backend_id,
    zone_instance.backend_incarnation_id::text AS stored_incarnation,
    backend.incarnation_id::text AS current_incarnation,
    ('zone status=' || zone_instance.status || ', zone_id=' || zone_instance.zone_id)::text AS detail
FROM zone_instances zone_instance
LEFT JOIN backends backend
  ON backend.backend_id = zone_instance.backend_id
WHERE zone_instance.status <> 'STOPPED'
  AND (
      backend.backend_id IS NULL
      OR backend.incarnation_id IS DISTINCT FROM zone_instance.backend_incarnation_id
  )
UNION ALL
SELECT
    'VALID_SESSION_INCARNATION_MISMATCH'::text AS violation_kind,
    session.network_session_id::text AS authority_id,
    session.owner_backend_id AS backend_id,
    session.owner_backend_incarnation_id::text AS stored_incarnation,
    backend.incarnation_id::text AS current_incarnation,
    ('session status=' || session.status || ', player_id=' || session.player_id)::text AS detail
FROM player_sessions session
LEFT JOIN backends backend
  ON backend.backend_id = session.owner_backend_id
WHERE session.status IN ('ACTIVE', 'RECOVERING', 'TRANSFERRING')
  AND session.lease_expires_at > NOW()
  AND (
      session.owner_backend_id IS NULL
      OR session.owner_backend_incarnation_id IS NULL
      OR backend.backend_id IS NULL
      OR backend.incarnation_id IS DISTINCT FROM session.owner_backend_incarnation_id
  )
ORDER BY violation_kind, backend_id, authority_id
"@ `
        "Runtime authority invariant query"

    $violations = @(Import-Csv -Path $violationsPath)
    $fileNames = @("backends.csv", "zones.csv", "live-sessions.csv", "authority-violations.csv")
    $files = $fileNames | ForEach-Object {
        $path = Join-Path $SnapshotPath $_
        [ordered]@{
            path = $_
            sha256 = (Get-FileHash -Algorithm SHA256 -Path $path).Hash.ToLowerInvariant()
        }
    }

    [ordered]@{
        schema_version = 2
        label = $Label
        captured_at_utc = (Get-Date).ToUniversalTime().ToString("o")
        repository_commit = $commit
        read_only = $true
        authority_status = if ($violations.Count -eq 0) { "NO_AUTOMATED_VIOLATIONS" } else { "VIOLATIONS_DETECTED" }
        violation_count = $violations.Count
        files = @($files)
        interpretation = [ordered]@{
            backend = "Only the current backend incarnation may publish lifecycle writes."
            zone = "Every non-stopped zone must belong to the current backend incarnation."
            session = "Every unexpired live session lease must belong to the current backend incarnation. Expired transfer handoffs are intentionally not violations."
        }
    } | ConvertTo-Json -Depth 6 | Set-Content -Encoding UTF8 (Join-Path $SnapshotPath "manifest.json")

    Write-Host "Runtime authority snapshot captured: $SnapshotPath" -ForegroundColor Green
    Write-Host "Repository commit: $commit"
    Write-Host "Automated authority violations: $($violations.Count)"
    if ($violations.Count -gt 0) {
        Write-Warning "Runtime incarnation authority violations were detected. Inspect authority-violations.csv; the rehearsal cannot pass while they remain unexplained."
    }
    Write-Warning "This command is read-only and does not prove restore acceptance by itself. Compare snapshots with the observed process timeline."
}
catch {
    Remove-Item -Recurse -Force $SnapshotPath -ErrorAction SilentlyContinue
    throw
}
