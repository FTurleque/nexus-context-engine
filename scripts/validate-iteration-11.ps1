[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$adapterPom = Join-Path $repoRoot "adapters\rest-quarkus\pom.xml"
$adapterTarget = Join-Path $repoRoot "adapters\rest-quarkus\target"
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
    Write-Host " NEXUS - Validation locale Iteration 11 / Adaptateur API"
    Write-Host "============================================================"
    Write-Host

    Write-Host "[1/4] Build et tests du coeur NEXUS"
    Invoke-Maven -Arguments @("clean", "install")

    Write-Host
    Write-Host "[2/4] Self-smoke historique du coeur"
    & (Join-Path $PSScriptRoot "self-smoke.ps1")

    Write-Host
    Write-Host "[3/4] Build et tests HTTP de l'adaptateur Quarkus"
    Invoke-Maven -Arguments @("-f", $adapterPom, "clean", "verify")

    Write-Host
    Write-Host "[4/4] Verification du packaging Quarkus"
    $quarkusRunner = Join-Path $adapterTarget "quarkus-app\quarkus-run.jar"
    if (-not (Test-Path $quarkusRunner)) {
        throw "Le runner Quarkus attendu est introuvable : $quarkusRunner"
    }

    Write-Host
    Write-Host "=== VALIDATION ADAPTATEUR API ==="
    Write-Host "Coeur Maven       : SUCCESS"
    Write-Host "Self-smoke        : SUCCESS"
    Write-Host "Tests REST Quarkus: SUCCESS"
    Write-Host "Packaging Quarkus : $quarkusRunner"
    Write-Host "Health            : /q/health/ready"
    Write-Host "Metrics           : /q/metrics"
    Write-Host "API               : /api/v1/projects"
    Write-Host "================================="
    Write-Host
    Write-Host "VALIDATION ITERATION 11 TERMINEE"
}
catch {
    Write-Host
    Write-Host "============================================================"
    Write-Host " VALIDATION ITERATION 11 INTERROMPUE"
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
