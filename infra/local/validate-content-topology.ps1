param(
    [string]$SettingsPath = (Join-Path $PSScriptRoot "settings.ps1"),
    [string]$ResourceDefinitionsPath = (Join-Path $PSScriptRoot "..\..\common\src\main\resources\content\resource-sources.json"),
    [string]$BlockPlacementsPath = (Join-Path $PSScriptRoot "..\..\paper\src\main\resources\content\resource-source-placements.json"),
    [string]$EntityPlacementsPath = (Join-Path $PSScriptRoot "..\..\paper\src\main\resources\content\resource-entity-placements.json"),
    [string]$BountyContentPath = (Join-Path $PSScriptRoot "..\..\common\src\main\resources\content\bounties.json"),
    [string]$BountyBossPlacementsPath = (Join-Path $PSScriptRoot "..\..\paper\src\main\resources\content\bounty-boss-placements.json")
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Resolve-RequiredFile([string]$Path, [string]$Description) {
    if ([string]::IsNullOrWhiteSpace($Path)) {
        throw "$Description path must not be blank."
    }
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "$Description file does not exist: $Path"
    }
    return (Resolve-Path -LiteralPath $Path).Path
}

function Read-JsonFile([string]$Path, [string]$Description) {
    try {
        return Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json
    }
    catch {
        throw "Could not parse $Description JSON at ${Path}: $($_.Exception.Message)"
    }
}

$SettingsPath = Resolve-RequiredFile $SettingsPath "Local settings"
$ResourceDefinitionsPath = Resolve-RequiredFile $ResourceDefinitionsPath "Resource definition catalog"
$BlockPlacementsPath = Resolve-RequiredFile $BlockPlacementsPath "Block resource placement catalog"
$EntityPlacementsPath = Resolve-RequiredFile $EntityPlacementsPath "Entity resource placement catalog"
$BountyContentPath = Resolve-RequiredFile $BountyContentPath "Bounty content catalog"
$BountyBossPlacementsPath = Resolve-RequiredFile $BountyBossPlacementsPath "Bounty boss placement catalog"

. $SettingsPath

if (-not (Get-Variable -Name LocalNetwork -Scope Script -ErrorAction SilentlyContinue)) {
    throw "Local settings did not define `$LocalNetwork: $SettingsPath"
}
if ($null -eq $LocalNetwork.Servers) {
    throw "Local settings must define LocalNetwork.Servers."
}

$resourceCatalog = Read-JsonFile $ResourceDefinitionsPath "resource definition catalog"
$blockCatalog = Read-JsonFile $BlockPlacementsPath "block resource placement catalog"
$entityCatalog = Read-JsonFile $EntityPlacementsPath "entity resource placement catalog"
$bountyCatalog = Read-JsonFile $BountyContentPath "bounty content catalog"
$bountyBossCatalog = Read-JsonFile $BountyBossPlacementsPath "bounty boss placement catalog"

$definitions = @($resourceCatalog.sources)
$blockPlacements = @($blockCatalog.placements)
$entityPlacements = @($entityCatalog.placements)
$bountyTiers = @($bountyCatalog.tiers)
$bountyBossPlacements = @($bountyBossCatalog.placements)

foreach ($server in @($LocalNetwork.Servers)) {
    $requireResourceContent = $false
    if ($server.ContainsKey("RequireResourceContent")) {
        $requireResourceContent = [bool]$server.RequireResourceContent
    }
    $requireBountyBossContent = $false
    if ($server.ContainsKey("RequireBountyBossContent")) {
        $requireBountyBossContent = [bool]$server.RequireBountyBossContent
    }
    if (-not $requireResourceContent -and -not $requireBountyBossContent) {
        continue
    }

    $serverId = [string]$server.Id
    $zoneId = [string]$server.Zone
    $templateVersion = [string]$server.ZoneTemplate
    if ([string]::IsNullOrWhiteSpace($serverId)) {
        throw "A local server requiring authored content has a blank Id."
    }
    if ([string]::IsNullOrWhiteSpace($zoneId)) {
        throw "Local server $serverId requires authored content but has a blank Zone."
    }
    if ([string]::IsNullOrWhiteSpace($templateVersion)) {
        throw "Local server $serverId requires authored content but has a blank ZoneTemplate."
    }

    if ($requireResourceContent) {
        $matchingDefinitions = @($definitions | Where-Object {
            [string]$_.zone_id -eq $zoneId -and [string]$_.template_version -eq $templateVersion
        })
        if ($matchingDefinitions.Count -eq 0) {
            $knownTemplates = @($definitions | Where-Object {
                [string]$_.zone_id -eq $zoneId
            } | Select-Object -ExpandProperty template_version -Unique)
            $knownText = if ($knownTemplates.Count -eq 0) { "none" } else { $knownTemplates -join ", " }
            throw "Local server $serverId requires resource content for zone '$zoneId' template '$templateVersion', but no matching source definition exists. Known templates for this zone: $knownText."
        }

        $matchingBlockPlacements = @($blockPlacements | Where-Object {
            [string]$_.zone_id -eq $zoneId -and [string]$_.template_version -eq $templateVersion
        })
        $matchingEntityPlacements = @($entityPlacements | Where-Object {
            [string]$_.zone_id -eq $zoneId -and [string]$_.template_version -eq $templateVersion
        })
        $matchingPlacements = @($matchingBlockPlacements) + @($matchingEntityPlacements)
        if ($matchingPlacements.Count -eq 0) {
            throw "Local server $serverId requires resource content for zone '$zoneId' template '$templateVersion', but no physical block/entity placement exists."
        }

        foreach ($definition in $matchingDefinitions) {
            $definitionId = [string]$definition.definition_id
            $physical = @($matchingPlacements | Where-Object {
                [string]$_.definition_id -eq $definitionId
            })
            if ($physical.Count -eq 0) {
                throw "Resource definition '$definitionId' is selected by local server $serverId but has no physical placement for zone '$zoneId' template '$templateVersion'."
            }
        }

        foreach ($placement in $matchingPlacements) {
            $definitionId = [string]$placement.definition_id
            $definition = @($matchingDefinitions | Where-Object {
                [string]$_.definition_id -eq $definitionId
            })
            if ($definition.Count -ne 1) {
                throw "Physical resource placement '$definitionId' for local server $serverId does not resolve to exactly one matching definition for zone '$zoneId' template '$templateVersion'."
            }
        }

        Write-Host "Validated resource topology for $serverId ($zoneId/$templateVersion): $($matchingDefinitions.Count) definition(s), $($matchingPlacements.Count) placement(s)."
    }

    if ($requireBountyBossContent) {
        $matchingBossPlacements = @($bountyBossPlacements | Where-Object {
            [string]$_.zone_id -eq $zoneId -and [string]$_.template_version -eq $templateVersion
        })
        if ($matchingBossPlacements.Count -eq 0) {
            throw "Local server $serverId requires Bounty boss content for zone '$zoneId' template '$templateVersion', but no matching boss placement exists."
        }

        foreach ($placement in $matchingBossPlacements) {
            $bossDefinitionId = [string]$placement.boss_definition_id
            $matchingTiers = @($bountyTiers | Where-Object {
                [string]$_.boss_definition_id -eq $bossDefinitionId
            })
            if ($matchingTiers.Count -eq 0) {
                throw "Bounty boss placement '$bossDefinitionId' for local server $serverId is not referenced by any loaded bounty tier."
            }
        }

        $duplicateBossIds = @($matchingBossPlacements |
            Group-Object -Property boss_definition_id |
            Where-Object { $_.Count -gt 1 })
        if ($duplicateBossIds.Count -gt 0) {
            $duplicates = @($duplicateBossIds | ForEach-Object { [string]$_.Name }) -join ", "
            throw "Local server $serverId has duplicate Bounty boss placements for: $duplicates"
        }

        Write-Host "Validated Bounty boss topology for $serverId ($zoneId/$templateVersion): $($matchingBossPlacements.Count) placement(s)."
    }
}
