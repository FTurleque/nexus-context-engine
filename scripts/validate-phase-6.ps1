[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$locationPushed = $false
$previousPath = $env:PATH

function Invoke-Native {
    param(
        [Parameter(Mandatory = $true)][string]$Command,
        [Parameter(Mandatory = $false)][string[]]$Arguments = @()
    )

    & $Command @Arguments
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        throw "Commande en echec ($exitCode) : $Command $($Arguments -join ' ')"
    }
}

function Get-JavaVersionText {
    param([Parameter(Mandatory = $true)][string]$JavaExecutable)

    $processStartInfo = New-Object System.Diagnostics.ProcessStartInfo
    $processStartInfo.FileName = $JavaExecutable
    $processStartInfo.Arguments = "-version"
    $processStartInfo.UseShellExecute = $false
    $processStartInfo.RedirectStandardOutput = $true
    $processStartInfo.RedirectStandardError = $true
    $processStartInfo.CreateNoWindow = $true

    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $processStartInfo
    [void]$process.Start()
    $stdout = $process.StandardOutput.ReadToEnd()
    $stderr = $process.StandardError.ReadToEnd()
    $process.WaitForExit()

    if ($process.ExitCode -ne 0) {
        return $null
    }

    return (($stderr + [Environment]::NewLine + $stdout).Trim())
}

function Get-JavaMajorVersion {
    param([Parameter(Mandatory = $true)][string]$VersionText)

    if ($VersionText -notmatch 'version\s+"(?<version>[0-9]+)(?:\.[^"]*)?"') {
        return $null
    }
    return [int]$Matches['version']
}

function Assert-Sha256File {
    param([Parameter(Mandatory = $true)][string]$Artifact)

    $checksumFile = "$Artifact.sha256"
    if (-not (Test-Path -LiteralPath $checksumFile -PathType Leaf)) {
        throw "Checksum absent : $checksumFile"
    }
    $expected = ((Get-Content -Raw -LiteralPath $checksumFile).Trim() -split '\s+')[0].ToUpperInvariant()
    $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $Artifact).Hash.ToUpperInvariant()
    if ($expected -ne $actual) {
        throw "Checksum SHA-256 invalide pour $Artifact"
    }
}

function Assert-SameFileContent {
    param(
        [Parameter(Mandatory = $true)][string]$ExpectedFile,
        [Parameter(Mandatory = $true)][string]$ActualFile,
        [Parameter(Mandatory = $true)][string]$Description
    )

    $expectedHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $ExpectedFile).Hash
    $actualHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $ActualFile).Hash
    if ($expectedHash -ne $actualHash) {
        throw "$Description ne correspond pas a l'artefact genere par le build."
    }
}

try {
    Push-Location $repoRoot
    $locationPushed = $true

    Write-Host "=== NEXUS - qualification locale ==="
    Write-Host "Repository : $repoRoot"
    Write-Host

    Write-Host "[1/9] JDK 21+ / compilation release 21"
    $javaCommand = Get-Command java -ErrorAction SilentlyContinue
    if ($null -eq $javaCommand -or [string]::IsNullOrWhiteSpace($javaCommand.Source)) {
        throw "Java est absent du PATH."
    }

    $javaVersion = Get-JavaVersionText -JavaExecutable $javaCommand.Source
    if ([string]::IsNullOrWhiteSpace($javaVersion)) {
        throw "Impossible de lire la version Java depuis $($javaCommand.Source)."
    }
    $javaMajor = Get-JavaMajorVersion -VersionText $javaVersion
    if ($null -eq $javaMajor -or $javaMajor -lt 21) {
        throw "NEXUS requiert un JDK 21 ou superieur. Version detectee : $javaVersion"
    }

    [xml]$rootPom = Get-Content -Raw -LiteralPath (Join-Path $repoRoot "pom.xml")
    $compilerRelease = [string]$rootPom.project.properties.'maven.compiler.release'
    if ($compilerRelease -ne "21") {
        throw "Le build doit rester cible sur maven.compiler.release=21 (valeur detectee : $compilerRelease)."
    }

    Write-Host "Java executable : $($javaCommand.Source)"
    Write-Host $javaVersion
    Write-Host "Maven compiler release : $compilerRelease"

    Write-Host "[2/9] Maven Wrapper reproductible"
    Invoke-Native -Command (Join-Path $repoRoot "mvnw.cmd") -Arguments @("--version")
    $wrapperMavenBin = Join-Path $env:USERPROFILE ".m2\wrapper\dists\nexus\apache-maven-3.9.11\apache-maven-3.9.11\bin"
    if (Test-Path -LiteralPath $wrapperMavenBin -PathType Container) {
        $env:PATH = "$wrapperMavenBin;$env:PATH"
    }

    Write-Host "[3/9] Reactor complet : clean install"
    Invoke-Native -Command (Join-Path $repoRoot "mvnw.cmd") -Arguments @("clean", "install")

    Write-Host "[4/9] Self-smoke historique obligatoire"
    & (Join-Path $repoRoot "scripts\self-smoke.ps1")
    if ($LASTEXITCODE -ne 0) {
        throw "scripts/self-smoke.ps1 a echoue avec le code $LASTEXITCODE"
    }

    Write-Host "[5/9] Livrables 0.2.0 et checksums"
    $cliJar = Join-Path $repoRoot "target\nexus-context-engine-0.2.0-cli.jar"
    $distributionZip = Join-Path $repoRoot "target\distribution\nexus-context-engine-0.2.0.zip"
    foreach ($artifact in @($cliJar, $distributionZip)) {
        if (-not (Test-Path -LiteralPath $artifact -PathType Leaf)) {
            throw "Livrable absent : $artifact"
        }
        Assert-Sha256File -Artifact $artifact
    }

    Write-Host "[6/9] SBOM CycloneDX agrege"
    $sbom = Join-Path $repoRoot "target\sbom\bom.json"
    if (-not (Test-Path -LiteralPath $sbom -PathType Leaf)) {
        throw "SBOM absent : $sbom"
    }
    $sbomJson = Get-Content -Raw -LiteralPath $sbom | ConvertFrom-Json
    if ($sbomJson.bomFormat -ne "CycloneDX") {
        throw "Le SBOM genere n'est pas au format CycloneDX."
    }
    if ($sbomJson.specVersion -ne "1.6") {
        throw "Version CycloneDX inattendue : $($sbomJson.specVersion)"
    }
    if ($null -eq $sbomJson.metadata.component -or
            $sbomJson.metadata.component.name -ne "nexus-context-engine-parent" -or
            $sbomJson.metadata.component.version -ne "0.2.0") {
        throw "Le composant racine du SBOM ne decrit pas NEXUS 0.2.0."
    }
    $requiredModules = @(
        "nexus-context-engine",
        "nexus-rest-quarkus",
        "nexus-mcp-java",
        "nexus-assistant-clients"
    )
    $sbomModules = @($sbomJson.components |
            Where-Object { $_.group -eq "io.github.fturleque" -and $_.version -eq "0.2.0" } |
            ForEach-Object { $_.name })
    foreach ($requiredModule in $requiredModules) {
        if ($sbomModules -notcontains $requiredModule) {
            throw "Module NEXUS absent du SBOM : $requiredModule"
        }
    }

    Write-Host "[7/9] Couverture et notices tierces"
    $thirdPartyNotices = Join-Path $repoRoot "target\licenses\THIRD_PARTY_NOTICES.txt"
    if (-not (Test-Path -LiteralPath $thirdPartyNotices -PathType Leaf) -or (Get-Item -LiteralPath $thirdPartyNotices).Length -eq 0) {
        throw "Notices tierces absentes ou vides : $thirdPartyNotices"
    }
    $jacocoReport = Join-Path $repoRoot "core\target\site\jacoco\jacoco.xml"
    if (-not (Test-Path -LiteralPath $jacocoReport -PathType Leaf) -or (Get-Item -LiteralPath $jacocoReport).Length -eq 0) {
        throw "Rapport JaCoCo absent ou vide : $jacocoReport"
    }

    Write-Host "[8/9] Archive installable et artefacts de conformite"
    $extractRoot = Join-Path $repoRoot "target\phase-6-distribution-smoke"
    if (Test-Path -LiteralPath $extractRoot) {
        Remove-Item -Recurse -Force -LiteralPath $extractRoot
    }
    Expand-Archive -Path $distributionZip -DestinationPath $extractRoot -Force
    $distributionRoot = Get-ChildItem -Path $extractRoot -Directory | Select-Object -First 1
    if ($null -eq $distributionRoot) {
        throw "Racine de distribution absente dans l'archive."
    }

    $launcher = Get-ChildItem -Path $distributionRoot.FullName -Filter "nexus.cmd" -Recurse -File | Select-Object -First 1
    if ($null -eq $launcher) {
        throw "Launcher Windows nexus.cmd absent de l'archive."
    }
    $posixLauncher = Get-ChildItem -Path $distributionRoot.FullName -Filter "nexus" -Recurse -File | Select-Object -First 1
    if ($null -eq $posixLauncher) {
        throw "Launcher POSIX nexus absent de l'archive."
    }
    $posixBytes = [System.IO.File]::ReadAllBytes($posixLauncher.FullName)
    if ($posixBytes -contains [byte]13) {
        throw "Le launcher POSIX contient des retours chariot CR et ne peut pas etre distribue."
    }
    $posixText = [System.Text.Encoding]::UTF8.GetString($posixBytes)
    if (-not $posixText.StartsWith("#!/bin/sh`n")) {
        throw "Le launcher POSIX ne possede pas le shebang attendu."
    }

    $embeddedLicense = Join-Path $distributionRoot.FullName "LICENSE"
    $embeddedThirdParty = Join-Path $distributionRoot.FullName "THIRD_PARTY_NOTICES.txt"
    $embeddedSbom = Join-Path $distributionRoot.FullName "SBOM.cdx.json"
    foreach ($requiredComplianceFile in @($embeddedLicense, $embeddedThirdParty, $embeddedSbom)) {
        if (-not (Test-Path -LiteralPath $requiredComplianceFile -PathType Leaf) -or (Get-Item -LiteralPath $requiredComplianceFile).Length -eq 0) {
            throw "Artefact de conformite absent ou vide dans l'archive : $requiredComplianceFile"
        }
    }
    Assert-SameFileContent -ExpectedFile (Join-Path $repoRoot "LICENSE") -ActualFile $embeddedLicense -Description "La licence embarquee"
    Assert-SameFileContent -ExpectedFile $thirdPartyNotices -ActualFile $embeddedThirdParty -Description "Les notices tierces embarquees"
    Assert-SameFileContent -ExpectedFile $sbom -ActualFile $embeddedSbom -Description "Le SBOM embarque"

    $versionOutput = & $launcher.FullName --version --json | Out-String
    if ($LASTEXITCODE -ne 0) {
        throw "Le launcher de distribution a echoue."
    }
    $version = $versionOutput | ConvertFrom-Json
    if ($version.version -ne "0.2.0") {
        throw "Version de distribution inattendue : $($version.version)"
    }

    Write-Host "[9/9] Controle exact-head et etat Git"
    $head = (& git rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0) {
        throw "Impossible de lire le HEAD Git."
    }

    $expectedHead = $env:NEXUS_EXPECTED_HEAD_SHA
    if (-not [string]::IsNullOrWhiteSpace($expectedHead)) {
        $expectedHead = $expectedHead.Trim()
        if ($head -ne $expectedHead) {
            throw "La qualification doit etre executee au HEAD attendu $expectedHead (HEAD courant : $head)."
        }
        Write-Host "Expected HEAD : $expectedHead"
    }
    else {
        $branch = (& git branch --show-current).Trim()
        if ($LASTEXITCODE -ne 0) {
            throw "Impossible de lire la branche Git courante."
        }
        Write-Host "Branch : $branch"
        Write-Host "NEXUS_EXPECTED_HEAD_SHA non defini : validation locale du HEAD courant $head."
    }

    Write-Host
    Write-Host "=== NEXUS QUALIFICATION PASS ==="
    Write-Host "HEAD : $head"
    Write-Host "Archive : $distributionZip"
    Write-Host "SBOM : $sbom"
    Write-Host "Third-party notices : $thirdPartyNotices"
    Write-Host "JaCoCo report : $jacocoReport"
}
finally {
    $env:PATH = $previousPath
    if ($locationPushed) {
        Pop-Location
    }
}
