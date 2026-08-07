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
    'docker\docker-compose.yml.template'
)) {
    if (-not (Test-Path -LiteralPath (Join-Path $DistributionRoot $required) -PathType Leaf)) {
        throw "Invalid NEXUS Windows distribution; missing $required"
    }
}

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

# Harden Docker prerequisite detection before compiling the generated Inno source.
# A docker.exe on PATH is only a client binary; it must not suppress Docker Desktop
# installation unless it can actually reach a Docker engine. A stopped but installed
# Docker Desktop is also accepted because the post-install launcher starts it.
$legacyDockerDetection = @'
function DockerRuntimePresent(): Boolean;
begin
  Result := (DockerCliExecutable() <> '') or (DockerDesktopExecutable() <> '');
end;
'@
$strictDockerDetection = @'
function DockerEngineReady(): Boolean;
var
  DockerCli: String;
  ResultCode: Integer;
begin
  Result := False;
  DockerCli := DockerCliExecutable();
  if DockerCli = '' then exit;

  if DockerCli = 'docker' then
    Result := Exec(ExpandConstant('{cmd}'), '/D /S /C "docker info >nul 2>nul"', '', SW_HIDE, ewWaitUntilTerminated, ResultCode) and (ResultCode = 0)
  else
    Result := Exec(DockerCli, 'info', '', SW_HIDE, ewWaitUntilTerminated, ResultCode) and (ResultCode = 0);
end;

function DockerRuntimePresent(): Boolean;
begin
  Result := DockerEngineReady() or (DockerDesktopExecutable() <> '');
end;
'@
if ($iss.IndexOf($legacyDockerDetection, [StringComparison]::Ordinal) -lt 0) {
    throw 'Installer template Docker runtime detector changed unexpectedly; refusing to build an EXE that could skip Docker Desktop bootstrap.'
}
$iss = $iss.Replace($legacyDockerDetection, $strictDockerDetection)

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
if ($iss.IndexOf('function DockerEngineReady(): Boolean;', [StringComparison]::Ordinal) -lt 0) {
    throw 'Generated installer is missing strict Docker engine readiness detection.'
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
    $hash = (Get-FileHash -LiteralPath $setup -Algorithm SHA256).Hash.ToLowerInvariant()
    "$hash  $([IO.Path]::GetFileName($setup))" | Set-Content -LiteralPath $checksum -Encoding ascii

    Write-Host ''
    Write-Host $(if ($Smoke) { 'NEXUS Windows smoke setup SUCCESS' } else { 'NEXUS Windows setup SUCCESS' }) -ForegroundColor Green
    Write-Host "Setup   : $setup"
    Write-Host "SHA-256 : $hash"
    Write-Host 'Wizard  : Native / Docker / Both + runtime/integration customization'
    Write-Host 'Docker  : client-only docker.exe does not suppress Docker Desktop bootstrap'
    Write-Output $setup
}
finally {
    Remove-Item -LiteralPath $generatedIss -Force -ErrorAction SilentlyContinue
}
