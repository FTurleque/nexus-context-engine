[CmdletBinding()]
param(
    [switch]$AdapterOnly
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$restPom = Join-Path $repoRoot "adapters\rest-quarkus\pom.xml"
$mcpPom = Join-Path $repoRoot "adapters\mcp-java\pom.xml"
$mcpRunner = Join-Path $repoRoot "adapters\mcp-java\target\nexus-mcp-java-0.1.0-SNAPSHOT-runner.jar"
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
    Write-Host " NEXUS - Validation locale Iteration 12 / Adaptateur MCP"
    Write-Host "============================================================"
    Write-Host

    if ($AdapterOnly) {
        Write-Host "[1/5] Build et tests du coeur NEXUS : SKIPPED (deja valide)"
        Write-Host "[2/5] Self-smoke historique du coeur : SKIPPED (deja valide)"
    }
    else {
        Write-Host "[1/5] Build et tests du coeur NEXUS"
        Invoke-Maven -Arguments @("clean", "install")

        Write-Host
        Write-Host "[2/5] Self-smoke historique du coeur"
        & (Join-Path $PSScriptRoot "self-smoke.ps1")
    }

    Write-Host
    Write-Host "[3/5] Regression de l'adaptateur REST sur la facade partagee"
    Invoke-Maven -Arguments @("-f", $restPom, "clean", "verify")

    Write-Host
    Write-Host "[4/5] Build et test d'integration MCP STDIO"
    Invoke-Maven -Arguments @("-f", $mcpPom, "clean", "verify")

    Write-Host
    Write-Host "[5/5] Verification du packaging MCP"
    if (-not (Test-Path $mcpRunner)) {
        throw "Le runner MCP attendu est introuvable : $mcpRunner"
    }

    Write-Host
    Write-Host "=== VALIDATION ADAPTATEUR MCP ==="
    if ($AdapterOnly) {
        Write-Host "Coeur Maven        : SUCCESS (deja valide)"
        Write-Host "Self-smoke         : SUCCESS (deja valide)"
    }
    else {
        Write-Host "Coeur Maven        : SUCCESS"
        Write-Host "Self-smoke         : SUCCESS"
    }
    Write-Host "Regression REST    : SUCCESS"
    Write-Host "Client MCP STDIO   : SUCCESS"
    Write-Host "Parite search_code : SUCCESS"
    Write-Host "Parite build_context: SUCCESS"
    Write-Host "Packaging MCP      : $mcpRunner"
    Write-Host "Tools              : list_projects, search_code, find_symbol, find_usages, build_context, explain_context"
    Write-Host "================================"
    Write-Host
    Write-Host "VALIDATION ITERATION 12 TERMINEE"
}
catch {
    Write-Host
    Write-Host "============================================================"
    Write-Host " VALIDATION ITERATION 12 INTERROMPUE"
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
