param(
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

. (Join-Path $PSScriptRoot "settings.ps1")

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$RuntimeRoot = $LocalNetwork.RuntimeRoot
$Downloads = Join-Path $RuntimeRoot "downloads"
$Tools = Join-Path $RuntimeRoot "tools"
$VelocityRoot = Join-Path $RuntimeRoot "velocity"
$ServersRoot = Join-Path $RuntimeRoot "servers"
$UserAgent = "KevinRabbe-Minecraft-server/0.1 (https://github.com/KevinRabbe/Minecraft-server)"

function Require-Command([string]$Name, [string]$Help) {
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "$Name was not found. $Help"
    }
}

function Assert-Java25 {
    Require-Command "java" "Install Java 25 and make java available on PATH."
    $versionText = (& java -version 2>&1 | Out-String)
    if ($versionText -notmatch 'version "25(?:\.|\")') {
        throw "Java 25 is required. Current java reports:`n$versionText"
    }
}

function Get-GradleExecutable {
    $wrapper = Join-Path $RepoRoot "gradlew.bat"
    if (Test-Path $wrapper) {
        return $wrapper
    }

    $installed = Get-Command "gradle" -ErrorAction SilentlyContinue
    if ($installed) {
        return $installed.Source
    }

    $version = "9.1.0"
    $zip = Join-Path $Downloads "gradle-$version-bin.zip"
    $home = Join-Path $Tools "gradle-$version"
    $exe = Join-Path $home "bin\gradle.bat"

    if (-not (Test-Path $exe)) {
        New-Item -ItemType Directory -Force -Path $Downloads, $Tools | Out-Null
        if (-not (Test-Path $zip)) {
            Write-Host "Downloading Gradle $version..."
            Invoke-WebRequest -UseBasicParsing -Uri "https://services.gradle.org/distributions/gradle-$version-bin.zip" -OutFile $zip
        }
        Write-Host "Extracting Gradle $version..."
        Expand-Archive -Path $zip -DestinationPath $Tools -Force
    }

    return $exe
}

function Get-PaperMcJar([string]$Project, [string]$Version, [string]$Destination) {
    if (Test-Path $Destination) {
        return
    }

    $headers = @{ "User-Agent" = $UserAgent }
    $buildsUri = "https://fill.papermc.io/v3/projects/$Project/versions/$Version/builds"
    Write-Host "Resolving stable $Project $Version..."
    $builds = Invoke-RestMethod -Headers $headers -Uri $buildsUri
    $stable = @($builds | Where-Object { $_.channel -eq "STABLE" }) | Select-Object -First 1
    if (-not $stable) {
        throw "No STABLE build exists for $Project $Version. Refusing to use an experimental build."
    }

    $download = $stable.downloads.'server:default'.url
    if (-not $download) {
        throw "PaperMC did not return a server download URL for $Project $Version."
    }

    Write-Host "Downloading $Project $Version build $($stable.id)..."
    Invoke-WebRequest -UseBasicParsing -Headers $headers -Uri $download -OutFile $Destination
}

function New-ForwardingSecret {
    $bytes = New-Object byte[] 32
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $rng.GetBytes($bytes)
    }
    finally {
        $rng.Dispose()
    }
    return -join ($bytes | ForEach-Object { $_.ToString("x2") })
}

function Write-ServerProperties([string]$Path, [hashtable]$Server) {
    @"
server-ip=127.0.0.1
server-port=$($Server.Port)
online-mode=false
motd=Local $($Server.Role) backend
max-players=20
spawn-protection=0
view-distance=6
simulation-distance=4
enable-query=false
enable-rcon=false
pause-when-empty-seconds=-1
"@ | Set-Content -Encoding UTF8 $Path
}

function Bootstrap-Paper([string]$Directory, [hashtable]$Server) {
    $globalConfig = Join-Path $Directory "config\paper-global.yml"
    if (Test-Path $globalConfig) {
        return
    }

    Write-Host "Bootstrapping $($Server.Id) once so Paper generates its configuration..."
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = "java"
    $psi.Arguments = "-Xms256M -Xmx512M -jar server.jar nogui"
    $psi.WorkingDirectory = $Directory
    $psi.UseShellExecute = $false
    $psi.RedirectStandardInput = $true
    $psi.EnvironmentVariables["SERVER_ID"] = $Server.Id
    $psi.EnvironmentVariables["SERVER_ROLE"] = $Server.Role

    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $psi
    [void]$process.Start()

    $deadline = (Get-Date).AddMinutes(2)
    while (-not $process.HasExited -and -not (Test-Path $globalConfig)) {
        if ((Get-Date) -gt $deadline) {
            try { $process.StandardInput.WriteLine("stop") } catch {}
            throw "Timed out while bootstrapping $($Server.Id)."
        }
        Start-Sleep -Milliseconds 500
    }

    if (-not $process.HasExited) {
        Start-Sleep -Seconds 2
        $process.StandardInput.WriteLine("stop")
        if (-not $process.WaitForExit(30000)) {
            $process.Kill()
            throw "$($Server.Id) did not stop cleanly after bootstrap."
        }
    }

    if (-not (Test-Path $globalConfig)) {
        throw "$($Server.Id) exited before generating config\paper-global.yml."
    }
}

function Set-PaperVelocityForwarding([string]$Path, [string]$Secret) {
    $lines = @(Get-Content $Path)
    $insideVelocity = $false
    $foundEnabled = $false
    $foundOnline = $false
    $foundSecret = $false

    for ($i = 0; $i -lt $lines.Count; $i++) {
        $line = $lines[$i]

        if ($insideVelocity -and $line -match '^  \S' -and $line -notmatch '^  velocity:\s*$') {
            $insideVelocity = $false
        }
        if ($line -match '^  velocity:\s*$') {
            $insideVelocity = $true
            continue
        }
        if (-not $insideVelocity) {
            continue
        }

        if ($line -match '^    enabled:') {
            $lines[$i] = '    enabled: true'
            $foundEnabled = $true
        }
        elseif ($line -match '^    online-mode:') {
            $lines[$i] = '    online-mode: true'
            $foundOnline = $true
        }
        elseif ($line -match '^    secret:') {
            $lines[$i] = "    secret: '$Secret'"
            $foundSecret = $true
        }
    }

    if (-not ($foundEnabled -and $foundOnline -and $foundSecret)) {
        throw "Could not locate the expected proxies.velocity settings in $Path. Paper's config format may have changed."
    }

    $lines | Set-Content -Encoding UTF8 $Path
}

function Write-VelocityConfig([string]$Path) {
    $serverLines = @()
    foreach ($server in $LocalNetwork.Servers) {
        $serverLines += "$($server.Id) = \"127.0.0.1:$($server.Port)\""
    }
    $serverBlock = $serverLines -join "`r`n"

    @"
# Generated by infra/local/setup.ps1 for local development.
config-version = "2.8"
bind = "$($LocalNetwork.ProxyHost):$($LocalNetwork.ProxyPort)"
motd = "<#5fd7ff>Minecraft Server - Local Dev"
show-max-players = 100
online-mode = true
force-key-authentication = true
prevent-client-proxy-connections = false
player-info-forwarding-mode = "modern"
forwarding-secret-file = "forwarding.secret"
announce-forge = false
kick-existing-players = false
ping-passthrough = "disabled"
sample-players-in-ping = false
enable-player-address-logging = true

[servers]
$serverBlock
try = ["city-01"]

[forced-hosts]

[query]
enabled = false
port = $($LocalNetwork.ProxyPort)
map = "Local Dev"
show-plugins = false
"@ | Set-Content -Encoding UTF8 $Path
}

function Build-And-DeployPlugins {
    $gradle = Get-GradleExecutable
    Write-Host "Building plugins..."
    Push-Location $RepoRoot
    try {
        & $gradle clean shadowJar
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle build failed with exit code $LASTEXITCODE."
        }
    }
    finally {
        Pop-Location
    }

    $paperPlugin = Get-ChildItem (Join-Path $RepoRoot "paper\build\libs\*.jar") |
        Sort-Object Length -Descending |
        Select-Object -First 1
    $velocityPlugin = Get-ChildItem (Join-Path $RepoRoot "velocity\build\libs\*.jar") |
        Sort-Object Length -Descending |
        Select-Object -First 1

    if (-not $paperPlugin -or -not $velocityPlugin) {
        throw "Expected shaded Paper and Velocity plugin JARs were not produced."
    }

    foreach ($server in $LocalNetwork.Servers) {
        $plugins = Join-Path $ServersRoot "$($server.Id)\plugins"
        New-Item -ItemType Directory -Force -Path $plugins | Out-Null
        Copy-Item -Force $paperPlugin.FullName (Join-Path $plugins "MinecraftServer.jar")
    }

    $velocityPlugins = Join-Path $VelocityRoot "plugins"
    New-Item -ItemType Directory -Force -Path $velocityPlugins | Out-Null
    Copy-Item -Force $velocityPlugin.FullName (Join-Path $velocityPlugins "MinecraftServerVelocity.jar")
}

Assert-Java25
Require-Command "docker" "Install Docker Desktop; local PostgreSQL is started through Docker Compose."

New-Item -ItemType Directory -Force -Path $RuntimeRoot, $Downloads, $Tools, $VelocityRoot, $ServersRoot | Out-Null

$paperJar = Join-Path $Downloads "paper-$($LocalNetwork.PaperVersion).jar"
$velocityJar = Join-Path $Downloads "velocity-$($LocalNetwork.VelocityVersion).jar"
Get-PaperMcJar "paper" $LocalNetwork.PaperVersion $paperJar
Get-PaperMcJar "velocity" $LocalNetwork.VelocityVersion $velocityJar

$secretPath = Join-Path $VelocityRoot "forwarding.secret"
if (-not (Test-Path $secretPath)) {
    New-ForwardingSecret | Set-Content -NoNewline -Encoding UTF8 $secretPath
}
$secret = (Get-Content -Raw $secretPath).Trim()

Copy-Item -Force $velocityJar (Join-Path $VelocityRoot "velocity.jar")
Write-VelocityConfig (Join-Path $VelocityRoot "velocity.toml")

foreach ($server in $LocalNetwork.Servers) {
    $directory = Join-Path $ServersRoot $server.Id
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
    Copy-Item -Force $paperJar (Join-Path $directory "server.jar")
    "eula=true" | Set-Content -Encoding ASCII (Join-Path $directory "eula.txt")
    Write-ServerProperties (Join-Path $directory "server.properties") $server

    Bootstrap-Paper $directory $server
    Set-PaperVelocityForwarding (Join-Path $directory "config\paper-global.yml") $secret

    $spigot = Join-Path $directory "spigot.yml"
    if (Test-Path $spigot) {
        $text = Get-Content -Raw $spigot
        $text = $text -replace '(?m)^(\s*bungeecord:\s*)true\s*$', '${1}false'
        Set-Content -Encoding UTF8 $spigot $text
    }
}

if (-not $SkipBuild) {
    Build-And-DeployPlugins
}

Write-Host "Starting local PostgreSQL..."
Push-Location (Join-Path $RepoRoot "infra\compose")
try {
    & docker compose up -d postgres
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose failed with exit code $LASTEXITCODE."
    }
}
finally {
    Pop-Location
}

Write-Host ""
Write-Host "Local network setup is complete." -ForegroundColor Green
Write-Host "Run: .\infra\local\start.ps1"
Write-Host "Connect Minecraft to: localhost:$($LocalNetwork.ProxyPort)"
