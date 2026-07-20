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
$checksumUrl = "$archiveUrl.sha256"
$installDirectory = Join-Path $InstallRoot "jdtls-$Version"
$pluginsDirectory = Join-Path $installDirectory "plugins"
$tempDirectory = Join-Path ([System.IO.Path]::GetTempPath()) ("nexus-jdtls-" + [Guid]::NewGuid().ToString("N"))
$archivePath = Join-Path $tempDirectory $archiveName
$checksumPath = "$archivePath.sha256"

function Assert-NativeSuccess {
    param([Parameter(Mandatory = $true)][string]$CommandDescription)
    if ($LASTEXITCODE -ne 0) {
        throw "$CommandDescription a echoue avec le code $LASTEXITCODE."
    }
}

try {
    Write-Host "=== Installation Eclipse JDT Language Server pour NEXUS ==="
    Write-Host "Version : $Version"
    Write-Host "Destination : $installDirectory"
    Write-Host

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

    Write-Host "[1/4] Telechargement de JDT LS"
    Invoke-WebRequest -Uri $archiveUrl -OutFile $archivePath -UseBasicParsing

    Write-Host "[2/4] Telechargement du checksum SHA-256"
    Invoke-WebRequest -Uri $checksumUrl -OutFile $checksumPath -UseBasicParsing

    Write-Host "[3/4] Verification SHA-256"
    $checksumContent = (Get-Content -Raw -Path $checksumPath).Trim()
    $expectedHash = ($checksumContent -split '\s+')[0].ToUpperInvariant()
    $actualHash = (Get-FileHash -Path $archivePath -Algorithm SHA256).Hash.ToUpperInvariant()
    if ($expectedHash -ne $actualHash) {
        throw "Checksum SHA-256 invalide. Attendu=$expectedHash, obtenu=$actualHash"
    }

    if (Test-Path $installDirectory) {
        Remove-Item -Recurse -Force $installDirectory
    }
    New-Item -ItemType Directory -Path $installDirectory -Force | Out-Null

    Write-Host "[4/4] Extraction"
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
