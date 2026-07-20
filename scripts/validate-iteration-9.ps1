[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$locationPushed = $false

try {
    Push-Location $repoRoot
    $locationPushed = $true

    Write-Host "============================================================"
    Write-Host " NEXUS - Validation locale Iteration 9 / JDT LS"
    Write-Host "============================================================"
    Write-Host

    Write-Host "[1/4] mvn clean install"
    & mvn clean install
    if ($LASTEXITCODE -ne 0) {
        throw "mvn clean install a echoue avec le code $LASTEXITCODE."
    }

    Write-Host
    Write-Host "[2/4] Self-smoke"
    & (Join-Path $PSScriptRoot "self-smoke.ps1")

    Write-Host
    Write-Host "[3/4] Installation / configuration JDT LS"
    & (Join-Path $PSScriptRoot "install-jdtls.ps1")

    if ([string]::IsNullOrWhiteSpace($env:NEXUS_JDTLS_HOME)) {
        throw "L'installation JDT LS n'a pas configure NEXUS_JDTLS_HOME."
    }

    Write-Host
    Write-Host "[4/4] Comparaison baseline vs JDT LS"
    & (Join-Path $PSScriptRoot "compare-jdt.ps1")

    Write-Host
    Write-Host "============================================================"
    Write-Host " VALIDATION ITERATION 9 TERMINEE"
    Write-Host "============================================================"
    Write-Host "Copiez le bloc final COMPARAISON JDT LS dans la conversation."
}
catch {
    Write-Host
    Write-Host "============================================================"
    Write-Host " VALIDATION ITERATION 9 INTERROMPUE"
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
