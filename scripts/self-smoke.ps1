[CmdletBinding()]
param(
    [string]$ProjectName = "nexus-context-engine-self-smoke",
    [switch]$KeepData
)

$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$smokeHome = Join-Path $repoRoot "target\nexus-self-smoke-home"
$previousNexusHome = $env:NEXUS_HOME

function Invoke-Maven {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments
    )

    & mvn @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "La commande Maven a échoué avec le code $LASTEXITCODE : mvn $($Arguments -join ' ')"
    }
}

function Invoke-Nexus {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Arguments
    )

    $output = & mvn -q exec:java "-Dexec.args=$Arguments" 2>&1
    $exitCode = $LASTEXITCODE
    $text = ($output | Out-String).TrimEnd()

    if (-not [string]::IsNullOrWhiteSpace($text)) {
        Write-Host $text
    }

    if ($exitCode -ne 0) {
        throw "La CLI NEXUS a échoué avec le code $exitCode pour : $Arguments"
    }

    return $text
}

try {
    Push-Location $repoRoot

    if (Test-Path $smokeHome) {
        Remove-Item -Recurse -Force $smokeHome
    }
    New-Item -ItemType Directory -Path $smokeHome -Force | Out-Null
    $env:NEXUS_HOME = $smokeHome

    Write-Host "=== NEXUS self-smoke ==="
    Write-Host "Repository : $repoRoot"
    Write-Host "NEXUS_HOME : $smokeHome"
    Write-Host

    Write-Host "[1/6] Compilation de la CLI"
    Invoke-Maven -Arguments @("-q", "-DskipTests", "compile")

    Write-Host "[2/6] Enregistrement du repository NEXUS"
    $registration = Invoke-Nexus -Arguments "project add . $ProjectName"
    if ($registration -notmatch [regex]::Escape($ProjectName)) {
        throw "Le projet '$ProjectName' n'apparaît pas dans la sortie de project add."
    }

    Write-Host "[3/6] Vérification du registre"
    $projectList = Invoke-Nexus -Arguments "project list"
    if ($projectList -notmatch [regex]::Escape($ProjectName)) {
        throw "Le projet '$ProjectName' n'apparaît pas dans project list."
    }

    Write-Host "[4/6] Première indexation complète"
    $firstIndex = Invoke-Nexus -Arguments "index $ProjectName"
    if ($firstIndex -notmatch "\b([1-9]\d*)\s+modifiés\b") {
        throw "La première indexation devait indexer au moins un fichier modifié."
    }

    Write-Host "[5/6] Deuxième indexation incrémentale"
    $secondIndex = Invoke-Nexus -Arguments "index $ProjectName"
    if ($secondIndex -notmatch "\b0\s+modifiés,\s+0\s+supprimés\b") {
        throw "La deuxième indexation devait être idempotente : 0 fichier modifié et 0 fichier supprimé."
    }

    Write-Host "[6/6] Inspection de l'index"
    $inspection = Invoke-Nexus -Arguments "inspect $ProjectName"
    if ($inspection -notmatch "\bREADY\b") {
        throw "Le projet devait être dans l'état READY après indexation."
    }
    if ($inspection -notmatch "Index\s*:\s+([1-9]\d*)\s+fichiers,\s+([1-9]\d*)\s+symboles,\s+(\d+)\s+relations") {
        throw "L'inspection devait contenir au moins un fichier et un symbole indexés."
    }

    Write-Host
    Write-Host "SELF-SMOKE SUCCESS"
    Write-Host "NEXUS a enregistré, indexé deux fois puis inspecté son propre repository avec succès."
}
finally {
    Pop-Location

    if ($null -eq $previousNexusHome) {
        Remove-Item Env:NEXUS_HOME -ErrorAction SilentlyContinue
    }
    else {
        $env:NEXUS_HOME = $previousNexusHome
    }

    if (-not $KeepData -and (Test-Path $smokeHome)) {
        Remove-Item -Recurse -Force $smokeHome
    }
    elseif ($KeepData) {
        Write-Host "Données de smoke test conservées dans : $smokeHome"
    }
}
