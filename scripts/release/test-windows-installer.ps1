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
    if ($Text.IndexOf($Needle, [StringComparison]::Ordinal) -lt 0) {
        throw "Windows installer contract missing: $Description"
    }
}

Write-Host '[contract] Docker Desktop bootstrap'
$installerTemplate = Join-Path $repo 'packaging\windows\nexus-installer.iss.template'
if (-not (Test-Path -LiteralPath $installerTemplate -PathType Leaf)) {
    throw "Installer template not found: $installerTemplate"
}
$installerSource = [IO.File]::ReadAllText($installerTemplate, [Text.Encoding]::UTF8)
Assert-TextContains -Text $installerSource -Needle 'DockerPrereqPage' -Description 'Docker prerequisite wizard page'
Assert-TextContains -Text $installerSource -Needle 'DownloadTemporaryFile(' -Description 'runtime Docker Desktop download'
Assert-TextContains -Text $installerSource -Needle 'https://desktop.docker.com/win/main/amd64/Docker%20Desktop%20Installer.exe' -Description 'official Docker Desktop x64 URL'
Assert-TextContains -Text $installerSource -Needle 'Get-AuthenticodeSignature' -Description 'Authenticode verification'
Assert-TextContains -Text $installerSource -Needle 'CN=Docker Inc' -Description 'Docker Inc signer constraint'
Assert-TextContains -Text $installerSource -Needle 'install --user --backend=wsl-2 --quiet' -Description 'per-user WSL 2 Docker Desktop install command'
Assert-TextContains -Text $installerSource -Needle 'function PrepareToInstall' -Description 'post-Ready prerequisite bootstrap hook'
Assert-TextContains -Text $installerSource -Needle 'DockerDesktopInstalledDuringSetup' -Description 'Docker Desktop installation state tracking'
Assert-TextContains -Text $installerSource -Needle 'nexus-docker-up.cmd' -Description 'Docker runtime startup launcher'
if ($installerSource.IndexOf('--accept-license', [StringComparison]::Ordinal) -ge 0) {
    throw 'Windows installer must not accept the Docker Desktop license on behalf of the user.'
}

Write-Host '[contract] deterministic generated-source hardening'
$hardener = Join-Path $repo 'scripts\release\harden-windows-installer-source.ps1'
if (-not (Test-Path -LiteralPath $hardener -PathType Leaf)) { throw "Installer hardener not found: $hardener" }
. $hardener
$hardenedSource = Protect-NexusInstallerSource -Source $installerSource
Assert-TextContains -Text $hardenedSource -Needle 'function CmdEnvEscape(Value: String): String;' -Description 'cmd percent escaping helper'
Assert-TextContains -Text $hardenedSource -Needle 'function DotEnvQuoted(Value: String): String;' -Description 'Compose dotenv quoting helper'
Assert-TextContains -Text $hardenedSource -Needle 'function IsLoopbackRestHost(Value: String): Boolean;' -Description 'wizard loopback-only REST policy'
Assert-TextContains -Text $hardenedSource -Needle 'CmdEnvEscape(RuntimePage.Values[0])' -Description 'NEXUS_HOME batch escaping'
Assert-TextContains -Text $hardenedSource -Needle 'DotEnvQuoted(DockerPath(RuntimePage.Values[0]))' -Description 'Docker home path dotenv quoting'
Assert-TextContains -Text $hardenedSource -Needle 'NEXUS_REST_EXPOSURE_MODE=loopback-forward' -Description 'explicit Docker REST exposure mode'
if ($hardenedSource.IndexOf("'set `"NEXUS_HOME=' + RuntimePage.Values[0]", [StringComparison]::Ordinal) -ge 0) {
    throw 'Generated installer source still writes raw NEXUS_HOME into batch configuration.'
}

Write-Host '[contract] template carries Docker runtime logic; build applies only security hardening'
Assert-TextContains -Text $installerSource -Needle 'function DockerEngineReady(): Boolean;' -Description 'strict Docker engine detection lives in the template'
Assert-TextContains -Text $installerSource -Needle 'Source: "{#NexusSourceDir}\docker\*"' -Description 'full Docker payload staged from the template'
Assert-TextContains -Text $installerSource -Needle ':nexus_image_ready' -Description 'local-image/pull/build fallback lives in the template'
Assert-TextContains -Text $installerSource -Needle '"%DOCKER_EXE%" build --pull --file' -Description 'local runtime-image build fallback lives in the template'
$installerBuilder = Join-Path $repo 'scripts\release\build-windows-installer.ps1'
$builderSource = Get-Content -Raw -LiteralPath $installerBuilder
Assert-TextContains -Text $builderSource -Needle 'Protect-NexusInstallerSource' -Description 'deterministic generated-source hardening hook'
Assert-TextContains -Text $builderSource -Needle 'missing strict Docker engine readiness detection' -Description 'build-time integrity guard for Docker detector drift'

Write-Host '[contract] Ollama auto-install is Authenticode-verified (fail-closed)'
Assert-TextContains -Text $installerSource -Needle 'function VerifyOllamaInstaller' -Description 'Ollama installer Authenticode verifier'
Assert-TextContains -Text $installerSource -Needle 'CN=Ollama Inc' -Description 'Ollama Inc signer constraint'
$ollamaVerifyIdx = $installerSource.IndexOf('VerifyOllamaInstaller(InstallerPath)', [StringComparison]::Ordinal)
$ollamaExecIdx = $installerSource.IndexOf("Exec(InstallerPath, '/S'", [StringComparison]::Ordinal)
if ($ollamaVerifyIdx -lt 0 -or $ollamaExecIdx -lt 0 -or $ollamaVerifyIdx -gt $ollamaExecIdx) {
    throw 'Ollama installer must be Authenticode-verified BEFORE it is executed (fail-closed ordering).'
}

Write-Host '[contract] REST Bearer token uses a CSPRNG, not Random()'
Assert-TextContains -Text $installerSource -Needle 'BCryptGenRandom' -Description 'REST token generated via Windows CSPRNG'
if ($installerSource.IndexOf('Random(Length(Chars))', [StringComparison]::Ordinal) -ge 0) {
    throw 'REST token must not be generated with the non-cryptographic Random().'
}

Write-Host '[contract] Ollama endpoint is Docker-runtime aware'
Assert-TextContains -Text $installerSource -Needle 'function OllamaUrlForDocker' -Description 'Docker Ollama endpoint resolver'
Assert-TextContains -Text $installerSource -Needle 'host.docker.internal' -Description 'Docker loopback resolves to host gateway'

Write-Host '[contract] assistant MCP wiring writes clean JSON and reversible markers'
Assert-TextContains -Text $installerSource -Needle 'function Format-Json(' -Description 'clean 2-space JSON formatter helper'
Assert-TextContains -Text $installerSource -Needle 'Save-CleanJson $mcp $c' -Description 'JetBrains config written via clean formatter'
Assert-TextContains -Text $installerSource -Needle 'Save-CleanJson $cfg $c' -Description 'Claude Desktop config written via clean formatter'
Assert-TextContains -Text $installerSource -Needle '# END NEXUS-MCP' -Description 'Codex block has a reversible END marker'
foreach ($rawDepth in @('ConvertTo-Json -Depth 5', 'ConvertTo-Json -Depth 10')) {
    if ($installerSource.IndexOf($rawDepth, [StringComparison]::Ordinal) -ge 0) {
        throw "Installer must not write MCP config JSON via raw '$rawDepth' (mangles indentation); use Save-CleanJson."
    }
}

Write-Host '[contract] client-only docker.exe must not suppress Docker Desktop bootstrap'
Assert-TextContains -Text $installerSource -Needle 'docker info >nul 2>nul' -Description 'real Docker engine reachability probe'
Assert-TextContains -Text $installerSource -Needle 'Result := DockerEngineReady() or (DockerDesktopExecutable() <> '''');' -Description 'Docker Desktop or working engine prerequisite semantics'

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
    'nexus-rest.cmd',
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

foreach ($launcherName in @('nexus.cmd','nexus-mcp.cmd','nexus-rest.cmd','nexus-assistant-clients.cmd','nexus-docker.cmd','nexus-docker-mcp.cmd')) {
    $launcherPath = Join-Path $install $launcherName
    if (Test-Path -LiteralPath $launcherPath) {
        $launcherSource = Get-Content -Raw -LiteralPath $launcherPath
        Assert-TextContains -Text $launcherSource -Needle 'setlocal DisableDelayedExpansion' -Description "$launcherName disables delayed expansion"
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

    $mcpProtocolSmoke = Join-Path $PSScriptRoot 'test-mcp-protocol.ps1'
    & $mcpProtocolSmoke -Exe $javaExe -LaunchArgs @('-jar', $mcpJar) -NexusHome $env:NEXUS_HOME -Label 'Native'

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

Write-Host 'NEXUS Windows installer hardened source + install/CLI/MCP/Claude/Codex payload/uninstall smoke PASS' -ForegroundColor Green
Remove-Item -LiteralPath $root -Recurse -Force -ErrorAction SilentlyContinue
