[CmdletBinding()]
param(
    [string]$Version = '',
    [string]$OutputRoot = '',
    [switch]$SkipVerify,
    [switch]$SkipTests
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
if ([string]::IsNullOrWhiteSpace($Version)) { $Version = $projectVersion }
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
    if (-not (Test-Path -LiteralPath $tool -PathType Leaf)) { throw "Required JDK tool not found: $tool" }
}

$previousPreference = $ErrorActionPreference
try {
    $ErrorActionPreference = 'Continue'
    $versionOutput = ((& $java -version 2>&1) | Out-String)
    $versionExit = $LASTEXITCODE
    $propertyOutput = @(& $java '-XshowSettings:properties' '-version' 2>&1 | ForEach-Object { $_.ToString() })
    $propertyExit = $LASTEXITCODE
}
finally { $ErrorActionPreference = $previousPreference }
if ($versionExit -ne 0 -or $versionOutput -notmatch 'version "(\d+)') {
    throw "Unable to determine JAVA_HOME runtime version: $versionOutput"
}
$javaMajor = [int]$Matches[1]
if ($javaMajor -lt 21) { throw "NEXUS Windows packaging requires JDK 21 or newer; found Java $javaMajor." }
if ($propertyExit -ne 0) {
    throw 'Unable to inspect the exact JAVA_HOME runtime version.'
}
$javaRuntimeVersion = $null
foreach ($line in $propertyOutput) {
    if ($line -match '^\s*java\.runtime\.version\s*=\s*(.+?)\s*$') {
        $javaRuntimeVersion = $Matches[1]
        break
    }
}
if ([string]::IsNullOrWhiteSpace($javaRuntimeVersion)) {
    throw 'JAVA_HOME did not expose java.runtime.version.'
}
$nativeAccessArgument = '--enable-native-access=ALL-UNNAMED'

Push-Location $repo
try {
    $mavenArgs = @('-B', 'clean')
    $mavenArgs += if ($SkipVerify) { 'package' } else { 'verify' }
    if ($SkipTests) {
        $mavenArgs += '-DskipTests'
    }
    & '.\mvnw.cmd' @mavenArgs
    if ($LASTEXITCODE -ne 0) { throw "NEXUS Maven build failed with exit code $LASTEXITCODE" }
}
finally { Pop-Location }

$cliJar = Join-Path $repo "target\nexus-context-engine-$Version-cli.jar"
$mcpJar = Join-Path $repo "adapters\mcp-java\target\nexus-mcp-java-$Version-runner.jar"
$assistantJar = Join-Path $repo "adapters\assistant-clients\target\nexus-assistant-clients-$Version-runner.jar"
$restRoot = Join-Path $repo 'adapters\rest-quarkus\target\quarkus-app'
foreach ($artifact in @($cliJar, $mcpJar, $assistantJar, (Join-Path $restRoot 'quarkus-run.jar'))) {
    if (-not (Test-Path -LiteralPath $artifact -PathType Leaf)) { throw "Required NEXUS runtime artifact not found: $artifact" }
}
$dockerPayloadSources = @(
    (Join-Path $repo 'packaging\docker\docker-compose.yml.template'),
    (Join-Path $repo 'packaging\docker\Dockerfile.runtime'),
    (Join-Path $repo 'packaging\docker\nexus-container-entrypoint.sh'),
    (Join-Path $repo 'packaging\docker\nexus-container-healthcheck.sh')
)
foreach ($evidence in @(
    (Join-Path $repo 'LICENSE'),
    (Join-Path $repo 'target\licenses\THIRD_PARTY_NOTICES.txt'),
    (Join-Path $repo 'target\sbom\bom.json')
) + $dockerPayloadSources) {
    if (-not (Test-Path -LiteralPath $evidence -PathType Leaf)) { throw "Required distribution evidence not found: $evidence" }
}

function Get-JdepsModules([string]$Artifact) {
    $previous = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = @(& $jdeps '--multi-release' '21' '--ignore-missing-deps' '--print-module-deps' $Artifact 2>&1 |
            ForEach-Object { $_.ToString().Trim() })
        $exit = $LASTEXITCODE
    }
    finally { $ErrorActionPreference = $previous }
    if ($exit -ne 0) { throw "jdeps failed for $Artifact`: $($output -join [Environment]::NewLine)" }
    $line = $output | Where-Object { $_ -match '^[A-Za-z0-9_.]+(?:,[A-Za-z0-9_.]+)*$' } | Select-Object -Last 1
    if ([string]::IsNullOrWhiteSpace($line)) { return @() }
    return @($line -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ })
}

$modules = @('java.se', 'jdk.crypto.ec', 'jdk.unsupported')
foreach ($artifact in @($cliJar, $mcpJar, $assistantJar)) { $modules += Get-JdepsModules $artifact }
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
Copy-Item -LiteralPath $cliJar -Destination (Join-Path $stage 'nexus-cli.jar')

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
    '--java-options', $nativeAccessArgument,
    '--dest', $appImages,
    '--win-console'
)
if ($LASTEXITCODE -ne 0) { throw "jpackage app-image failed with exit code $LASTEXITCODE" }

$appImage = Join-Path $appImages 'nexus'
$launcher = Join-Path $appImage 'nexus.exe'
$runtimeJava = Join-Path $appImage 'runtime\bin\java.exe'
foreach ($required in @($launcher, $runtimeJava)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Invalid NEXUS app-image; missing $required" }
}

Move-Item -LiteralPath $appImage -Destination (Join-Path $distribution 'app')
New-Item -ItemType Directory -Force -Path (Join-Path $distribution 'lib'), (Join-Path $distribution 'rest'), (Join-Path $distribution 'docker') | Out-Null
Copy-Item -LiteralPath $cliJar -Destination (Join-Path $distribution 'lib\nexus-cli.jar')
Copy-Item -LiteralPath $mcpJar -Destination (Join-Path $distribution 'lib\nexus-mcp.jar')
Copy-Item -LiteralPath $assistantJar -Destination (Join-Path $distribution 'lib\nexus-assistant-clients.jar')
Copy-Item -Path (Join-Path $restRoot '*') -Destination (Join-Path $distribution 'rest') -Recurse
foreach ($dockerPayload in $dockerPayloadSources) {
    Copy-Item -LiteralPath $dockerPayload -Destination (Join-Path $distribution ('docker\' + [IO.Path]::GetFileName($dockerPayload)))
}
Copy-Item -LiteralPath (Join-Path $repo 'LICENSE') -Destination (Join-Path $distribution 'LICENSE')
Copy-Item -LiteralPath (Join-Path $repo 'target\licenses\THIRD_PARTY_NOTICES.txt') -Destination (Join-Path $distribution 'THIRD_PARTY_NOTICES.txt')
Copy-Item -LiteralPath (Join-Path $repo 'target\sbom\bom.json') -Destination (Join-Path $distribution 'SBOM.cdx.json')
Copy-Item -LiteralPath (Join-Path $repo 'distribution\README.md') -Destination (Join-Path $distribution 'README.md')
$Version | Set-Content -LiteralPath (Join-Path $distribution 'VERSION') -Encoding ascii
$moduleList | Set-Content -LiteralPath (Join-Path $distribution 'RUNTIME-MODULES.txt') -Encoding ascii

@'
@echo off
setlocal DisableDelayedExpansion
if exist "%~dp0config\nexus-native.env.cmd" call "%~dp0config\nexus-native.env.cmd"
"%~dp0app\nexus.exe" %*
exit /b %ERRORLEVEL%
'@ | Set-Content -LiteralPath (Join-Path $distribution 'nexus.cmd') -Encoding ascii

@'
@echo off
setlocal DisableDelayedExpansion
if exist "%~dp0config\nexus-native.env.cmd" call "%~dp0config\nexus-native.env.cmd"
"%~dp0app\runtime\bin\java.exe" --enable-native-access=ALL-UNNAMED -jar "%~dp0lib\nexus-mcp.jar" %*
exit /b %ERRORLEVEL%
'@ | Set-Content -LiteralPath (Join-Path $distribution 'nexus-mcp.cmd') -Encoding ascii

@'
@echo off
setlocal DisableDelayedExpansion
if exist "%~dp0config\nexus-native.env.cmd" call "%~dp0config\nexus-native.env.cmd"
"%~dp0app\runtime\bin\java.exe" --enable-native-access=ALL-UNNAMED -jar "%~dp0lib\nexus-assistant-clients.jar" %*
exit /b %ERRORLEVEL%
'@ | Set-Content -LiteralPath (Join-Path $distribution 'nexus-assistant-clients.cmd') -Encoding ascii

@'
@echo off
setlocal DisableDelayedExpansion
if exist "%~dp0config\nexus-native.env.cmd" call "%~dp0config\nexus-native.env.cmd"
if "%NEXUS_REST_HOST%"=="" set "NEXUS_REST_HOST=127.0.0.1"
if "%NEXUS_REST_PORT%"=="" set "NEXUS_REST_PORT=8080"
"%~dp0app\runtime\bin\java.exe" --enable-native-access=ALL-UNNAMED "-Dquarkus.http.host=%NEXUS_REST_HOST%" "-Dquarkus.http.port=%NEXUS_REST_PORT%" -jar "%~dp0rest\quarkus-run.jar" %*
exit /b %ERRORLEVEL%
'@ | Set-Content -LiteralPath (Join-Path $distribution 'nexus-rest.cmd') -Encoding ascii

@'
@echo off
setlocal DisableDelayedExpansion
if exist "%~dp0docker\.env" for /f "usebackq tokens=1,* delims==" %%A in ("%~dp0docker\.env") do if /I "%%A"=="NEXUS_DOCKER_CONTAINER" set "NEXUS_DOCKER_CONTAINER=%%B"
if "%NEXUS_DOCKER_CONTAINER%"=="" set "NEXUS_DOCKER_CONTAINER=nexus"
docker exec -i "%NEXUS_DOCKER_CONTAINER%" java --enable-native-access=ALL-UNNAMED -jar /opt/nexus/lib/nexus-mcp.jar %*
exit /b %ERRORLEVEL%
'@ | Set-Content -LiteralPath (Join-Path $distribution 'nexus-docker-mcp.cmd') -Encoding ascii

@'
@echo off
setlocal DisableDelayedExpansion
if exist "%~dp0docker\.env" for /f "usebackq tokens=1,* delims==" %%A in ("%~dp0docker\.env") do if /I "%%A"=="NEXUS_DOCKER_CONTAINER" set "NEXUS_DOCKER_CONTAINER=%%B"
if "%NEXUS_DOCKER_CONTAINER%"=="" set "NEXUS_DOCKER_CONTAINER=nexus"
docker exec -i "%NEXUS_DOCKER_CONTAINER%" java --enable-native-access=ALL-UNNAMED -jar /opt/nexus/lib/nexus-cli.jar %*
exit /b %ERRORLEVEL%
'@ | Set-Content -LiteralPath (Join-Path $distribution 'nexus-docker.cmd') -Encoding ascii

$signer = Join-Path $PSScriptRoot 'sign-windows-artifact.ps1'
if (-not (Test-Path -LiteralPath $signer -PathType Leaf)) {
    throw "Windows signing helper missing: $signer"
}
& $signer -Path (Join-Path $distribution 'app\nexus.exe')

$sbomAugmenter = Join-Path $PSScriptRoot 'augment-windows-sbom.ps1'
if (-not (Test-Path -LiteralPath $sbomAugmenter -PathType Leaf)) {
    throw "Windows SBOM helper missing: $sbomAugmenter"
}
& $sbomAugmenter `
    -DistributionRoot $distribution `
    -SbomPath (Join-Path $distribution 'SBOM.cdx.json') `
    -ProjectVersion $Version `
    -JavaVersion $javaRuntimeVersion

$smokeHome = Join-Path $OutputRoot '.distribution-smoke-home'
Remove-Item -LiteralPath $smokeHome -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $smokeHome | Out-Null
$previousHome = $env:NEXUS_HOME
try {
    $env:NEXUS_HOME = $smokeHome
    $versionJson = & (Join-Path $distribution 'nexus.cmd') '--version' '--json' | Out-String
    if ($LASTEXITCODE -ne 0) { throw "Packaged NEXUS launcher failed with exit code $LASTEXITCODE" }
    $parsed = $versionJson | ConvertFrom-Json
    if ($parsed.version -ne $Version) { throw "Packaged NEXUS version mismatch: expected $Version, got $($parsed.version)" }

    $assistantUsage = & (Join-Path $distribution 'nexus-assistant-clients.cmd') | Out-String
    if ($LASTEXITCODE -ne 0 -or $assistantUsage -notmatch 'Usage:') { throw 'Packaged assistant integration runner smoke failed.' }
}
finally {
    $env:NEXUS_HOME = $previousHome
    Remove-Item -LiteralPath $smokeHome -Recurse -Force -ErrorAction SilentlyContinue
}

$runtimeModules = @(& (Join-Path $distribution 'app\runtime\bin\java.exe') '--list-modules' 2>&1 | ForEach-Object { $_.ToString().Trim() })
foreach ($requiredModule in @('java.base', 'java.sql', 'java.net.http', 'jdk.unsupported')) {
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
Write-Host "Bundled Java : $javaRuntimeVersion"
Write-Host 'SBOM         : Maven dependencies + signed/runtime file inventory'
Write-Host 'Docker       : compose + runtime Dockerfile + entrypoint + healthcheck are canonical payload'
Write-Host 'Surfaces     : CLI + MCP STDIO + REST + assistant integrations + Docker launchers'
Write-Output $distribution
