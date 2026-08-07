[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Setup,
    [string]$Version = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ($env:OS -ne 'Windows_NT') { throw 'Installer smoke is Windows-only.' }
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
[xml]$pom = Get-Content -LiteralPath (Join-Path $repo 'pom.xml') -Raw
if ([string]::IsNullOrWhiteSpace($Version)) { $Version = [string]$pom.project.version }
$Setup = [IO.Path]::GetFullPath($Setup)
if (-not (Test-Path -LiteralPath $Setup -PathType Leaf)) { throw "Setup not found: $Setup" }

function Assert-TextContains {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Needle,
        [Parameter(Mandatory = $true)][string]$Description
    )
    if (-not $Text.Contains($Needle, [StringComparison]::Ordinal)) {
        throw "Windows installer contract missing: $Description"
    }
}

Write-Host '[contract] Docker Desktop bootstrap'
$installerTemplate = Join-Path $repo 'packaging\windows\nexus-installer.iss.template'
if (-not (Test-Path -LiteralPath $installerTemplate -PathType Leaf)) {
    throw "Installer template not found: $installerTemplate"
}
$installerSource = Get-Content -Raw -LiteralPath $installerTemplate
Assert-TextContains -Text $installerSource -Needle 'DockerPrereqPage' -Description 'Docker prerequisite wizard page'
Assert-TextContains -Text $installerSource -Needle 'DownloadTemporaryFile(' -Description 'runtime Docker Desktop download'
Assert-TextContains -Text $installerSource -Needle 'https://desktop.docker.com/win/main/amd64/Docker%20Desktop%20Installer.exe' -Description 'official Docker Desktop x64 URL'
Assert-TextContains -Text $installerSource -Needle 'Get-AuthenticodeSignature' -Description 'Authenticode verification'
Assert-TextContains -Text $installerSource -Needle 'CN=Docker Inc' -Description 'Docker Inc signer constraint'
Assert-TextContains -Text $installerSource -Needle 'install --user --backend=wsl-2 --quiet' -Description 'per-user WSL 2 Docker Desktop install command'
Assert-TextContains -Text $installerSource -Needle 'function PrepareToInstall' -Description 'post-Ready prerequisite bootstrap hook'
Assert-TextContains -Text $installerSource -Needle 'DockerDesktopInstalledDuringSetup' -Description 'Docker Desktop installation state tracking'
Assert-TextContains -Text $installerSource -Needle 'nexus-docker-up.cmd' -Description 'Docker runtime startup launcher'
if ($installerSource.Contains('--accept-license', [StringComparison]::Ordinal)) {
    throw 'Windows installer must not accept the Docker Desktop license on behalf of the user.'
}

$root = Join-Path $repo 'target\installer-smoke'
$install = Join-Path $root 'install'
$data = Join-Path $root 'nexus-home'
Remove-Item -LiteralPath $root -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $install, $data | Out-Null
$sentinel = Join-Path $data 'preserve-me.txt'
'preserve' | Set-Content -LiteralPath $sentinel -Encoding ascii

$arguments = @(
    '/VERYSILENT',
    '/SUPPRESSMSGBOXES',
    '/NORESTART',
    '/CURRENTUSER',
    "/DIR=`"$install`""
)
$process = Start-Process -FilePath $Setup -ArgumentList $arguments -Wait -PassThru
if ($process.ExitCode -ne 0) { throw "Setup smoke install failed with exit code $($process.ExitCode)" }

foreach ($required in @(
    'nexus.cmd',
    'nexus-mcp.cmd',
    'nexus-assistant-clients.cmd',
    'app\nexus.exe',
    'app\runtime\bin\java.exe',
    'lib\nexus-cli.jar',
    'lib\nexus-mcp.jar',
    'lib\nexus-assistant-clients.jar',
    'config\nexus-native.env.cmd',
    'LICENSE',
    'THIRD_PARTY_NOTICES.txt',
    'SBOM.cdx.json'
)) {
    if (-not (Test-Path -LiteralPath (Join-Path $install $required) -PathType Leaf)) {
        throw "Installed NEXUS is missing $required"
    }
}

$previousHome = $env:NEXUS_HOME
try {
    $env:NEXUS_HOME = $data
    $json = & (Join-Path $install 'nexus.cmd') '--version' '--json' | Out-String
    if ($LASTEXITCODE -ne 0) { throw "Installed NEXUS CLI failed with exit code $LASTEXITCODE" }
    $parsed = $json | ConvertFrom-Json
    if ($parsed.version -ne $Version) { throw "Installed version mismatch: $($parsed.version) != $Version" }

    $assistant = Join-Path $install 'nexus-assistant-clients.cmd'
    $assistantUsage = & $assistant | Out-String
    if ($LASTEXITCODE -ne 0 -or $assistantUsage -notmatch 'Usage:') {
        throw 'Installed assistant integration runner smoke failed.'
    }
    if ($assistantUsage -notmatch 'claude-cli') {
        throw 'Installed assistant integration runner does not advertise Claude CLI.'
    }
    if ($assistantUsage -notmatch 'codex-desktop') {
        throw 'Installed assistant integration runner does not advertise Codex Desktop.'
    }

    $javaExe = Join-Path $install 'app\runtime\bin\java.exe'
    $mcpJar = Join-Path $install 'lib\nexus-mcp.jar'

    $claudeCommand = & $assistant 'claude-cli' 'native' $javaExe $mcpJar 'command' | Out-String
    if ($LASTEXITCODE -ne 0 -or $claudeCommand -notmatch 'claude mcp add --scope user nexus --') {
        throw 'Installed Claude CLI integration generation smoke failed.'
    }
    if ($claudeCommand -notmatch [regex]::Escape($javaExe)) {
        throw 'Claude CLI integration does not target the bundled Java runtime.'
    }

    $codexToml = & $assistant 'codex-desktop' 'native' $javaExe $mcpJar 'toml' | Out-String
    if ($LASTEXITCODE -ne 0 -or $codexToml -notmatch '\[mcp_servers\.nexus\]') {
        throw 'Installed Codex Desktop TOML generation smoke failed.'
    }
    if ($codexToml -notmatch 'nexus-mcp\.jar') {
        throw 'Codex Desktop TOML does not target the NEXUS MCP runner.'
    }
}
finally {
    $env:NEXUS_HOME = $previousHome
}

$uninstaller = Get-ChildItem -LiteralPath $install -Filter 'unins*.exe' -File | Select-Object -First 1
if ($null -eq $uninstaller) { throw 'Inno Setup uninstaller was not installed.' }
$uninstallProcess = Start-Process -FilePath $uninstaller.FullName -ArgumentList @('/VERYSILENT','/SUPPRESSMSGBOXES','/NORESTART') -Wait -PassThru
if ($uninstallProcess.ExitCode -ne 0) { throw "Setup smoke uninstall failed with exit code $($uninstallProcess.ExitCode)" }
Start-Sleep -Milliseconds 500
if (Test-Path -LiteralPath (Join-Path $install 'app\nexus.exe')) {
    throw 'NEXUS executable still exists after uninstall.'
}
if (-not (Test-Path -LiteralPath $sentinel -PathType Leaf)) {
    throw 'NEXUS_HOME user data was deleted by uninstall.'
}

Write-Host 'NEXUS Windows installer Docker-bootstrap contract + install/CLI/MCP/Claude/Codex payload/uninstall smoke PASS' -ForegroundColor Green
Remove-Item -LiteralPath $root -Recurse -Force -ErrorAction SilentlyContinue
