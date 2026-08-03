[CmdletBinding()]
param(
    [string]$Java21Home
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$locationPushed = $false
$previousJavaHome = $env:JAVA_HOME
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

    $stdoutFile = [System.IO.Path]::GetTempFileName()
    $stderrFile = [System.IO.Path]::GetTempFileName()
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        # Windows PowerShell 5.1 converts native stderr to NativeCommandError
        # when ErrorActionPreference is Stop. java -version writes to stderr.
        $ErrorActionPreference = "Continue"
        & $JavaExecutable -version 1> $stdoutFile 2> $stderrFile
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }

    try {
        if ($exitCode -ne 0) {
            return $null
        }
        $stdout = Get-Content -Raw -Path $stdoutFile -ErrorAction SilentlyContinue
        $stderr = Get-Content -Raw -Path $stderrFile -ErrorAction SilentlyContinue
        return (($stderr + [Environment]::NewLine + $stdout).Trim())
    }
    finally {
        Remove-Item -Force $stdoutFile, $stderrFile -ErrorAction SilentlyContinue
    }
}

function Test-Java21Executable {
    param([Parameter(Mandatory = $true)][string]$JavaExecutable)

    if (-not (Test-Path -LiteralPath $JavaExecutable -PathType Leaf)) {
        return $false
    }
    $versionText = Get-JavaVersionText -JavaExecutable $JavaExecutable
    return ($null -ne $versionText -and $versionText -match 'version\s+"21(?:\.|\")')
}

function Resolve-Java21Home {
    param([string]$ExplicitHome)

    $candidateHomes = New-Object System.Collections.Generic.List[string]

    foreach ($candidate in @($ExplicitHome, $env:NEXUS_JAVA21_HOME, $env:JAVA_HOME)) {
        if (-not [string]::IsNullOrWhiteSpace($candidate)) {
            $candidateHomes.Add($candidate)
        }
    }

    # IntelliJ / JetBrains downloaded JDKs.
    $jetBrainsJdks = Join-Path $env:USERPROFILE ".jdks"
    if (Test-Path -LiteralPath $jetBrainsJdks -PathType Container) {
        Get-ChildItem -LiteralPath $jetBrainsJdks -Directory -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -match '21' } |
            Sort-Object FullName -Descending |
            ForEach-Object { $candidateHomes.Add($_.FullName) }
    }

    $programFilesRoots = @($env:ProgramFiles, ${env:ProgramFiles(x86)}) |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
        Select-Object -Unique

    foreach ($root in $programFilesRoots) {
        $patterns = @(
            (Join-Path $root "Microsoft\jdk-21*"),
            (Join-Path $root "Eclipse Adoptium\jdk-21*"),
            (Join-Path $root "Java\jdk-21*"),
            (Join-Path $root "Amazon Corretto\jdk21*"),
            (Join-Path $root "Zulu\zulu-21*"),
            (Join-Path $root "BellSoft\LibericaJDK-21*")
        )
        foreach ($pattern in $patterns) {
            Get-ChildItem -Path $pattern -Directory -ErrorAction SilentlyContinue |
                Sort-Object FullName -Descending |
                ForEach-Object { $candidateHomes.Add($_.FullName) }
        }
    }

    $javaCommand = Get-Command java -ErrorAction SilentlyContinue
    if ($null -ne $javaCommand -and -not [string]::IsNullOrWhiteSpace($javaCommand.Source)) {
        if (Test-Java21Executable -JavaExecutable $javaCommand.Source) {
            return (Split-Path -Parent (Split-Path -Parent $javaCommand.Source))
        }
    }

    foreach ($candidateHome in ($candidateHomes | Select-Object -Unique)) {
        try {
            $resolvedCandidateHome = (Resolve-Path -LiteralPath $candidateHome -ErrorAction Stop).Path
        }
        catch {
            continue
        }

        $javaExecutable = Join-Path $resolvedCandidateHome "bin\java.exe"
        if (Test-Java21Executable -JavaExecutable $javaExecutable) {
            return $resolvedCandidateHome
        }
    }

    return $null
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

try {
    Push-Location $repoRoot
    $locationPushed = $true

    Write-Host "=== NEXUS Phase 6 - qualification locale ==="
    Write-Host "Repository : $repoRoot"
    Write-Host

    Write-Host "[1/8] Java 21"
    $resolvedJava21Home = Resolve-Java21Home -ExplicitHome $Java21Home
    if ([string]::IsNullOrWhiteSpace($resolvedJava21Home)) {
        $currentJava = Get-Command java -ErrorAction SilentlyContinue
        $currentVersion = "java absent du PATH"
        if ($null -ne $currentJava -and -not [string]::IsNullOrWhiteSpace($currentJava.Source)) {
            $detected = Get-JavaVersionText -JavaExecutable $currentJava.Source
            if (-not [string]::IsNullOrWhiteSpace($detected)) {
                $currentVersion = $detected
            }
        }
        throw "Java 21 Windows est requis et introuvable. Java courant : $currentVersion. Installez un JDK 21 Windows ou passez -Java21Home <chemin>."
    }

    $env:JAVA_HOME = $resolvedJava21Home
    $env:PATH = "$(Join-Path $resolvedJava21Home 'bin');$previousPath"
    $javaExecutable = Join-Path $resolvedJava21Home "bin\java.exe"
    $javaVersion = Get-JavaVersionText -JavaExecutable $javaExecutable
    if ([string]::IsNullOrWhiteSpace($javaVersion) -or $javaVersion -notmatch 'version\s+"21(?:\.|\")') {
        throw "Le JDK selectionne n'est pas Java 21 : $resolvedJava21Home"
    }
    Write-Host "JAVA_HOME : $resolvedJava21Home"
    Write-Host $javaVersion

    Write-Host "[2/8] Maven Wrapper reproductible"
    Invoke-Native -Command (Join-Path $repoRoot "mvnw.cmd") -Arguments @("--version")
    $wrapperMavenBin = Join-Path $env:USERPROFILE ".m2\wrapper\dists\nexus\apache-maven-3.9.11\apache-maven-3.9.11\bin"
    if (Test-Path -LiteralPath $wrapperMavenBin -PathType Container) {
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
        if (-not (Test-Path -LiteralPath $artifact -PathType Leaf)) {
            throw "Livrable absent : $artifact"
        }
        Assert-Sha256File -Artifact $artifact
    }

    Write-Host "[6/8] SBOM CycloneDX agrege"
    $sbom = Join-Path $repoRoot "target\sbom\bom.json"
    if (-not (Test-Path -LiteralPath $sbom -PathType Leaf)) {
        throw "SBOM absent : $sbom"
    }
    $sbomJson = Get-Content -Raw -LiteralPath $sbom | ConvertFrom-Json
    if ($sbomJson.bomFormat -ne "CycloneDX") {
        throw "Le SBOM genere n'est pas au format CycloneDX."
    }

    Write-Host "[7/8] Archive installable sans clone"
    $extractRoot = Join-Path $repoRoot "target\phase-6-distribution-smoke"
    if (Test-Path -LiteralPath $extractRoot) {
        Remove-Item -Recurse -Force -LiteralPath $extractRoot
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
    $env:JAVA_HOME = $previousJavaHome
    $env:PATH = $previousPath
    if ($locationPushed) {
        Pop-Location
    }
}
