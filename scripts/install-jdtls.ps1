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
$pluginsDirectory = Join-Path $installDirectory "plugins"
$tempDirectory = Join-Path ([System.IO.Path]::GetTempPath()) ("nexus-jdtls-" + [Guid]::NewGuid().ToString("N"))
$archivePath = Join-Path $tempDirectory $archiveName
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

try {
    Write-Host "=== Installation Eclipse JDT Language Server pour NEXUS ==="
    Write-Host "Version : $Version"
    Write-Host "Destination : $installDirectory"
    Write-Host

    $expectedHash = Get-PinnedHash -Path $integrityFile -Key $integrityKey

    if ((Test-Path $pluginsDirectory) -and -not $Force) {
        Write-Host "JDT LS est deja installe. Reutilisation de l'installation existante."
        $env:NEXUS_JDTLS_HOME = $installDirectory
        Write-Host "NEXUS_JDTLS_HOME=$env:NEXUS_JDTLS_HOME"
        return
    }

    if (-not (Get-Command tar.exe -ErrorAction SilentlyContinue)) {
        throw "tar.exe est requis pour extraire l'archive JDT LS sous Windows."
    }

    New-Item -ItemType Directory -Path $tempDirectory -Force | Out-Null
    New-Item -ItemType Directory -Path $InstallRoot -Force | Out-Null

    Write-Host "[1/3] Telechargement de JDT LS"
    Invoke-WebRequest -Uri $archiveUrl -OutFile $archivePath -UseBasicParsing

    Write-Host "[2/3] Verification contre l'ancre SHA-256 versionnee dans le repository"
    $actualHash = (Get-FileHash -Path $archivePath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($expectedHash -ne $actualHash) {
        throw "Checksum SHA-256 JDT LS invalide. Attendu=$expectedHash, obtenu=$actualHash"
    }

    if (Test-Path $installDirectory) {
        Remove-Item -Recurse -Force $installDirectory
    }
    New-Item -ItemType Directory -Path $installDirectory -Force | Out-Null

    Write-Host "[3/3] Extraction"
    & tar.exe -xzf $archivePath -C $installDirectory
    Assert-NativeSuccess "L'extraction de JDT LS"

    if (-not (Test-Path $pluginsDirectory)) {
        throw "Installation JDT LS invalide : le repertoire plugins est absent de $installDirectory"
    }

    $launcher = Get-ChildItem -Path $pluginsDirectory -Filter "org.eclipse.equinox.launcher_*.jar" -File |
        Sort-Object Name -Descending |
        Select-Object -First 1
    if ($null -eq $launcher) {
        throw "Installation JDT LS invalide : launcher Equinox introuvable."
    }

    $env:NEXUS_JDTLS_HOME = $installDirectory

    Write-Host
    Write-Host "=== JDT LS installe avec succes ==="
    Write-Host "Launcher : $($launcher.FullName)"
    Write-Host "NEXUS_JDTLS_HOME=$env:NEXUS_JDTLS_HOME"
    Write-Host
    Write-Host "La variable est active dans ce processus PowerShell pour les commandes NEXUS suivantes."
}
finally {
    if (Test-Path $tempDirectory) {
        Remove-Item -Recurse -Force $tempDirectory -ErrorAction SilentlyContinue
    }
}
