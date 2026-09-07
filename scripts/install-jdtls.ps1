[CmdletBinding()]
param(
    [string]$Version = "1.60.0-202606262232",
    [string]$InstallRoot = (Join-Path $HOME ".nexus\tools"),
    [switch]$Force
)

$ErrorActionPreference = "Stop"

$archiveName = "jdt-language-server-$Version.tar.gz"
$downloadBase = "https://download.eclipse.org/jdtls/snapshots"
$archiveUrl = "$downloadBase/$archiveName"
$installDirectory = Join-Path $InstallRoot "jdtls-$Version"
$cacheDirectory = Join-Path $InstallRoot ".cache"
$cachedArchivePath = Join-Path $cacheDirectory $archiveName
$tempDirectory = Join-Path ([System.IO.Path]::GetTempPath()) ("nexus-jdtls-" + [Guid]::NewGuid().ToString("N"))
$downloadPath = Join-Path $tempDirectory $archiveName
$stagingDirectory = Join-Path $InstallRoot (".jdtls-stage-" + [Guid]::NewGuid().ToString("N"))
$repoRoot = Split-Path -Parent $PSScriptRoot
$integrityFile = Join-Path $repoRoot "config\tool-integrity.properties"
$integrityKey = "jdtls.$Version.sha256"

function Assert-NativeSuccess {
    param([Parameter(Mandatory = $true)][string]$CommandDescription)
    if ($LASTEXITCODE -ne 0) {
        throw "$CommandDescription a echoue avec le code $LASTEXITCODE."
    }
}

function Get-PinnedHash {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Key
    )
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Fichier d'ancres d'integrite introuvable : $Path"
    }
    $prefix = "$Key="
    $line = Get-Content -LiteralPath $Path -Encoding UTF8 |
        Where-Object { $_.StartsWith($prefix, [StringComparison]::Ordinal) } |
        Select-Object -First 1
    if ([string]::IsNullOrWhiteSpace($line)) {
        throw "Ancre SHA-256 JDT LS absente de $Path pour $Version"
    }
    $value = $line.Substring($prefix.Length).Trim().ToLowerInvariant()
    if ($value -notmatch '^[0-9a-f]{64}$') {
        throw "Ancre SHA-256 JDT LS invalide dans $Path pour $Version"
    }
    return $value
}

function Test-PinnedArchive {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$ExpectedHash
    )
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return $false
    }
    $actualHash = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
    return $actualHash -eq $ExpectedHash
}

function Install-VerifiedArchive {
    param(
        [Parameter(Mandatory = $true)][string]$ArchivePath,
        [Parameter(Mandatory = $true)][string]$Destination
    )

    New-Item -ItemType Directory -Path $Destination -Force | Out-Null
    & tar.exe -xzf $ArchivePath -C $Destination
    Assert-NativeSuccess "L'extraction de JDT LS"

    $pluginsDirectory = Join-Path $Destination "plugins"
    if (-not (Test-Path -LiteralPath $pluginsDirectory -PathType Container)) {
        throw "Installation JDT LS invalide : le repertoire plugins est absent de $Destination"
    }

    $launcher = Get-ChildItem -LiteralPath $pluginsDirectory -Filter "org.eclipse.equinox.launcher_*.jar" -File |
        Sort-Object Name -Descending |
        Select-Object -First 1
    if ($null -eq $launcher) {
        throw "Installation JDT LS invalide : launcher Equinox introuvable."
    }
    return $launcher
}

try {
    Write-Host "=== Installation Eclipse JDT Language Server pour NEXUS ==="
    Write-Host "Version : $Version"
    Write-Host "Destination : $installDirectory"
    Write-Host

    $expectedHash = Get-PinnedHash -Path $integrityFile -Key $integrityKey

    if (-not (Get-Command tar.exe -ErrorAction SilentlyContinue)) {
        throw "tar.exe est requis pour extraire l'archive JDT LS sous Windows."
    }

    New-Item -ItemType Directory -Path $tempDirectory -Force | Out-Null
    New-Item -ItemType Directory -Path $InstallRoot -Force | Out-Null
    New-Item -ItemType Directory -Path $cacheDirectory -Force | Out-Null

    if ($Force -and (Test-Path -LiteralPath $cachedArchivePath -PathType Leaf)) {
        Remove-Item -Force -LiteralPath $cachedArchivePath
    }

    if (-not (Test-PinnedArchive -Path $cachedArchivePath -ExpectedHash $expectedHash)) {
        if (Test-Path -LiteralPath $cachedArchivePath) {
            Write-Warning "Archive JDT LS cachee alteree : suppression avant nouveau telechargement."
            Remove-Item -Force -LiteralPath $cachedArchivePath
        }
        Write-Host "[1/4] Telechargement de JDT LS"
        Invoke-WebRequest -Uri $archiveUrl -OutFile $downloadPath -UseBasicParsing

        Write-Host "[2/4] Verification contre l'ancre SHA-256 versionnee dans le repository"
        if (-not (Test-PinnedArchive -Path $downloadPath -ExpectedHash $expectedHash)) {
            $actualHash = (Get-FileHash -LiteralPath $downloadPath -Algorithm SHA256).Hash.ToLowerInvariant()
            throw "Checksum SHA-256 JDT LS invalide. Attendu=$expectedHash, obtenu=$actualHash"
        }
        Move-Item -Force -LiteralPath $downloadPath -Destination $cachedArchivePath
    }
    else {
        Write-Host "[1/4] Archive JDT LS cachee presente"
        Write-Host "[2/4] Archive cachee revalidee contre l'ancre SHA-256 versionnee"
    }

    # Never trust an already extracted installation. Rebuild a staging tree from
    # the repository-pinned archive on every installer invocation, validate it,
    # then replace the previous tree only after validation succeeds.
    Write-Host "[3/4] Reconstruction depuis l'archive SHA-256 verifiee"
    $launcher = Install-VerifiedArchive -ArchivePath $cachedArchivePath -Destination $stagingDirectory

    Write-Host "[4/4] Remplacement de l'installation locale"
    if (Test-Path -LiteralPath $installDirectory) {
        Remove-Item -Recurse -Force -LiteralPath $installDirectory
    }
    Move-Item -LiteralPath $stagingDirectory -Destination $installDirectory

    $finalPluginsDirectory = Join-Path $installDirectory "plugins"
    $finalLauncher = Get-ChildItem -LiteralPath $finalPluginsDirectory -Filter "org.eclipse.equinox.launcher_*.jar" -File |
        Sort-Object Name -Descending |
        Select-Object -First 1
    if ($null -eq $finalLauncher) {
        throw "Installation JDT LS invalide apres remplacement : launcher Equinox introuvable."
    }

    $env:NEXUS_JDTLS_HOME = $installDirectory

    Write-Host
    Write-Host "=== JDT LS installe et revalide avec succes ==="
    Write-Host "Launcher : $($finalLauncher.FullName)"
    Write-Host "Archive epinglee : $cachedArchivePath"
    Write-Host "NEXUS_JDTLS_HOME=$env:NEXUS_JDTLS_HOME"
    Write-Host
    Write-Host "La variable est active dans ce processus PowerShell pour les commandes NEXUS suivantes."
}
finally {
    if (Test-Path -LiteralPath $stagingDirectory) {
        Remove-Item -Recurse -Force -LiteralPath $stagingDirectory -ErrorAction SilentlyContinue
    }
    if (Test-Path -LiteralPath $tempDirectory) {
        Remove-Item -Recurse -Force -LiteralPath $tempDirectory -ErrorAction SilentlyContinue
    }
}
