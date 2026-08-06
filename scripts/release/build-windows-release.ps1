[CmdletBinding()]
param(
    [string]$Version = '',
    [string]$OutputRoot = '',
    [switch]$SkipVerify
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
[xml]$pom = Get-Content -LiteralPath (Join-Path $repo 'pom.xml') -Raw
if ([string]::IsNullOrWhiteSpace($Version)) { $Version = [string]$pom.project.version }
if ([string]::IsNullOrWhiteSpace($OutputRoot)) { $OutputRoot = Join-Path $repo 'target\dist' }
$OutputRoot = [IO.Path]::GetFullPath($OutputRoot)

$distributionBuilder = Join-Path $PSScriptRoot 'build-windows-distribution.ps1'
$installerBuilder = Join-Path $PSScriptRoot 'build-windows-installer.ps1'

$distributionArgs = @('-Version', $Version, '-OutputRoot', $OutputRoot)
if ($SkipVerify) { $distributionArgs += '-SkipVerify' }
$distribution = (& $distributionBuilder @distributionArgs | Select-Object -Last 1)
if ([string]::IsNullOrWhiteSpace($distribution)) { throw 'Windows distribution builder returned no distribution path.' }

$setup = (& $installerBuilder -Version $Version -DistributionRoot $distribution -OutputRoot $OutputRoot | Select-Object -Last 1)
if ([string]::IsNullOrWhiteSpace($setup)) { throw 'Windows installer builder returned no setup path.' }

$zip = Join-Path $OutputRoot "nexus-context-engine-$Version-windows-x64.zip"
Write-Host ''
Write-Host '=== NEXUS Windows release candidate ===' -ForegroundColor Green
Write-Host "Portable ZIP : $zip"
Write-Host "Installer EXE: $setup"
Write-Host "ZIP SHA-256  : $zip.sha256"
Write-Host "EXE SHA-256  : $setup.sha256"
