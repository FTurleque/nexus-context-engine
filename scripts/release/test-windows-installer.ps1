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
Assert-TextContains -Text $installerSource -Needle 'https://desktop.docker.com/win/main/amd64/Docker%20Desktop%20Installer.exe' -Description 'template Docker Desktop x64 URL hardening anchor'
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
Assert-TextContains -Text $hardenedSource -Needle 'function VerifyFileSha256(FilePath: String; ExpectedSha256: String): Boolean;' -Description 'external prerequisite SHA-256 verifier'
Assert-TextContains -Text $hardenedSource -Needle 'CmdEnvEscape(RuntimePage.Values[0])' -Description 'NEXUS_HOME batch escaping'
Assert-TextContains -Text $hardenedSource -Needle "'NEXUS_HOME_BIND=nexus-data' + #13#10 +" -Description 'Docker home uses the named Compose volume'
Assert-TextContains -Text $hardenedSource -Needle 'DotEnvQuoted(OllamaPage.Values[0])' -Description 'Docker Ollama URL dotenv quoting'
Assert-TextContains -Text $hardenedSource -Needle 'NEXUS_REST_EXPOSURE_MODE=loopback-forward' -Description 'explicit Docker REST exposure mode'
if ($hardenedSource.IndexOf("'set `"NEXUS_HOME=' + RuntimePage.Values[0]", [StringComparison]::Ordinal) -ge 0) {
    throw 'Generated installer source still writes raw NEXUS_HOME into batch configuration.'
}

Write-Host '[contract] external prerequisite provenance is immutable and fail-closed'
$dockerPinnedUrl = 'https://desktop.docker.com/win/main/amd64/236216/Docker%20Desktop%20Installer.exe'
$dockerSha256 = '820438e75c16e44b393079154bea7d27958a15845c23a635b1a1f6f586b2ed44'
$ollamaPinnedUrl = 'https://github.com/ollama/ollama/releases/download/v0.33.3/OllamaSetup.exe'
$ollamaSha256 = '32cdcb1da477bc7fffbf1c1cdeeb99b1db003af094db56dd3c156abd04d34f8e'
Assert-TextContains -Text $hardenedSource -Needle $dockerPinnedUrl -Description 'qualified Docker Desktop 4.86.0 build 236216 URL'
Assert-TextContains -Text $hardenedSource -Needle $dockerSha256 -Description 'qualified Docker Desktop 4.86.0 SHA-256'
Assert-TextContains -Text $hardenedSource -Needle $ollamaPinnedUrl -Description 'qualified Ollama v0.33.3 release URL'
Assert-TextContains -Text $hardenedSource -Needle $ollamaSha256 -Description 'qualified Ollama v0.33.3 SHA-256'
if ($hardenedSource.IndexOf('https://desktop.docker.com/win/main/amd64/Docker%20Desktop%20Installer.exe', [StringComparison]::Ordinal) -ge 0) {
    throw 'Generated installer source must not retain the mutable Docker Desktop latest URL.'
}
if ($hardenedSource.IndexOf('https://ollama.com/download/OllamaSetup.exe', [StringComparison]::Ordinal) -ge 0) {
    throw 'Generated installer source must not retain the mutable Ollama latest URL.'
}

$dockerHashIdx = $hardenedSource.IndexOf("VerifyFileSha256(InstallerPath, '$dockerSha256')", [StringComparison]::Ordinal)
$dockerVerifyIdx = $hardenedSource.IndexOf('VerifyDockerDesktopInstaller(InstallerPath)', [StringComparison]::Ordinal)
$dockerExecIdx = $hardenedSource.IndexOf("Exec(InstallerPath, 'install --user --backend=wsl-2 --quiet'", [StringComparison]::Ordinal)
if ($dockerHashIdx -lt 0 -or $dockerVerifyIdx -lt 0 -or $dockerExecIdx -lt 0 -or
    $dockerHashIdx -gt $dockerVerifyIdx -or $dockerVerifyIdx -gt $dockerExecIdx) {
    throw 'Docker Desktop must be SHA-256 verified, then Authenticode verified, BEFORE execution.'
}

$ollamaHashIdx = $hardenedSource.IndexOf("VerifyFileSha256(InstallerPath, '$ollamaSha256')", [StringComparison]::Ordinal)
$ollamaVerifyIdx = $hardenedSource.IndexOf('VerifyOllamaInstaller(InstallerPath)', [StringComparison]::Ordinal)
$ollamaExecIdx = $hardenedSource.IndexOf("Exec(InstallerPath, '/S'", [StringComparison]::Ordinal)
if ($ollamaHashIdx -lt 0 -or $ollamaVerifyIdx -lt 0 -or $ollamaExecIdx -lt 0 -or
    $ollamaHashIdx -gt $ollamaVerifyIdx -or $ollamaVerifyIdx -gt $ollamaExecIdx) {
    throw 'Ollama must be SHA-256 verified, then Authenticode verified, BEFORE execution.'
}

Write-Host '[contract] template carries Docker runtime logic; build applies deterministic security/provenance hardening'
Assert-TextContains -Text $installerSource -Needle 'function DockerEngineReady(): Boolean;' -Description 'strict Docker engine detection lives in the template'
Assert-TextContains -Text $installerSource -Needle 'Source: "{#NexusSourceDir}\docker\*"' -Description 'full Docker payload staged from the template'
Assert-TextContains -Text $installerSource -Needle ':nexus_image_ready' -Description 'local-image/pull/build fallback lives in the template'
Assert-TextContains -Text $installerSource -Needle '"%DOCKER_EXE%" build --pull --file' -Description 'local runtime-image build fallback lives in the template'
$installerBuilder = Join-Path $repo 'scripts\release\build-windows-installer.ps1'
$builderSource = Get-Content -Raw -LiteralPath $installerBuilder
Assert-TextContains -Text $builderSource -Needle 'Protect-NexusInstallerSource' -Description 'deterministic generated-source hardening hook'
Assert-TextContains -Text $builderSource -Needle 'missing strict Docker engine readiness detection' -Description 'build-time integrity guard for Docker detector drift'
Assert-TextContains -Text $builderSource -Needle $dockerSha256 -Description 'build-time integrity guard for qualified Docker SHA-256'
Assert-TextContains -Text $builderSource -Needle $ollamaSha256 -Description 'build-time integrity guard for qualified Ollama SHA-256'

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
Assert-TextContains -Text $installerSource -Needle 'Save-CleanJson $cfg $c' -Description 'client configurations written via the clean formatter'
foreach ($jsonFragment in @(
    "'function Read-ClientJson([string]`$Path) {'",
    '[System.IO.File]::ReadAllText($Path, [System.Text.UTF8Encoding]::new($false))',
    '[System.IO.File]::Replace($tmp, $Path, $candidate)',
    "'`$added = Add-NexusServerEntry @(`$mcp) ''servers'' `$e'",
    "'`$added = Add-NexusServerEntry `$cfgs ''mcpServers'' `$e'",
    "'Remove-NexusServerEntry @(`$mcp) ''servers'' | Out-Null'",
    "'Remove-NexusServerEntry `$cfgs ''mcpServers'' | Out-Null'"
)) {
    Assert-TextContains -Text $installerSource -Needle $jsonFragment -Description "client JSON read/write: $jsonFragment"
}
if (([regex]::Matches($installerSource, [regex]::Escape("'`$ErrorActionPreference = ''Stop''' + #13#10 +"))).Count -lt 4) {
    throw 'Every generated JSON client script must stop on the first error.'
}
if ($installerSource -match 'Get-Content \$(mcp|cfg) -Raw \| ConvertFrom-Json') {
    throw 'Client configurations must be read by Read-ClientJson (UTF-8), not Get-Content.'
}
Assert-TextContains -Text $installerSource -Needle '# END NEXUS-MCP' -Description 'Codex block has a reversible END marker'

Write-Host '[contract] assistant client page runs a preflight and lists each client on its own row'
Assert-TextContains -Text $installerSource -Needle '\detect-assistant-clients.ps1"; Flags: dontcopy' -Description 'client detector embedded for the wizard preflight'
Assert-TextContains -Text $installerSource -Needle "ExtractTemporaryFile('detect-assistant-clients.ps1')" -Description 'client detector extracted before the page is shown'
foreach ($clientConnector in @('connect-copilot-cli.cmd', 'connect-copilot-jetbrains.cmd', 'connect-claude-cli.cmd', 'connect-claude-desktop.cmd', 'connect-codex-cli.cmd', 'connect-codex-desktop.cmd')) {
    Assert-TextContains -Text $installerSource -Needle ", '$clientConnector', '" -Description "client connector $clientConnector"
}

Write-Host '[contract] Claude Desktop is wired in every configuration folder present'
Assert-TextContains -Text $installerSource -Needle 'function ClaudeDesktopConfigsPs(): String;' -Description 'Claude Desktop configuration folders listed once'
Assert-TextContains -Text $installerSource -Needle "'if (`$cfgs.Count -eq 0) { exit 10 }'" -Description 'Claude Desktop connector loops over every configuration'

Write-Host '[contract] Claude Desktop configuration is only written while the app is closed'
foreach ($closedFragment in @(
    'function ClaudeDesktopClosedGuardPs(): String;',
    "RunWhileClaudeDesktopClosed(ExpandConstant('{app}\integrations\connect-claude-desktop.ps1'), RegistryName, False)",
    "RunWhileClaudeDesktopClosed(ExpandConstant('{app}\integrations\disconnect-claude-desktop.ps1'), '', True)",
    'mbConfirmation, MB_RETRYCANCEL) <> IDRETRY;'
)) {
    Assert-TextContains -Text $installerSource -Needle $closedFragment -Description "Claude Desktop closed guard: $closedFragment"
}

Write-Host '[contract] uninstall can remove every NEXUS entry, written or adopted'
foreach ($uninstallFragment in @(
    'procedure WriteDisconnectScripts;',
    'procedure AdoptConfiguredClients;',
    "SaveStringToFile(Dir + '\disconnect-copilot-cli.ps1',",
    "ExpandConstant('{app}\integrations\disconnect-copilot-cli.ps1')"
)) {
    Assert-TextContains -Text $installerSource -Needle $uninstallFragment -Description "client uninstall: $uninstallFragment"
}

Write-Host '[contract] Docker data live in a named volume and the Ollama URL is resolved by NEXUS'
Assert-TextContains -Text $installerSource -Needle "'NEXUS_HOME_BIND=nexus-data' + #13#10 +" -Description 'Docker NEXUS_HOME uses the named volume, not a Windows bind mount'
Assert-TextContains -Text $installerSource -Needle "'NEXUS_OLLAMA_BASE_URL=' + OllamaPage.Values[0] + #13#10 +" -Description 'Docker receives the Ollama URL as entered; NEXUS adapts loopback after its policy check'
$composeTemplate = [IO.File]::ReadAllText((Join-Path $repo 'packaging\docker\docker-compose.yml.template'), [Text.Encoding]::UTF8)
Assert-TextContains -Text $composeTemplate -Needle 'name: "${NEXUS_DOCKER_DATA_VOLUME:-nexus-context-engine-data}"' -Description 'Compose declares the nexus-data named volume'

Write-Host '[contract] the image built on the workstation carries its version label'
Assert-TextContains -Text $composeTemplate -Needle 'NEXUS_IMAGE_VERSION: "${NEXUS_IMAGE_VERSION:-}"' -Description 'Compose passes the image version build argument'
Assert-TextContains -Text $composeTemplate -Needle 'NEXUS_IMAGE_REVISION: "${NEXUS_IMAGE_REVISION:-}"' -Description 'Compose passes the image revision build argument'
Assert-TextContains -Text $installerSource -Needle "EnvContent := EnvContent + 'NEXUS_IMAGE_VERSION={#NexusVersion}' + #13#10;" -Description 'installer writes the image version to .env'
Assert-TextContains -Text $installerSource -Needle "'--build-arg NEXUS_IMAGE_VERSION={#NexusVersion} '" -Description 'local image fallback passes the image version'
Assert-TextContains -Text $builderSource -Needle "`$iss.Replace('@@REVISION@@', `$revision)" -Description 'packaging resolves the revision token'

Write-Host '[contract] reinstalling keeps the managed container port and moves the official image to this version'
Assert-TextContains -Text $installerSource -Needle 'ManagedContainerPublishesPort(DockerPage.Values[1], DockerPage.Values[2], PortValue)' -Description 'managed NEXUS container port is not relocated on reinstall'
Assert-TextContains -Text $installerSource -Needle '(not IsOfficialNexusImage(Value)) then DockerPage.Values[0] := Value;' -Description 'saved official image does not override the image of the installed version'
Assert-TextContains -Text $installerSource -Needle "DockerPage.Values[0] := 'ghcr.io/fturleque/nexus-context-engine:{#NexusVersion}';" -Description 'default image follows the installer version'

Write-Host '[contract] post-install steps report their state on their own progress row'
foreach ($progressFragment in @(
    'procedure BuildSetupStepRows;',
    'Row.Bar.Style := npbstMarquee',
    'Row.Bar.State := npbsError;',
    'Row.Bar.State := npbsPaused;',
    "SetSetupStepState('config', 'running', '');",
    "SetSetupStepState(ClientSection(Index), 'failed', 'client introuvable')"
)) {
    Assert-TextContains -Text $installerSource -Needle $progressFragment -Description "post-install progress row: $progressFragment"
}
# Native CLI connectors launch nexus-mcp.cmd, which loads config\nexus-native.env.cmd
# and passes --enable-native-access=ALL-UNNAMED to the embedded Java runtime.
Assert-TextContains -Text $installerSource -Needle "PowerShellSingleQuote(ExpandConstant('{app}\nexus-mcp.cmd'))" -Description 'native CLI connectors use the hardened nexus-mcp.cmd launcher'
$detector = Join-Path $repo 'packaging\windows\detect-assistant-clients.ps1'
$detectorBytes = [IO.File]::ReadAllBytes($detector)
if (@($detectorBytes | Where-Object { $_ -gt 127 }).Count -gt 0) {
    throw 'detect-assistant-clients.ps1 must stay ASCII-only: Windows PowerShell 5.1 parses BOM-less scripts with the ANSI code page.'
}
$probeHome = Join-Path ([IO.Path]::GetTempPath()) ('nexus-client-probe-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $probeHome | Out-Null
$savedProfile = @{ USERPROFILE = $env:USERPROFILE; APPDATA = $env:APPDATA; LOCALAPPDATA = $env:LOCALAPPDATA; PATH = $env:PATH }
try {
    # An empty profile and a PATH without client CLIs: every client must be reported absent.
    $env:USERPROFILE = $probeHome; $env:APPDATA = Join-Path $probeHome 'Roaming'; $env:LOCALAPPDATA = Join-Path $probeHome 'Local'
    $env:PATH = Join-Path $env:SystemRoot 'System32'
    $probeIni = Join-Path $probeHome 'preflight.ini'
    & (Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe') -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $detector -OutputPath $probeIni | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Client detector failed with exit code $LASTEXITCODE" }
    $probeText = [IO.File]::ReadAllText($probeIni, [Text.Encoding]::Unicode)
    foreach ($section in @('CopilotCli', 'CopilotJetBrains', 'ClaudeCode', 'ClaudeDesktop', 'CodexCli', 'CodexDesktop')) {
        if ($probeText -notmatch "(?ms)^\[$section\]\s*Available=0") { throw "Client detector reports $section on an empty profile." }
    }

    # Existing nexus entries: one launches NEXUS (adopted), one launches another tool (left alone).
    New-Item -ItemType Directory -Force -Path (Join-Path $probeHome 'Roaming\Claude'), (Join-Path $probeHome 'Local\github-copilot\intellij') | Out-Null
    Set-Content -LiteralPath (Join-Path $probeHome 'Roaming\Claude\claude_desktop_config.json') -Encoding ascii `
        -Value '{"mcpServers":{"nexus":{"command":"cmd.exe","args":["/d","/s","/c","C:\\NEXUS\\nexus-mcp.cmd"]}}}'
    Set-Content -LiteralPath (Join-Path $probeHome 'Local\github-copilot\intellij\mcp.json') -Encoding ascii `
        -Value '{"servers":{"nexus":{"type":"stdio","command":"other-tool","args":[]}}}'
    & (Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe') -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $detector -OutputPath $probeIni | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Client detector failed with exit code $LASTEXITCODE" }
    $probeText = [IO.File]::ReadAllText($probeIni, [Text.Encoding]::Unicode)
    if ($probeText -notmatch '(?ms)^\[ClaudeDesktop\]\s*Available=1\s*Configured=1\s*Owned=1') {
        throw 'Client detector does not adopt a nexus entry that launches nexus-mcp.cmd.'
    }
    if ($probeText -notmatch '(?ms)^\[CopilotJetBrains\]\s*Available=1\s*Configured=1\s*Owned=0') {
        throw 'Client detector adopts a nexus entry that launches another tool.'
    }

    # A leftover Microsoft Store folder without nexus beside the standard one: still to configure.
    New-Item -ItemType Directory -Force -Path (Join-Path $probeHome 'Local\Packages\Claude_probe\LocalCache\Roaming\Claude') | Out-Null
    & (Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe') -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $detector -OutputPath $probeIni | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Client detector failed with exit code $LASTEXITCODE" }
    $probeText = [IO.File]::ReadAllText($probeIni, [Text.Encoding]::Unicode)
    if ($probeText -notmatch '(?ms)^\[ClaudeDesktop\]\s*Available=1\s*Configured=0') {
        throw 'Client detector reports Claude Desktop configured while one of its configuration folders lacks nexus.'
    }
}
finally {
    foreach ($name in $savedProfile.Keys) { [Environment]::SetEnvironmentVariable($name, $savedProfile[$name], 'Process') }
    Remove-Item -LiteralPath $probeHome -Recurse -Force -ErrorAction SilentlyContinue
}
foreach ($rawDepth in @('ConvertTo-Json -Depth 5', 'ConvertTo-Json -Depth 10')) {
    if ($installerSource.IndexOf($rawDepth, [StringComparison]::Ordinal) -ge 0) {
        throw "Installer must not write MCP config JSON via raw '$rawDepth' (mangles indentation); use Save-CleanJson."
    }
}

Write-Host '[contract] client-only docker.exe must not suppress Docker Desktop bootstrap'
Assert-TextContains -Text $installerSource -Needle 'docker info >nul 2>nul' -Description 'real Docker engine reachability probe'
Assert-TextContains -Text $installerSource -Needle 'Result := DockerEngineReady() or (DockerDesktopExecutable() <> '''');' -Description 'Docker Desktop or working engine prerequisite semantics'

Write-Host '[contract] recommended native install keeps REST optional; every portable launcher remains hardened'
Assert-TextContains -Text $installerSource -Needle 'NativeOptionsPage.Values[2] := False;' -Description 'recommended native profile keeps REST optional'
Assert-TextContains -Text $installerSource -Needle 'Source: "{#NexusSourceDir}\nexus-rest.cmd"; DestDir: "{app}"; Flags: ignoreversion; Check: InstallNativeRest' -Description 'optional REST launcher remains installer-gated'
$distribution = Join-Path $repo "target\dist\nexus-context-engine-$Version-windows-x64"
foreach ($launcherName in @('nexus.cmd','nexus-mcp.cmd','nexus-rest.cmd','nexus-assistant-clients.cmd','nexus-docker.cmd','nexus-docker-mcp.cmd')) {
    $launcherPath = Join-Path $distribution $launcherName
    if (-not (Test-Path -LiteralPath $launcherPath -PathType Leaf)) {
        throw "Built NEXUS distribution is missing $launcherName"
    }
    $launcherSource = Get-Content -Raw -LiteralPath $launcherPath
    Assert-TextContains -Text $launcherSource -Needle 'setlocal DisableDelayedExpansion' -Description "$launcherName disables delayed expansion"
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

Write-Host '[smoke] generated client scripts read and write UTF-8, never rewrite an unreadable file, back up before rewriting'
# The smoke installer writes nexus-json.ps1 and the disconnect scripts (connect scripts need a
# detected client). The connect scripts call Add-NexusServerEntry exactly as the probe below does;
# every script runs under Windows PowerShell 5.1, the interpreter the installer uses.
$integrations = Join-Path $install 'integrations'
$jsonHelper = Join-Path $integrations 'nexus-json.ps1'
foreach ($generated in @('nexus-json.ps1', 'disconnect-claude-desktop.ps1', 'disconnect-copilot-jetbrains.ps1')) {
    $generatedPath = Join-Path $integrations $generated
    if (-not (Test-Path -LiteralPath $generatedPath -PathType Leaf)) { throw "Installed NEXUS is missing integrations\$generated" }
    if (@([IO.File]::ReadAllBytes($generatedPath) | Where-Object { $_ -gt 127 }).Count -gt 0) {
        throw "integrations\$generated must stay ASCII: Windows PowerShell 5.1 parses BOM-less scripts with the ANSI code page."
    }
}
$ps51 = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$clientHome = Join-Path $root 'client-profile'
$probeScript = Join-Path $root 'client-probe.ps1'
[IO.File]::WriteAllText($probeScript, @'
param([string]$Helper, [string]$Paths, [string]$Section, [string]$Mode)
$ErrorActionPreference = 'Stop'
. $Helper
$list = @($Paths -split '\|')
if ($Mode -eq 'remove') { Remove-NexusServerEntry $list $Section | Out-Null; exit 0 }
$e = [PSCustomObject]@{ command = 'cmd.exe'; args = @('/d', '/s', '/c', 'C:\NEXUS\nexus-mcp.cmd') }
$added = Add-NexusServerEntry $list $Section $e
if ($added -eq 0) { exit 20 }
exit 0
'@, [Text.Encoding]::ASCII)

# A failing script writes its exception on stderr: under Windows PowerShell 5.1, redirecting it
# with ErrorActionPreference Stop would throw here instead of returning the exit code.
function Invoke-ClientProbe([string[]]$Paths, [string]$Section, [string]$Mode = 'add') {
    $ErrorActionPreference = 'Continue'
    & $ps51 -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $probeScript `
        -Helper $jsonHelper -Paths ($Paths -join '|') -Section $Section -Mode $Mode *> $null
    return $LASTEXITCODE
}
function Invoke-InstalledScript([string]$Name) {
    $ErrorActionPreference = 'Continue'
    & $ps51 -NoProfile -NonInteractive -ExecutionPolicy Bypass -File (Join-Path $integrations $Name) *> $null
    return $LASTEXITCODE
}
function Write-ClientFile([string]$Path, [string]$Content) {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Path) | Out-Null
    [IO.File]::WriteAllText($Path, $Content, $utf8NoBom)
}
function Read-ClientFile([string]$Path) { return ([IO.File]::ReadAllText($Path, $utf8NoBom) | ConvertFrom-Json) }
function Assert-BytesUnchanged([string]$Path, [byte[]]$Expected, [string]$Description) {
    $actual = [IO.File]::ReadAllBytes($Path)
    if ([Convert]::ToBase64String($actual) -ne [Convert]::ToBase64String($Expected)) { throw "Client configuration rewritten: $Description" }
}
function Get-NexusSideFiles([string]$Path) {
    return @(Get-ChildItem -LiteralPath (Split-Path -Parent $Path) -File |
        Where-Object { $_.Name -like ((Split-Path -Leaf $Path) + '.nexus-*') })
}

$savedClientProfile = @{ APPDATA = $env:APPDATA; LOCALAPPDATA = $env:LOCALAPPDATA }
Remove-Item -LiteralPath $clientHome -Recurse -Force -ErrorAction SilentlyContinue
try {
    $env:APPDATA = Join-Path $clientHome 'Roaming'
    $env:LOCALAPPDATA = Join-Path $clientHome 'Local'
    $accented = 'C:\Users\H' + [char]0x00E9 + 'l' + [char]0x00E8 + 'ne\outil.exe'
    $outilJson = '{"mcpServers":{"outil":{"command":"' + $accented.Replace('\', '\\') + '","args":[]}}}'
    $claudeCfg = Join-Path $env:APPDATA 'Claude\claude_desktop_config.json'

    # UTF-8 without BOM, accented path in another entry: connect, then uninstall.
    Write-ClientFile $claudeCfg $outilJson
    $original = [IO.File]::ReadAllBytes($claudeCfg)
    if ((Invoke-ClientProbe @($claudeCfg) 'mcpServers') -ne 0) { throw 'Client connection failed on a valid UTF-8 configuration.' }
    $connected = Read-ClientFile $claudeCfg
    if ($connected.mcpServers.outil.command -cne $accented) { throw "Connection altered another entry: $($connected.mcpServers.outil.command)" }
    if ($null -eq $connected.mcpServers.PSObject.Properties['nexus']) { throw 'Connection did not add the nexus entry.' }
    $backups = @(Get-NexusSideFiles $claudeCfg)
    if ($backups.Count -ne 1 -or $backups[0].Name -notmatch '\.nexus-backup-\d{14}(-\d+)?$') { throw 'Connection did not keep exactly one dated backup.' }
    Assert-BytesUnchanged $backups[0].FullName $original 'the backup must hold the original bytes'
    if ((Invoke-ClientProbe @($claudeCfg) 'mcpServers') -ne 20) { throw 'A second connection must exit 20 without rewriting.' }
    if (@(Get-NexusSideFiles $claudeCfg).Count -ne 1) { throw 'An idempotent connection created another backup or a temporary file.' }
    if ((Invoke-InstalledScript 'disconnect-claude-desktop.ps1') -ne 0) { throw 'disconnect-claude-desktop.ps1 failed on a valid configuration.' }
    $disconnected = Read-ClientFile $claudeCfg
    if ($null -ne $disconnected.mcpServers.PSObject.Properties['nexus']) { throw 'Uninstall did not remove the nexus entry.' }
    if ($disconnected.mcpServers.outil.command -cne $accented) { throw "Uninstall altered another entry: $($disconnected.mcpServers.outil.command)" }

    # Invalid JSON: non-zero exit, original bytes, no backup or temporary file, on connect and uninstall.
    Remove-Item -LiteralPath $clientHome -Recurse -Force
    Write-ClientFile $claudeCfg '{"mcpServers": {'
    $invalid = [IO.File]::ReadAllBytes($claudeCfg)
    if ((Invoke-ClientProbe @($claudeCfg) 'mcpServers') -eq 0) { throw 'Connection must fail on invalid JSON.' }
    Assert-BytesUnchanged $claudeCfg $invalid 'invalid JSON on connect'
    if ((Invoke-InstalledScript 'disconnect-claude-desktop.ps1') -eq 0) { throw 'Uninstall must report invalid JSON.' }
    Assert-BytesUnchanged $claudeCfg $invalid 'invalid JSON on uninstall'
    if (@(Get-NexusSideFiles $claudeCfg).Count -ne 0) { throw 'A failed read left a backup or a temporary file.' }

    # Two Claude Desktop configurations: nothing crosses from one file to the other.
    $storeCfg = Join-Path $env:LOCALAPPDATA 'Packages\Claude_probe\LocalCache\Roaming\Claude\claude_desktop_config.json'
    Write-ClientFile $storeCfg '{"mcpServers": {'
    Write-ClientFile $claudeCfg '{"mcpServers":{"autre":{"command":"autre.exe","args":[]}}}'
    $storeBytes = [IO.File]::ReadAllBytes($storeCfg)
    $claudeBytes = [IO.File]::ReadAllBytes($claudeCfg)
    if ((Invoke-ClientProbe @($storeCfg, $claudeCfg) 'mcpServers') -eq 0) { throw 'Connection must fail when the first configuration is invalid.' }
    Assert-BytesUnchanged $storeCfg $storeBytes 'first configuration invalid'
    Assert-BytesUnchanged $claudeCfg $claudeBytes 'configuration following an invalid one'
    Write-ClientFile $storeCfg $outilJson
    Write-ClientFile $claudeCfg '{"mcpServers": {'
    $claudeBytes = [IO.File]::ReadAllBytes($claudeCfg)
    if ((Invoke-ClientProbe @($storeCfg, $claudeCfg) 'mcpServers') -eq 0) { throw 'Connection must fail when the second configuration is invalid.' }
    Assert-BytesUnchanged $claudeCfg $claudeBytes 'second configuration invalid after a valid first one'
    if ($null -eq (Read-ClientFile $storeCfg).mcpServers.PSObject.Properties['nexus']) { throw 'The valid first configuration did not receive its nexus entry.' }

    # Copilot JetBrains: backup beside mcp.json, valid final JSON, removal by the uninstall script.
    $jetbrainsCfg = Join-Path $env:LOCALAPPDATA 'github-copilot\intellij\mcp.json'
    Write-ClientFile $jetbrainsCfg '{"servers":{"outil":{"type":"stdio","command":"outil.exe","args":[]}}}'
    $jetbrainsBytes = [IO.File]::ReadAllBytes($jetbrainsCfg)
    if ((Invoke-ClientProbe @($jetbrainsCfg) 'servers') -ne 0) { throw 'Copilot JetBrains connection failed.' }
    $jetbrainsBackups = @(Get-NexusSideFiles $jetbrainsCfg | Where-Object { $_.Name -match '\.nexus-backup-\d{14}(-\d+)?$' })
    if ($jetbrainsBackups.Count -ne 1) { throw 'Copilot JetBrains connection did not keep a backup of mcp.json.' }
    Assert-BytesUnchanged $jetbrainsBackups[0].FullName $jetbrainsBytes 'mcp.json backup'
    if ($null -eq (Read-ClientFile $jetbrainsCfg).servers.PSObject.Properties['nexus']) { throw 'mcp.json did not receive the nexus entry.' }
    if ((Invoke-InstalledScript 'disconnect-copilot-jetbrains.ps1') -ne 0) { throw 'disconnect-copilot-jetbrains.ps1 failed.' }
    $jetbrainsFinal = Read-ClientFile $jetbrainsCfg
    if ($null -ne $jetbrainsFinal.servers.PSObject.Properties['nexus'] -or $jetbrainsFinal.servers.outil.command -cne 'outil.exe') {
        throw 'Copilot JetBrains uninstall did not restore the other entries only.'
    }

    # A failed replacement (file held open without delete sharing) leaves the original in place.
    Write-ClientFile $jetbrainsCfg '{"servers":{}}'
    $lockedBytes = [IO.File]::ReadAllBytes($jetbrainsCfg)
    $lock = [IO.File]::Open($jetbrainsCfg, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
    try { $lockedExit = Invoke-ClientProbe @($jetbrainsCfg) 'servers' } finally { $lock.Dispose() }
    if ($lockedExit -eq 0) { throw 'Connection must fail when the configuration cannot be replaced.' }
    Assert-BytesUnchanged $jetbrainsCfg $lockedBytes 'failed replacement'
    if (@(Get-NexusSideFiles $jetbrainsCfg | Where-Object { $_.Name -like '*.tmp' }).Count -ne 0) { throw 'A failed replacement left its temporary file.' }
}
finally {
    foreach ($name in $savedClientProfile.Keys) { [Environment]::SetEnvironmentVariable($name, $savedClientProfile[$name], 'Process') }
    Remove-Item -LiteralPath $clientHome -Recurse -Force -ErrorAction SilentlyContinue
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

Write-Host 'NEXUS Windows installer hardened source + pinned prerequisite provenance + install/CLI/MCP/Claude/Codex payload/uninstall smoke PASS' -ForegroundColor Green
Remove-Item -LiteralPath $root -Recurse -Force -ErrorAction SilentlyContinue
