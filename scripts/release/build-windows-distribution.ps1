[CmdletBinding()]
param(
    [string]$Version = '',
    [string]$OutputRoot = '',
    [switch]$SkipVerify
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ($env:OS -ne 'Windows_NT') {
    throw 'The NEXUS Windows distribution must be built on Windows.'
}

$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
    $OutputRoot = Join-Path $repo 'target\dist'
}
$OutputRoot = [IO.Path]::GetFullPath($OutputRoot)

[xml]$pom = Get-Content -LiteralPath (Join-Path $repo 'pom.xml') -Raw
$projectVersion = [string]$pom.project.version
if ([string]::IsNullOrWhiteSpace($Version)) {
    $Version = $projectVersion
}
if ($Version -ne $projectVersion) {
    throw "Requested version $Version does not match pom.xml version $projectVersion. Update the reactor version first."
}

$javaHome = $env:JAVA_HOME
if ([string]::IsNullOrWhiteSpace($javaHome)) {
    throw 'JAVA_HOME must point to a JDK 21 or newer installation.'
}
$java = Join-Path $javaHome 'bin\java.exe'
$jpackage = Join-Path $javaHome 'bin\jpackage.exe'
$jdeps = Join-Path $javaHome 'bin\jdeps.exe'
foreach ($tool in @($java, $jpackage, $jdeps)) {
    if (-not (Test-Path -LiteralPath $tool -PathType Leaf)) {
        throw "Required JDK tool not found: $tool"
    }
}

$previousPreference = $ErrorActionPreference
try {
    $ErrorActionPreference = 'Continue'
    $versionOutput = ((& $java -version 2>&1) | Out-String)
    $versionExit = $LASTEXITCODE
}
finally {
    $ErrorActionPreference = $previousPreference
}
if ($versionExit -ne 0 -or $versionOutput -notmatch 'version "(\d+)') {
    throw "Unable to determine JAVA_HOME runtime version: $versionOutput"
}
$javaMajor = [int]$Matches[1]
if ($javaMajor -lt 21) {
    throw "NEXUS Windows packaging requires JDK 21 or newer; found Java $javaMajor."
}

Push-Location $repo
try {
    $mavenArgs = @('-B', 'clean')
    $mavenArgs += if ($SkipVerify) { 'package' } else { 'verify' }
    & '.\mvnw.cmd' @mavenArgs
    if ($LASTEXITCODE -ne 0) {
        throw "NEXUS Maven build failed with exit code $LASTEXITCODE"
    }
}
finally {
    Pop-Location
}

$jar = Join-Path $repo "target\nexus-context-engine-$Version-cli.jar"
if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) {
    throw "NEXUS shaded CLI JAR not found: $jar"
}
foreach ($evidence in @(
    (Join-Path $repo 'LICENSE'),
    (Join-Path $repo 'target\licenses\THIRD_PARTY_NOTICES.txt'),
    (Join-Path $repo 'target\sbom\bom.json')
)) {
    if (-not (Test-Path -LiteralPath $evidence -PathType Leaf)) {
        throw "Required distribution evidence not found: $evidence"
    }
}

$previousPreference = $ErrorActionPreference
try {
    $ErrorActionPreference = 'Continue'
    $jdepsOutput = @(& $jdeps '--multi-release' '21' '--ignore-missing-deps' '--print-module-deps' $jar 2>&1 |
        ForEach-Object { $_.ToString().Trim() })
    $jdepsExit = $LASTEXITCODE
}
finally {
    $ErrorActionPreference = $previousPreference
}
if ($jdepsExit -ne 0) {
    throw "jdeps failed while deriving runtime modules: $($jdepsOutput -join [Environment]::NewLine)"
}
$moduleLine = $jdepsOutput |
    Where-Object { $_ -match '^[A-Za-z0-9_.]+(?:,[A-Za-z0-9_.]+)*$' } |
    Select-Object -Last 1
if ([string]::IsNullOrWhiteSpace($moduleLine)) {
    throw "jdeps did not return module dependencies: $($jdepsOutput -join [Environment]::NewLine)"
}
$modules = @($moduleLine -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ })
$modules += @('java.logging', 'java.naming', 'java.net.http', 'java.sql', 'java.xml', 'jdk.crypto.ec', 'jdk.unsupported')
$modules = @($modules | Sort-Object -Unique)
$moduleList = $modules -join ','
Write-Host "NEXUS packaged runtime roots: $moduleList" -ForegroundColor Cyan

$stage = Join-Path $OutputRoot '.jpackage-input'
$appImages = Join-Path $OutputRoot '.jpackage-output'
$distributionName = "nexus-context-engine-$Version-windows-x64"
$distribution = Join-Path $OutputRoot $distributionName
$zip = Join-Path $OutputRoot "$distributionName.zip"
$checksum = "$zip.sha256"

Remove-Item -LiteralPath $stage, $appImages, $distribution -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath $zip, $checksum -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $stage, $appImages, $distribution | Out-Null
Copy-Item -LiteralPath $jar -Destination (Join-Path $stage 'nexus-cli.jar')

$appVersion = ($Version -split '[-+]')[0]
$jlinkOptions = '--strip-debug --no-man-pages --no-header-files'
& $jpackage @(
    '--type', 'app-image',
    '--name', 'nexus',
    '--app-version', $appVersion,
    '--description', 'NEXUS Context Engine',
    '--input', $stage,
    '--main-jar', 'nexus-cli.jar',
    '--main-class', 'com.nexus.cli.NexusCli',
    '--add-modules', $moduleList,
    '--jlink-options', $jlinkOptions,
    '--dest', $appImages,
    '--win-console'
)
if ($LASTEXITCODE -ne 0) {
    throw "jpackage app-image failed with exit code $LASTEXITCODE"
}

$appImage = Join-Path $appImages 'nexus'
$launcher = Join-Path $appImage 'nexus.exe'
$runtimeJava = Join-Path $appImage 'runtime\bin\java.exe'
foreach ($required in @($launcher, $runtimeJava)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "Invalid NEXUS app-image; missing $required"
    }
}

Move-Item -LiteralPath $appImage -Destination (Join-Path $distribution 'app')
New-Item -ItemType Directory -Force -Path (Join-Path $distribution 'lib') | Out-Null
Copy-Item -LiteralPath $jar -Destination (Join-Path $distribution 'lib\nexus-cli.jar')
Copy-Item -LiteralPath (Join-Path $repo 'LICENSE') -Destination (Join-Path $distribution 'LICENSE')
Copy-Item -LiteralPath (Join-Path $repo 'target\licenses\THIRD_PARTY_NOTICES.txt') -Destination (Join-Path $distribution 'THIRD_PARTY_NOTICES.txt')
Copy-Item -LiteralPath (Join-Path $repo 'target\sbom\bom.json') -Destination (Join-Path $distribution 'SBOM.cdx.json')
Copy-Item -LiteralPath (Join-Path $repo 'distribution\README.md') -Destination (Join-Path $distribution 'README.md')
$Version | Set-Content -LiteralPath (Join-Path $distribution 'VERSION') -Encoding ascii
$moduleList | Set-Content -LiteralPath (Join-Path $distribution 'RUNTIME-MODULES.txt') -Encoding ascii

@'
@echo off
setlocal
"%~dp0app\nexus.exe" %*
exit /b %ERRORLEVEL%
'@ | Set-Content -LiteralPath (Join-Path $distribution 'nexus.cmd') -Encoding ascii

$smokeHome = Join-Path $OutputRoot '.distribution-smoke-home'
Remove-Item -LiteralPath $smokeHome -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $smokeHome | Out-Null
$previousHome = $env:NEXUS_HOME
try {
    $env:NEXUS_HOME = $smokeHome
    $versionJson = & (Join-Path $distribution 'nexus.cmd') '--version' '--json' | Out-String
    if ($LASTEXITCODE -ne 0) { throw "Packaged NEXUS launcher failed with exit code $LASTEXITCODE" }
    $parsed = $versionJson | ConvertFrom-Json
    if ($parsed.version -ne $Version) {
        throw "Packaged NEXUS version mismatch: expected $Version, got $($parsed.version)"
    }
}
finally {
    $env:NEXUS_HOME = $previousHome
    Remove-Item -LiteralPath $smokeHome -Recurse -Force -ErrorAction SilentlyContinue
}

$runtimeModules = @(& (Join-Path $distribution 'app\runtime\bin\java.exe') '--list-modules' 2>&1 |
    ForEach-Object { $_.ToString().Trim() })
foreach ($requiredModule in $modules) {
    if (-not ($runtimeModules | Where-Object { $_ -match "^$([regex]::Escape($requiredModule))@" })) {
        throw "Packaged runtime is missing required module $requiredModule"
    }
}

Compress-Archive -Path $distribution -DestinationPath $zip -CompressionLevel Optimal
$hash = (Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash.ToLowerInvariant()
"$hash  $([IO.Path]::GetFileName($zip))" | Set-Content -LiteralPath $checksum -Encoding ascii

Write-Host ''
Write-Host 'NEXUS Windows self-contained distribution SUCCESS' -ForegroundColor Green
Write-Host "Distribution : $distribution"
Write-Host "ZIP          : $zip"
Write-Host "SHA-256      : $hash"
Write-Host "Bundled Java : $javaMajor"
Write-Output $distribution
