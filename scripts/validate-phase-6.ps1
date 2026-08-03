[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$locationPushed = $false

function Invoke-Native {
    param(
        [Parameter(Mandatory = $true)][string]$Command,
        [Parameter(Mandatory = $false)][string[]]$Arguments = @()
    )
    & $Command @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Commande en echec ($LASTEXITCODE) : $Command $($Arguments -join ' ')"
    }
}

function Assert-Sha256File {
    param([Parameter(Mandatory = $true)][string]$Artifact)
    $checksumFile = "$Artifact.sha256"
    if (-not (Test-Path $checksumFile)) {
        throw "Checksum absent : $checksumFile"
    }
    $expected = ((Get-Content -Raw $checksumFile).Trim() -split '\s+')[0].ToUpperInvariant()
    $actual = (Get-FileHash -Algorithm SHA256 -Path $Artifact).Hash.ToUpperInvariant()
    if ($expected -ne $actual) {
        throw "Checksum SHA-256 invalide pour $Artifact"
    }
}

try {
    Push-Location $repoRoot
    $locationPushed = $true

    Write-Host "=== NEXUS Phase 6 - qualification locale ==="
    Write-Host "Repository : $repoRoot"
    Write-Host

    Write-Host "[1/8] Java 21"
    $javaVersion = (& java -version 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0 -or $javaVersion -notmatch 'version\s+"21(?:\.|\")') {
        throw "Java 21 est requis. Version detectee : $javaVersion"
    }
    Write-Host $javaVersion.Trim()

    Write-Host "[2/8] Maven Wrapper reproductible"
    Invoke-Native -Command (Join-Path $repoRoot "mvnw.cmd") -Arguments @("--version")
    $wrapperMavenBin = Join-Path $env:USERPROFILE ".m2\wrapper\dists\nexus\apache-maven-3.9.11\apache-maven-3.9.11\bin"
    if (Test-Path $wrapperMavenBin) {
        $env:PATH = "$wrapperMavenBin;$env:PATH"
    }

    Write-Host "[3/8] Reactor complet : clean install"
    Invoke-Native -Command (Join-Path $repoRoot "mvnw.cmd") -Arguments @("clean", "install")

    Write-Host "[4/8] Self-smoke historique obligatoire"
    & (Join-Path $repoRoot "scripts\self-smoke.ps1")
    if ($LASTEXITCODE -ne 0) {
        throw "scripts/self-smoke.ps1 a echoue avec le code $LASTEXITCODE"
    }

    Write-Host "[5/8] Livrables 0.2.0 et checksums"
    $cliJar = Join-Path $repoRoot "target\nexus-context-engine-0.2.0-cli.jar"
    $distributionZip = Join-Path $repoRoot "target\distribution\nexus-context-engine-0.2.0.zip"
    foreach ($artifact in @($cliJar, $distributionZip)) {
        if (-not (Test-Path $artifact)) {
            throw "Livrable absent : $artifact"
        }
        Assert-Sha256File -Artifact $artifact
    }

    Write-Host "[6/8] SBOM CycloneDX agrege"
    $sbom = Join-Path $repoRoot "target\sbom\bom.json"
    if (-not (Test-Path $sbom)) {
        throw "SBOM absent : $sbom"
    }
    $sbomJson = Get-Content -Raw $sbom | ConvertFrom-Json
    if ($sbomJson.bomFormat -ne "CycloneDX") {
        throw "Le SBOM genere n'est pas au format CycloneDX."
    }

    Write-Host "[7/8] Archive installable sans clone"
    $extractRoot = Join-Path $repoRoot "target\phase-6-distribution-smoke"
    if (Test-Path $extractRoot) {
        Remove-Item -Recurse -Force $extractRoot
    }
    Expand-Archive -Path $distributionZip -DestinationPath $extractRoot -Force
    $launcher = Get-ChildItem -Path $extractRoot -Filter "nexus.cmd" -Recurse -File | Select-Object -First 1
    if ($null -eq $launcher) {
        throw "Launcher Windows nexus.cmd absent de l'archive."
    }
    $versionOutput = & $launcher.FullName --version --json | Out-String
    if ($LASTEXITCODE -ne 0) {
        throw "Le launcher de distribution a echoue."
    }
    $version = $versionOutput | ConvertFrom-Json
    if ($version.version -ne "0.2.0") {
        throw "Version de distribution inattendue : $($version.version)"
    }

    Write-Host "[8/8] Controle exact-head et etat Git"
    $branch = (& git branch --show-current).Trim()
    if ($LASTEXITCODE -ne 0 -or $branch -ne "phase-6-consolidation-hardening") {
        throw "La qualification doit etre executee sur phase-6-consolidation-hardening (branche courante : $branch)."
    }
    $head = (& git rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0) {
        throw "Impossible de lire le HEAD Git."
    }

    Write-Host
    Write-Host "=== PHASE 6 PASS ==="
    Write-Host "HEAD : $head"
    Write-Host "Archive : $distributionZip"
    Write-Host "SBOM : $sbom"
}
finally {
    if ($locationPushed) {
        Pop-Location
    }
}
