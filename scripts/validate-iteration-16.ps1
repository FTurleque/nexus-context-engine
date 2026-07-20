[CmdletBinding()]
param(
    [switch]$FocusedOnly
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
    Write-Host " NEXUS - Validation locale Iteration 16 / Large Scale Search"
    Write-Host "============================================================"
    Write-Host

    if ($FocusedOnly) {
        Write-Host "[1/3] Build et tests du coeur NEXUS : SKIPPED"
        Write-Host "[2/3] Self-smoke historique du coeur : SKIPPED"
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
    Write-Host "[3/3] Tests dedies recherche federee et baselines qualite"
    Invoke-Maven -Arguments @(
        "-Dtest=FederatedSearchServiceIntegrationTest,FederatedGoldenSearchCorpusTest,GoldenSearchCorpusTest",
        "test"
    )

    Write-Host
    Write-Host "=== VALIDATION ITERATION 16 - PREMIER INCREMENT ==="
    if ($FocusedOnly) {
        Write-Host "Coeur Maven              : SKIPPED"
        Write-Host "Self-smoke               : SKIPPED"
    }
    else {
        Write-Host "Coeur Maven              : SUCCESS"
        Write-Host "Self-smoke               : SUCCESS"
    }
    Write-Host "Recherche multi-projet   : SUCCESS"
    Write-Host "Provenance projectId     : VALIDEE PAR TEST"
    Write-Host "Corpus golden historique : SUCCESS"
    Write-Host "Corpus golden federe     : SUCCESS"
    Write-Host "Moteur externe           : NON INTRODUIT"
    Write-Host "===================================================="
    Write-Host
    Write-Host "VALIDATION ITERATION 16 - PREMIER INCREMENT TERMINEE"
}
catch {
    Write-Host
    Write-Host "============================================================"
    Write-Host " VALIDATION ITERATION 16 INTERROMPUE"
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
