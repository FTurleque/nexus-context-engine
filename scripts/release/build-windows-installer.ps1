[CmdletBinding()]
param(
    [string]$Version = '',
    [string]$DistributionRoot = '',
    [string]$OutputRoot = '',
    [string]$IsccPath = '',
    [switch]$Smoke
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ($env:OS -ne 'Windows_NT') {
    throw 'The NEXUS Windows installer must be built on Windows.'
}

$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
[xml]$pom = Get-Content -LiteralPath (Join-Path $repo 'pom.xml') -Raw
$projectVersion = [string]$pom.project.version
if ([string]::IsNullOrWhiteSpace($Version)) { $Version = $projectVersion }
if ($Version -ne $projectVersion) {
    throw "Requested version $Version does not match pom.xml version $projectVersion."
}
if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
    $OutputRoot = Join-Path $repo 'target\dist'
}
$OutputRoot = [IO.Path]::GetFullPath($OutputRoot)
$distributionName = "nexus-context-engine-$Version-windows-x64"
if ([string]::IsNullOrWhiteSpace($DistributionRoot)) {
    $DistributionRoot = Join-Path $OutputRoot $distributionName
}
$DistributionRoot = [IO.Path]::GetFullPath($DistributionRoot)

foreach ($required in @(
    'nexus.cmd',
    'nexus-mcp.cmd',
    'nexus-rest.cmd',
    'nexus-assistant-clients.cmd',
    'nexus-docker.cmd',
    'nexus-docker-mcp.cmd',
    'VERSION',
    'RUNTIME-MODULES.txt',
    'LICENSE',
    'THIRD_PARTY_NOTICES.txt',
    'SBOM.cdx.json',
    'app\nexus.exe',
    'app\runtime\bin\java.exe',
    'lib\nexus-cli.jar',
    'lib\nexus-mcp.jar',
    'lib\nexus-assistant-clients.jar',
    'rest\quarkus-run.jar',
    'docker\docker-compose.yml.template',
    'docker\Dockerfile.runtime',
    'docker\nexus-container-entrypoint.sh',
    'docker\nexus-container-healthcheck.sh'
)) {
    if (-not (Test-Path -LiteralPath (Join-Path $DistributionRoot $required) -PathType Leaf)) {
        throw "Invalid NEXUS Windows distribution; missing $required"
    }
}

# The installer consumes the exact canonical Docker fallback already included in the
# self-contained distribution. It must never mutate that payload after its SBOM and
# portable ZIP have been generated, otherwise installer and ZIP provenance diverge.

if ([string]::IsNullOrWhiteSpace($IsccPath)) {
    $ensure = Join-Path $PSScriptRoot 'ensure-inno-setup.ps1'
    $IsccPath = (& $ensure | Select-Object -Last 1)
}
if ([string]::IsNullOrWhiteSpace($IsccPath) -or -not (Test-Path -LiteralPath $IsccPath -PathType Leaf)) {
    throw "ISCC.exe not found: $IsccPath"
}

$template = Join-Path $repo 'packaging\windows\nexus-installer.iss.template'
if (-not (Test-Path -LiteralPath $template -PathType Leaf)) {
    throw "NEXUS Inno Setup template not found: $template"
}
$hardener = Join-Path $PSScriptRoot 'harden-windows-installer-source.ps1'
if (-not (Test-Path -LiteralPath $hardener -PathType Leaf)) {
    throw "NEXUS installer hardening helper not found: $hardener"
}
$restAuthHardener = Join-Path $PSScriptRoot 'harden-windows-rest-auth-source.ps1'
if (-not (Test-Path -LiteralPath $restAuthHardener -PathType Leaf)) {
    throw "NEXUS REST installer authentication helper not found: $restAuthHardener"
}
. $hardener
. $restAuthHardener

$work = Join-Path $OutputRoot '.installer'
$installerOutput = if ($Smoke) { Join-Path $OutputRoot '.smoke' } else { $OutputRoot }
New-Item -ItemType Directory -Force -Path $work, $installerOutput | Out-Null
$outputBase = if ($Smoke) { "NEXUS-$Version-windows-x64-smoke-setup" } else { "NEXUS-$Version-windows-x64-setup" }
$generatedIss = Join-Path $work "$outputBase.iss"
$setup = Join-Path $installerOutput "$outputBase.exe"
$checksum = "$setup.sha256"
Remove-Item -LiteralPath $generatedIss, $setup, $checksum -Force -ErrorAction SilentlyContinue

function Escape-Inno([string]$value) {
    return $value.Replace('"', '""')
}

$appVersion = (($Version -split '[-+]')[0]) + '.0'
$appId = if ($Smoke) { "NEXUS-Installer-Smoke-$Version" } else { '{{AE8110A7-3692-4A8F-9070-D1FF00E14200}' }
$smokeMode = if ($Smoke) { '1' } else { '0' }
$utf8 = New-Object System.Text.UTF8Encoding($false)
$iss = [IO.File]::ReadAllText($template, $utf8)

$iss = $iss.Replace('@@VERSION@@', (Escape-Inno $Version))
$iss = $iss.Replace('@@APP_VERSION@@', (Escape-Inno $appVersion))
$iss = $iss.Replace('@@APP_ID@@', (Escape-Inno $appId))
$iss = $iss.Replace('@@SMOKE_MODE@@', $smokeMode)
$iss = $iss.Replace('@@SOURCE_DIR@@', (Escape-Inno $DistributionRoot))
$iss = $iss.Replace('@@OUTPUT_DIR@@', (Escape-Inno $installerOutput))
$iss = $iss.Replace('@@OUTPUT_BASENAME@@', (Escape-Inno $outputBase))
if ($iss -match '@@[A-Z0-9_]+@@') {
    throw "Unresolved Inno Setup template token: $($Matches[0])"
}

# The source template remains human-readable; all values crossing into cmd.exe or
# Docker Compose are hardened deterministically here before compilation. Helpers
# are exact-anchor based and fail closed if the template drifts.
$iss = Protect-NexusInstallerSource -Source $iss
$iss = Protect-NexusNativeRestAuthSource -Source $iss

# Integrity guards on the generated source of truth.
if ($iss.IndexOf('function DockerEngineReady(): Boolean;', [StringComparison]::Ordinal) -lt 0) {
    throw 'Installer template is missing strict Docker engine readiness detection (DockerEngineReady).'
}
if ($iss.IndexOf('Source: "{#NexusSourceDir}\docker\*"', [StringComparison]::Ordinal) -lt 0) {
    throw 'Installer template must ship the full Docker payload (docker\* recurse) for the local fallback.'
}
foreach ($requiredHardeningFragment in @(
    'function CmdEnvEscape(Value: String): String;',
    'function DotEnvQuoted(Value: String): String;',
    'function IsLoopbackRestHost(Value: String): Boolean;',
    'function VerifyFileSha256(FilePath: String; ExpectedSha256: String): Boolean;',
    'NativeToken := GenerateLocalToken();',
    'RuntimePage.Values[3] := NativeToken;',
    'NEXUS_REST_EXPOSURE_MODE=loopback-forward',
    'https://desktop.docker.com/win/main/amd64/236216/Docker%20Desktop%20Installer.exe',
    '820438e75c16e44b393079154bea7d27958a15845c23a635b1a1f6f586b2ed44',
    'https://github.com/ollama/ollama/releases/download/v0.33.3/OllamaSetup.exe',
    '32cdcb1da477bc7fffbf1c1cdeeb99b1db003af094db56dd3c156abd04d34f8e'
)) {
    if ($iss.IndexOf($requiredHardeningFragment, [StringComparison]::Ordinal) -lt 0) {
        throw "Generated installer source is missing hardening fragment: $requiredHardeningFragment"
    }
}
foreach ($requiredFallbackFragment in @(
    'Dockerfile.runtime',
    '"%DOCKER_EXE%" pull "%NEXUS_DOCKER_IMAGE%"',
    '"%DOCKER_EXE%" build --pull --file',
    ':nexus_image_ready'
)) {
    if ($iss.IndexOf($requiredFallbackFragment, [StringComparison]::OrdinalIgnoreCase) -lt 0) {
        throw "Installer template is missing Docker registry fallback fragment: $requiredFallbackFragment"
    }
}
[IO.File]::WriteAllText($generatedIss, $iss, $utf8)

try {
    & $IsccPath $generatedIss
    if ($LASTEXITCODE -ne 0) {
        throw "Inno Setup compilation failed with exit code $LASTEXITCODE"
    }
    if (-not (Test-Path -LiteralPath $setup -PathType Leaf)) {
        throw "NEXUS setup executable was not produced: $setup"
    }

    $signer = Join-Path $PSScriptRoot 'sign-windows-artifact.ps1'
    if (-not (Test-Path -LiteralPath $signer -PathType Leaf)) {
        throw "Windows signing helper missing: $signer"
    }
    & $signer -Path $setup

    $hash = (Get-FileHash -LiteralPath $setup -Algorithm SHA256).Hash.ToLowerInvariant()
    "$hash  $([IO.Path]::GetFileName($setup))" | Set-Content -LiteralPath $checksum -Encoding ascii

    Write-Host ''
    Write-Host $(if ($Smoke) { 'NEXUS Windows smoke setup SUCCESS' } else { 'NEXUS Windows setup SUCCESS' }) -ForegroundColor Green
    Write-Host "Setup   : $setup"
    Write-Host "SHA-256 : $hash"
    Write-Host 'Wizard  : Native / Docker / Both + runtime/integration customization'
    Write-Host 'Security: authenticated loopback REST + hardened cmd/.env + pinned/hash-verified prerequisites + optional/required Authenticode'
    Write-Host 'Docker  : canonical distribution payload + strict engine detection + registry pull/local fallback'
    Write-Output $setup
}
finally {
    Remove-Item -LiteralPath $generatedIss -Force -ErrorAction SilentlyContinue
}
