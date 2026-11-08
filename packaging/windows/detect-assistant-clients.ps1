[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $OutputPath,

    [ValidateRange(1, 30)]
    [int] $ProbeTimeoutSeconds = 5
)

# Assistant client preflight for the NEXUS Windows installer. Runs before the
# "Clients IA" wizard page and writes one INI section per client:
#   Available=1|0    the client is installed and can be connected
#   Configured=1|0   the client already declares an MCP server named "nexus"
#   Owned=1|0        that "nexus" entry launches NEXUS (nexus-mcp.cmd or nexus-mcp.jar)
#   Reason=...       status line shown under the checkbox
# Read-only: it never writes any client configuration.

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

# Windows PowerShell 5.1 parses BOM-less files with the ANSI code page: this
# source stays ASCII-only and builds the accented UI characters at runtime.
# The INI is written as UTF-16 LE with BOM, which Inno Setup reads natively.
function Ui([string] $Value) {
    return $Value.Replace('{e}', [string][char]0x00E9).Replace('{dash}', [string][char]0x2014)
}

# Environment variable first, special folder second: a test harness can point
# the detection at an isolated profile by overriding the variable.
function Get-UserFolderPath([string] $EnvironmentVariableName, [string] $SpecialFolder) {
    $Value = [Environment]::GetEnvironmentVariable($EnvironmentVariableName)
    if (-not [string]::IsNullOrWhiteSpace($Value)) { return $Value }
    return [Environment]::GetFolderPath($SpecialFolder)
}

function Resolve-CommandPath([string] $Name) {
    $Command = Get-Command $Name -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -eq $Command) { return '' }
    if ($Command.CommandType -notin @('Application', 'ExternalScript')) { return '' }
    return [string]$Command.Source
}

function Test-VsCodeCopilotShim([string] $Path) {
    return $Path -match '(?i)(Microsoft VS Code|\\Code\\bin\\|\\.vscode\\|vscode)'
}

# Claude Code Desktop ships its own claude.exe under
# %APPDATA%\Claude\claude-code\<version>\claude.exe, never on PATH.
function Find-EmbeddedClaudeCli {
    $ClaudeCodeDir = Join-Path (Get-UserFolderPath 'APPDATA' 'ApplicationData') 'Claude\claude-code'
    if (-not (Test-Path -LiteralPath $ClaudeCodeDir -PathType Container)) { return '' }
    foreach ($Dir in @(Get-ChildItem -LiteralPath $ClaudeCodeDir -Directory -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending)) {
        $Exe = Join-Path $Dir.FullName 'claude.exe'
        if (Test-Path -LiteralPath $Exe -PathType Leaf) { return $Exe }
    }
    return ''
}

# npm CLIs are often .ps1 launchers that need PowerShell 7; executables run
# under Windows PowerShell.
function Resolve-ProbeHost([string] $ToolPath) {
    if ([System.IO.Path]::GetExtension($ToolPath) -ieq '.ps1') {
        $Pwsh = Get-Command pwsh.exe -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($null -eq $Pwsh) { return '' }
        return [string]$Pwsh.Source
    }
    $WindowsPowerShell = Join-Path ([Environment]::GetFolderPath('System')) 'WindowsPowerShell\v1.0\powershell.exe'
    if (Test-Path -LiteralPath $WindowsPowerShell -PathType Leaf) { return $WindowsPowerShell }
    return ''
}

# Runs "<tool> <arguments>" in a child PowerShell with a hard timeout, so a CLI
# waiting for input or a login prompt can never hang the wizard.
function Invoke-Probe([string] $File, [string[]] $Arguments) {
    $HostPath = Resolve-ProbeHost $File
    if ([string]::IsNullOrWhiteSpace($HostPath)) {
        return [pscustomobject]@{ ExitCode = -2; Output = '' }
    }
    $Payload = [pscustomobject][ordered]@{ file = $File; arguments = @($Arguments) } |
        ConvertTo-Json -Depth 4 -Compress
    $ChildScript = @'
$ErrorActionPreference = 'Continue'
try {
    $request = [System.Text.Encoding]::UTF8.GetString(
        [Convert]::FromBase64String($env:NEXUS_CLIENT_PROBE)) | ConvertFrom-Json
    $invokeArgs = @($request.arguments | ForEach-Object { [string]$_ })
    & ([string]$request.file) @invokeArgs
    $code = $LASTEXITCODE
    if ($null -eq $code) { $code = if ($?) { 0 } else { 1 } }
    exit ([int]$code)
}
catch {
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 1
}
'@
    $Info = New-Object System.Diagnostics.ProcessStartInfo
    $Info.FileName = $HostPath
    $Info.Arguments = '-NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand ' +
        [Convert]::ToBase64String([System.Text.Encoding]::Unicode.GetBytes($ChildScript))
    $Info.UseShellExecute = $false
    $Info.CreateNoWindow = $true
    $Info.RedirectStandardInput = $true
    $Info.RedirectStandardOutput = $true
    $Info.RedirectStandardError = $true
    $Info.EnvironmentVariables['NEXUS_CLIENT_PROBE'] =
        [Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes($Payload))

    $Process = New-Object System.Diagnostics.Process
    $Process.StartInfo = $Info
    try {
        if (-not $Process.Start()) { return [pscustomobject]@{ ExitCode = -1; Output = '' } }
        $Process.StandardInput.Close()
        $StdOut = $Process.StandardOutput.ReadToEndAsync()
        $StdErr = $Process.StandardError.ReadToEndAsync()
        if (-not $Process.WaitForExit($ProbeTimeoutSeconds * 1000)) {
            try { & (Join-Path ([Environment]::SystemDirectory) 'taskkill.exe') /PID ([string]$Process.Id) /T /F 2>&1 | Out-Null } catch { }
            return [pscustomobject]@{ ExitCode = -3; Output = '' }
        }
        $Process.WaitForExit()
        return [pscustomobject]@{ ExitCode = $Process.ExitCode; Output = ($StdOut.Result + "`n" + $StdErr.Result) }
    }
    finally {
        $Process.Dispose()
    }
}

function Read-JsonFile([string] $Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $null }
    try { return Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json }
    catch { return $null }
}

function Test-JsonServer([object] $Config, [string] $ServersKey) {
    if ($null -eq $Config) { return $false }
    $Servers = $Config.PSObject.Properties[$ServersKey]
    if ($null -eq $Servers -or $null -eq $Servers.Value) { return $false }
    return $null -ne $Servers.Value.PSObject.Properties['nexus']
}

function Test-LaunchesNexus([string] $Text) {
    return $Text -match '(?i)nexus-mcp\.(cmd|jar)'
}

function Get-JsonServerText([object] $Config, [string] $ServersKey) {
    if (-not (Test-JsonServer $Config $ServersKey)) { return '' }
    return ($Config.PSObject.Properties[$ServersKey].Value.PSObject.Properties['nexus'].Value | ConvertTo-Json -Depth 10 -Compress)
}

function Get-CodexConfigText {
    $Config = Join-Path (Get-UserFolderPath 'USERPROFILE' 'UserProfile') '.codex\config.toml'
    if (-not (Test-Path -LiteralPath $Config -PathType Leaf)) { return '' }
    return [System.IO.File]::ReadAllText($Config)
}

# Text of the [mcp_servers.nexus] table, up to the next table header.
function Get-CodexNexusSection([string] $Text) {
    $Match = [regex]::Match($Text, '(?ms)^\s*\[mcp_servers\.nexus\][^\[]*')
    if ($Match.Success) { return $Match.Value }
    return ''
}

# Text between the markers connect-codex-desktop.ps1 writes around its block.
function Get-CodexMarkedBlock([string] $Text) {
    $Match = [regex]::Match($Text, '(?s)# BEGIN NEXUS-MCP.*?# END NEXUS-MCP')
    if ($Match.Success) { return $Match.Value }
    return ''
}

function Test-JetBrainsCopilot {
    $LocalAppData = Get-UserFolderPath 'LOCALAPPDATA' 'LocalApplicationData'
    $Roaming = Get-UserFolderPath 'APPDATA' 'ApplicationData'
    if (Test-Path -LiteralPath (Join-Path $LocalAppData 'github-copilot\intellij')) { return $true }
    foreach ($Root in @((Join-Path $Roaming 'JetBrains'), (Join-Path $LocalAppData 'JetBrains'))) {
        if (-not (Test-Path -LiteralPath $Root -PathType Container)) { continue }
        foreach ($Product in @(Get-ChildItem -LiteralPath $Root -Directory -ErrorAction SilentlyContinue)) {
            $Plugins = Join-Path $Product.FullName 'plugins'
            if (-not (Test-Path -LiteralPath $Plugins -PathType Container)) { continue }
            if (Get-ChildItem -LiteralPath $Plugins -Directory -ErrorAction SilentlyContinue |
                    Where-Object { $_.Name -match '(?i)copilot' } | Select-Object -First 1) { return $true }
        }
    }
    return $false
}

# Every Claude Desktop configuration file: one per Microsoft Store (MSIX) package
# folder, plus %APPDATA%\Claude for the standard installer. A machine can keep
# both, only one being the running app's, so the connector writes to all of them.
function Find-ClaudeDesktopConfigs {
    $Configs = @()
    $Local = Get-UserFolderPath 'LOCALAPPDATA' 'LocalApplicationData'
    $Packages = Join-Path $Local 'Packages'
    if (Test-Path -LiteralPath $Packages -PathType Container) {
        foreach ($Dir in @(Get-ChildItem -LiteralPath $Packages -Directory -Filter 'Claude_*' -ErrorAction SilentlyContinue)) {
            $Candidate = Join-Path $Dir.FullName 'LocalCache\Roaming\Claude'
            if (Test-Path -LiteralPath $Candidate -PathType Container) {
                $Configs += (Join-Path $Candidate 'claude_desktop_config.json')
            }
        }
    }
    $Roaming = Join-Path (Get-UserFolderPath 'APPDATA' 'ApplicationData') 'Claude'
    if (Test-Path -LiteralPath $Roaming -PathType Container) {
        $Configs += (Join-Path $Roaming 'claude_desktop_config.json')
    }
    return , $Configs
}

function Test-CodexDesktop {
    $Local = Get-UserFolderPath 'LOCALAPPDATA' 'LocalApplicationData'
    $HomeDir = Get-UserFolderPath 'USERPROFILE' 'UserProfile'
    return (Test-Path -LiteralPath (Join-Path $HomeDir '.codex\config.toml') -PathType Leaf) -or
        (Test-Path -LiteralPath (Join-Path $Local 'Programs\Codex\Codex.exe') -PathType Leaf) -or
        (Test-Path -LiteralPath (Join-Path $Local 'Codex\Codex.exe') -PathType Leaf)
}

function Test-CodexConfigured {
    $Config = Join-Path (Get-UserFolderPath 'USERPROFILE' 'UserProfile') '.codex\config.toml'
    if (-not (Test-Path -LiteralPath $Config -PathType Leaf)) { return $false }
    return [System.IO.File]::ReadAllText($Config) -match '(?m)^\s*\[mcp_servers\.nexus\]'
}

function New-Result([bool] $Available, [bool] $Configured, [string] $Reason, [bool] $Owned = $false) {
    return [pscustomobject]@{ Available = $Available; Configured = $Configured; Owned = ($Configured -and $Owned); Reason = $Reason }
}

# Returns the CLI result; Owned is filled from the probe output, or by the caller
# when the CLI does not print the entry command.
function Get-CliResult([string] $Name, [string] $Label, [string] $Path, [string[]] $ConfiguredProbe) {
    if ([string]::IsNullOrWhiteSpace($Path)) {
        return New-Result $false $false (Ui "Non disponible {dash} $Label introuvable")
    }
    $Help = Invoke-Probe $Path @('mcp', '--help')
    if ($Help.ExitCode -eq -2) {
        return New-Result $false $false (Ui "Non disponible {dash} PowerShell 7 requis pour lancer $Label")
    }
    if ($Help.ExitCode -ne 0) {
        return New-Result $false $false (Ui "Non disponible {dash} $Label d{e}tect{e} sans commande MCP compatible")
    }
    $Probe = Invoke-Probe $Path $ConfiguredProbe
    $Configured = if ($Name -eq 'copilot') {
        $Probe.ExitCode -eq 0 -and $Probe.Output -match '(?m)^\s*nexus\b'
    } else {
        $Probe.ExitCode -eq 0
    }
    return New-Result $true $Configured (Ui 'D{e}tect{e}') (Test-LaunchesNexus $Probe.Output)
}

$Results = [ordered]@{}

$Copilot = Resolve-CommandPath 'copilot'
if (-not [string]::IsNullOrWhiteSpace($Copilot) -and (Test-VsCodeCopilotShim $Copilot)) {
    $Results['CopilotCli'] = New-Result $false $false (Ui 'Non disponible {dash} lanceur VS Code d{e}tect{e}, CLI Copilot absente')
} else {
    $Results['CopilotCli'] = Get-CliResult 'copilot' 'GitHub Copilot CLI' $Copilot @('mcp', 'list')
    # `copilot mcp list` prints names only: the entry command is in its config file.
    $CopilotConfig = Read-JsonFile (Join-Path (Get-UserFolderPath 'USERPROFILE' 'UserProfile') '.copilot\mcp-config.json')
    $Results['CopilotCli'].Owned = $Results['CopilotCli'].Configured -and
        (Test-LaunchesNexus (Get-JsonServerText $CopilotConfig 'mcpServers'))
}

if (Test-JetBrainsCopilot) {
    $Mcp = Read-JsonFile (Join-Path (Get-UserFolderPath 'LOCALAPPDATA' 'LocalApplicationData') 'github-copilot\intellij\mcp.json')
    $Results['CopilotJetBrains'] = New-Result $true (Test-JsonServer $Mcp 'servers') (Ui 'D{e}tect{e}') `
        (Test-LaunchesNexus (Get-JsonServerText $Mcp 'servers'))
} else {
    $Results['CopilotJetBrains'] = New-Result $false $false (Ui 'Non disponible {dash} plugin GitHub Copilot JetBrains non d{e}tect{e}')
}

$Claude = Resolve-CommandPath 'claude'
if ([string]::IsNullOrWhiteSpace($Claude)) { $Claude = Find-EmbeddedClaudeCli }
$Results['ClaudeCode'] = Get-CliResult 'claude' 'Claude Code' $Claude @('mcp', 'get', 'nexus')

# Configured only when every configuration declares nexus: the connector then has
# nothing left to write. Owned when one of those entries launches NEXUS.
$ClaudeDesktopConfigs = Find-ClaudeDesktopConfigs
if ($ClaudeDesktopConfigs.Count -gt 0) {
    $DesktopConfigured = $true
    $DesktopOwned = $false
    foreach ($ConfigPath in $ClaudeDesktopConfigs) {
        $DesktopConfig = Read-JsonFile $ConfigPath
        if (-not (Test-JsonServer $DesktopConfig 'mcpServers')) { $DesktopConfigured = $false }
        if (Test-LaunchesNexus (Get-JsonServerText $DesktopConfig 'mcpServers')) { $DesktopOwned = $true }
    }
    $Results['ClaudeDesktop'] = New-Result $true $DesktopConfigured (Ui 'D{e}tect{e}') $DesktopOwned
} else {
    $Results['ClaudeDesktop'] = New-Result $false $false (Ui 'Non disponible {dash} Claude Desktop non d{e}tect{e}')
}

# Codex CLI and Codex Desktop share ~/.codex/config.toml. An entry inside the
# BEGIN/END NEXUS-MCP markers belongs to the Desktop connector (removed by
# deleting the block); any other nexus table belongs to `codex mcp`.
$CodexText = Get-CodexConfigText
$CodexBlock = Get-CodexMarkedBlock $CodexText
$Codex = Resolve-CommandPath 'codex'
$Results['CodexCli'] = Get-CliResult 'codex' 'Codex CLI' $Codex @('mcp', 'get', 'nexus')
$Results['CodexCli'].Owned = $Results['CodexCli'].Configured -and ($CodexBlock -eq '') -and
    (Test-LaunchesNexus (Get-CodexNexusSection $CodexText))

if (Test-CodexDesktop) {
    $Results['CodexDesktop'] = New-Result $true (Test-CodexConfigured) (Ui 'D{e}tect{e}') (Test-LaunchesNexus $CodexBlock)
} else {
    $Results['CodexDesktop'] = New-Result $false $false (Ui 'Non disponible {dash} Codex Desktop non d{e}tect{e}')
}

$Parent = Split-Path -Parent ([System.IO.Path]::GetFullPath($OutputPath))
if (-not [string]::IsNullOrWhiteSpace($Parent)) {
    New-Item -ItemType Directory -Force -Path $Parent | Out-Null
}
$Lines = New-Object System.Collections.Generic.List[string]
foreach ($Name in $Results.Keys) {
    $Value = $Results[$Name]
    $Lines.Add("[$Name]")
    $Lines.Add('Available=' + $(if ($Value.Available) { '1' } else { '0' }))
    $Lines.Add('Configured=' + $(if ($Value.Configured) { '1' } else { '0' }))
    $Lines.Add('Owned=' + $(if ($Value.Owned) { '1' } else { '0' }))
    $Lines.Add('Reason=' + ([string]$Value.Reason).Replace("`r", ' ').Replace("`n", ' '))
    $Lines.Add('')
}
[System.IO.File]::WriteAllLines([System.IO.Path]::GetFullPath($OutputPath), $Lines, [System.Text.Encoding]::Unicode)

foreach ($Name in $Results.Keys) {
    $Value = $Results[$Name]
    Write-Host ('{0}: available={1} configured={2} owned={3} reason={4}' -f $Name, $Value.Available, $Value.Configured, $Value.Owned, $Value.Reason)
}
