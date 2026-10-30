[CmdletBinding()]
param(
    [switch]$AdapterOnly
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$mcpPom = Join-Path $repoRoot "adapters\mcp-java\pom.xml"
$integrationPom = Join-Path $repoRoot "adapters\assistant-clients\pom.xml"
$mcpRunner = Join-Path $repoRoot "adapters\mcp-java\target\nexus-mcp-java-0.1.0-SNAPSHOT-runner.jar"
$integrationRunner = Join-Path $repoRoot "adapters\assistant-clients\target\nexus-assistant-clients-0.1.0-SNAPSHOT-runner.jar"
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
    Write-Host " NEXUS - Validation locale Iteration 13 / Copilot et Claude"
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
    Write-Host "[3/5] Regression du serveur MCP NEXUS"
    Invoke-Maven -Arguments @("-f", $mcpPom, "clean", "verify")

    Write-Host
    Write-Host "[4/5] Build et tests du generateur Copilot / Claude"
    Invoke-Maven -Arguments @("-f", $integrationPom, "clean", "verify")

    Write-Host
    Write-Host "[5/5] Verification des runners"
    if (-not (Test-Path $mcpRunner)) {
        throw "Le runner MCP attendu est introuvable : $mcpRunner"
    }
    if (-not (Test-Path $integrationRunner)) {
        throw "Le runner d'integration attendu est introuvable : $integrationRunner"
    }

    Write-Host
    Write-Host "=== VALIDATION ADAPTATEURS COPILOT / CLAUDE ==="
    if ($AdapterOnly) {
        Write-Host "Coeur Maven          : SUCCESS (deja valide)"
        Write-Host "Self-smoke           : SUCCESS (deja valide)"
    }
    else {
        Write-Host "Coeur Maven          : SUCCESS"
        Write-Host "Self-smoke           : SUCCESS"
    }
    Write-Host "Regression MCP       : SUCCESS"
    Write-Host "Tests integrations   : SUCCESS"
    Write-Host "Copilot CLI          : profile genere"
    Write-Host "Copilot JetBrains    : profile genere"
    Write-Host "Claude project       : profile genere"
    Write-Host "Claude user          : profile genere"
    Write-Host "Runner MCP           : $mcpRunner"
    Write-Host "Runner integrations  : $integrationRunner"
    Write-Host "================================================"
    Write-Host
    Write-Host "VALIDATION ITERATION 13 TERMINEE"
}
catch {
    Write-Host
    Write-Host "============================================================"
    Write-Host " VALIDATION ITERATION 13 INTERROMPUE"
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
