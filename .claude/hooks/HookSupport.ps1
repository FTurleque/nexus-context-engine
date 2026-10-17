# Fonctions partagées par les hooks Claude Code du projet NEXUS.
# Chaque hook lit l'évènement JSON sur stdin et répond par un JSON hookSpecificOutput.

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
[Console]::InputEncoding = [System.Text.UTF8Encoding]::new($false)

function Read-HookInput {
    $raw = [Console]::In.ReadToEnd()
    if ([string]::IsNullOrWhiteSpace($raw)) {
        return $null
    }
    return $raw | ConvertFrom-Json
}

function Get-InputField($event, [string] $name) {
    if ($null -eq $event -or $null -eq $event.tool_input) {
        return $null
    }
    $property = $event.tool_input.PSObject.Properties[$name]
    if ($null -eq $property) {
        return $null
    }
    return [string] $property.Value
}

function Get-ProjectRoot {
    if ($env:CLAUDE_PROJECT_DIR) {
        return [System.IO.Path]::GetFullPath($env:CLAUDE_PROJECT_DIR)
    }
    return (Get-Location).Path
}

# Chemin repository-relative en séparateurs '/', ou $null hors projet.
function Get-RepositoryPath([string] $filePath) {
    if ([string]::IsNullOrWhiteSpace($filePath)) {
        return $null
    }
    $root = (Get-ProjectRoot).TrimEnd('\', '/')
    $full = [System.IO.Path]::GetFullPath($filePath, $root)
    $comparison = [System.StringComparison]::OrdinalIgnoreCase
    if (-not $full.StartsWith($root + [System.IO.Path]::DirectorySeparatorChar, $comparison) -and
        -not $full.StartsWith($root + '/', $comparison)) {
        return $null
    }
    return $full.Substring($root.Length + 1).Replace('\', '/')
}

function Write-PreToolDecision([string] $decision, [string] $reason) {
    @{
        hookSpecificOutput = @{
            hookEventName            = 'PreToolUse'
            permissionDecision       = $decision
            permissionDecisionReason = $reason
        }
    } | ConvertTo-Json -Depth 4 -Compress
    exit 0
}

function Write-AdditionalContext([string] $eventName, [string[]] $messages) {
    if ($messages.Count -eq 0) {
        exit 0
    }
    @{
        hookSpecificOutput = @{
            hookEventName     = $eventName
            additionalContext = ($messages -join "`n")
        }
    } | ConvertTo-Json -Depth 4 -Compress
    exit 0
}
