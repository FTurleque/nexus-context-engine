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

# The installer must be able to build the Docker runtime locally when the configured
# registry image is unavailable/private. Stage the runtime-only Dockerfile and entrypoint
# directly into the distribution consumed by Inno Setup.
$dockerPayloadRoot = Join-Path $DistributionRoot 'docker'
New-Item -ItemType Directory -Force -Path $dockerPayloadRoot | Out-Null
$dockerRuntimePayload = @(
    @{ Source = (Join-Path $repo 'packaging\docker\Dockerfile.runtime'); Destination = (Join-Path $dockerPayloadRoot 'Dockerfile.runtime') },
    @{ Source = (Join-Path $repo 'packaging\docker\nexus-container-entrypoint.sh'); Destination = (Join-Path $dockerPayloadRoot 'nexus-container-entrypoint.sh') }
)
foreach ($item in $dockerRuntimePayload) {
    if (-not (Test-Path -LiteralPath $item.Source -PathType Leaf)) {
        throw "Required Docker fallback payload missing: $($item.Source)"
    }
    Copy-Item -LiteralPath $item.Source -Destination $item.Destination -Force
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

# Include every Docker runtime support file staged in the Windows distribution.
$legacyDockerFiles = 'Source: "{#NexusSourceDir}\docker\docker-compose.yml.template"; DestDir: "{app}\docker"; Flags: ignoreversion; Check: InstallDocker'
$expandedDockerFiles = 'Source: "{#NexusSourceDir}\docker\*"; DestDir: "{app}\docker"; Flags: ignoreversion recursesubdirs createallsubdirs; Check: InstallDocker'
if ($iss.IndexOf($legacyDockerFiles, [StringComparison]::Ordinal) -lt 0) {
    throw 'Installer template Docker payload entry changed unexpectedly; refusing to omit local Docker fallback files.'
}
$iss = $iss.Replace($legacyDockerFiles, $expandedDockerFiles)

# The configured GHCR image can legitimately be unavailable before merge or remain private.
# Prefer an existing local image, then try the registry, then build the same configured tag
# from the NEXUS payload already installed on disk.
$legacyComposeCommand = [char]39 + '"%DOCKER_EXE%" compose --env-file "%~dp0docker\.env" -f "%~dp0docker\docker-compose.yml" up -d' + [char]39
$fallbackComposeCommands = @'
'set "NEXUS_DOCKER_IMAGE="' + #13#10 +
    'if exist "%~dp0docker\.env" for /f "usebackq tokens=1,* delims==" %%A in ("%~dp0docker\.env") do if /I "%%A"=="NEXUS_DOCKER_IMAGE" set "NEXUS_DOCKER_IMAGE=%%B"' + #13#10 +
    'if "%NEXUS_DOCKER_IMAGE%"=="" echo [NEXUS] NEXUS_DOCKER_IMAGE est absent de docker\.env. & if "%NEXUS_DOCKER_IMAGE%"=="" exit /b 31' + #13#10 +
    '"%DOCKER_EXE%" image inspect "%NEXUS_DOCKER_IMAGE%" >nul 2>nul && goto nexus_image_ready' + #13#10 +
    'echo [NEXUS] Image %NEXUS_DOCKER_IMAGE% absente localement; tentative de telechargement...' + #13#10 +
    '"%DOCKER_EXE%" pull "%NEXUS_DOCKER_IMAGE%" && goto nexus_image_ready' + #13#10 +
    'echo [NEXUS] Image registre indisponible ou privee; construction locale depuis le payload installe...' + #13#10 +
    'if not exist "%~dp0docker\Dockerfile.runtime" echo [NEXUS] Dockerfile.runtime introuvable. & if not exist "%~dp0docker\Dockerfile.runtime" exit /b 32' + #13#10 +
    'if not exist "%~dp0docker\nexus-container-entrypoint.sh" echo [NEXUS] Entrypoint Docker introuvable. & if not exist "%~dp0docker\nexus-container-entrypoint.sh" exit /b 33' + #13#10 +
    '"%DOCKER_EXE%" build --pull --file "%~dp0docker\Dockerfile.runtime" --tag "%NEXUS_DOCKER_IMAGE%" "%~dp0"' + #13#10 +
    'if errorlevel 1 echo [NEXUS] Construction locale de l''image NEXUS echouee. & if errorlevel 1 exit /b 34' + #13#10 +
    ':nexus_image_ready' + #13#10 +
    '"%DOCKER_EXE%" compose --env-file "%~dp0docker\.env" -f "%~dp0docker\docker-compose.yml" up -d'
'@
if ($iss.IndexOf($legacyComposeCommand, [StringComparison]::Ordinal) -lt 0) {
    throw 'Installer template Docker compose launcher changed unexpectedly; refusing to build without registry fallback.'
}
$iss = $iss.Replace($legacyComposeCommand, $fallbackComposeCommands.TrimEnd("`r", "`n"))

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
foreach ($requiredFallbackFragment in @(
    'Dockerfile.runtime',
    '"%DOCKER_EXE%" pull "%NEXUS_DOCKER_IMAGE%"',
    '"%DOCKER_EXE%" build --pull --file',
    ':nexus_image_ready'
)) {
    if ($iss.IndexOf($requiredFallbackFragment, [StringComparison]::OrdinalIgnoreCase) -lt 0) {
        throw "Generated installer is missing Docker registry fallback fragment: $requiredFallbackFragment"
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
    $hash = (Get-FileHash -LiteralPath $setup -Algorithm SHA256).Hash.ToLowerInvariant()
    "$hash  $([IO.Path]::GetFileName($setup))" | Set-Content -LiteralPath $checksum -Encoding ascii

    Write-Host ''
    Write-Host $(if ($Smoke) { 'NEXUS Windows smoke setup SUCCESS' } else { 'NEXUS Windows setup SUCCESS' }) -ForegroundColor Green
    Write-Host "Setup   : $setup"
    Write-Host "SHA-256 : $hash"
    Write-Host 'Wizard  : Native / Docker / Both + runtime/integration customization'
    Write-Host 'Docker  : strict engine detection + registry pull with local runtime-image fallback'
    Write-Output $setup
}
finally {
    Remove-Item -LiteralPath $generatedIss -Force -ErrorAction SilentlyContinue
}
