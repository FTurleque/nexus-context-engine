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
    Write-Host " NEXUS - Validation locale Iteration 18 / Federated Context"
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
    Write-Host "[3/3] Contexte federe, budget global, provenance et regressions"
    Invoke-Maven -Arguments @(
        "-Dtest=NexusApplicationFederatedContextTest,FederatedSearchServiceIntegrationTest,DefaultContextBuilderIntegrationTest,GoldenSearchCorpusTest,FederatedGoldenSearchCorpusTest",
        "test"
    )

    Write-Host
    Write-Host "=== VALIDATION ITERATION 18 / INCREMENT 1 ==="
    if ($FocusedOnly) {
        Write-Host "Coeur Maven                   : SKIPPED"
        Write-Host "Self-smoke                    : SKIPPED"
    }
    else {
        Write-Host "Coeur Maven                   : SUCCESS"
        Write-Host "Self-smoke                    : SUCCESS"
    }
    Write-Host "Recherche federée             : SUCCESS"
    Write-Host "Contexte federe               : SUCCESS"
    Write-Host "Budget global                 : SUCCESS"
    Write-Host "Provenance projet             : SUCCESS"
    Write-Host "Chemins identiques inter-repo : CONSERVES"
    Write-Host "Sources projet-locales        : DIFFEREES / REFUSEES"
    Write-Host "Contexte mono-projet          : SUCCESS"
    Write-Host "Corpus golden historique      : SUCCESS"
    Write-Host "Corpus golden federe          : SUCCESS"
    Write-Host "================================================"
    Write-Host
    Write-Host "VALIDATION INCREMENT 1 ITERATION 18 TERMINEE"
}
catch {
    Write-Host
    Write-Host "============================================================"
    Write-Host " VALIDATION ITERATION 18 INTERROMPUE"
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
