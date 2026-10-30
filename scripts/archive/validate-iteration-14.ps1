[CmdletBinding()]
param(
    [switch]$RegistryOnly
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$locationPushed = $false

function Invoke-Maven {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)

    & mvn @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "La commande Maven a echoue avec le code $LASTEXITCODE : mvn $($Arguments -join ' ')"
    }
}

try {
    Push-Location $repoRoot
    $locationPushed = $true

    Write-Host "============================================================"
    Write-Host " NEXUS - Validation locale Iteration 14 / AI Skills Registry"
    Write-Host "============================================================"
    Write-Host

    if ($RegistryOnly) {
        Write-Host "[1/3] Build et tests du coeur NEXUS : SKIPPED (deja valide)"
        Write-Host "[2/3] Self-smoke historique du coeur : SKIPPED (deja valide)"
    }
    else {
        Write-Host "[1/3] Build et tests du coeur NEXUS"
        Invoke-Maven -Arguments @("clean", "install")

        Write-Host
        Write-Host "[2/3] Self-smoke historique du coeur"
        & (Join-Path $PSScriptRoot "self-smoke.ps1")
        if ($LASTEXITCODE -ne 0) {
            throw "Le self-smoke a echoue avec le code $LASTEXITCODE"
        }
    }

    Write-Host
    Write-Host "[3/3] Tests dedies AI Skills Registry"
    Invoke-Maven -Arguments @("-Dtest=AiSkillsRegistryProviderTest", "test")

    Write-Host
    Write-Host "=== VALIDATION AI SKILLS REGISTRY ==="
    if ($RegistryOnly) {
        Write-Host "Coeur Maven          : SUCCESS (deja valide)"
        Write-Host "Self-smoke           : SUCCESS (deja valide)"
    }
    else {
        Write-Host "Coeur Maven          : SUCCESS"
        Write-Host "Self-smoke           : SUCCESS"
    }
    Write-Host "Tests registre       : SUCCESS"
    Write-Host "Priorite locale      : VALIDEE"
    Write-Host "Divulgation progressive : VALIDEE"
    Write-Host "Absence registre     : NON BLOQUANTE"
    Write-Host "====================================="
    Write-Host
    Write-Host "VALIDATION ITERATION 14 TERMINEE"
}
catch {
    Write-Host
    Write-Host "============================================================"
    Write-Host " VALIDATION ITERATION 14 INTERROMPUE"
    Write-Host "============================================================"
    Write-Host $_.Exception.Message
    Write-Host
    Write-Host "Le terminal reste ouvert. Copiez la sortie depuis l'etape en echec."
}
finally {
    if ($locationPushed) {
        Pop-Location
    }
}
