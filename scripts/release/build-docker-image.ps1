[CmdletBinding()]
param(
    [string]$Image = '',
    [switch]$NoPull
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
[xml]$pom = Get-Content -LiteralPath (Join-Path $repo 'pom.xml') -Raw
$version = [string]$pom.project.version
if ([string]::IsNullOrWhiteSpace($Image)) {
    $Image = "nexus-context-engine:$version"
}

$docker = Get-Command docker -ErrorAction SilentlyContinue
if ($null -eq $docker) {
    throw 'Docker CLI is required to build the NEXUS Docker distribution.'
}
& $docker.Source version --format '{{.Server.Version}}' | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw 'Docker Engine is not available. Start Docker Desktop/Engine and retry.'
}

$dockerfile = Join-Path $repo 'packaging\docker\Dockerfile'
$dockerfileSource = Get-Content -LiteralPath $dockerfile -Raw
$entrypointNormalization = "sed -i 's/\r$//' /usr/local/bin/nexus-entrypoint"
if ($dockerfileSource.IndexOf($entrypointNormalization, [StringComparison]::Ordinal) -lt 0) {
    throw 'Dockerfile must normalize CRLF on nexus-container-entrypoint.sh before producing a Windows-checkout-compatible image.'
}

# OCI labels: the pom version and, for an unmodified Git working tree, the commit built.
$args = @('build', '--file', $dockerfile, '--tag', $Image, '--build-arg', "NEXUS_IMAGE_VERSION=$version")
$git = Get-Command git -ErrorAction SilentlyContinue
if ($null -ne $git) {
    $revision = ''
    $changes = 'unknown'
    try {
        $revision = ([string](& $git.Source -C $repo rev-parse HEAD 2>$null | Select-Object -First 1)).Trim()
        $changes = [string](& $git.Source -C $repo status --porcelain 2>$null | Out-String)
    }
    catch { $revision = '' }
    if ([string]::IsNullOrWhiteSpace($changes) -and $revision -cmatch '^[0-9a-f]{40}$') {
        $args += @('--build-arg', "NEXUS_IMAGE_REVISION=$revision")
    }
}
if (-not $NoPull) { $args += '--pull' }
$args += $repo

Write-Host "Building NEXUS Docker image $Image" -ForegroundColor Cyan
& $docker.Source @args
if ($LASTEXITCODE -ne 0) {
    throw "Docker image build failed with exit code $LASTEXITCODE"
}

Write-Host "NEXUS Docker image SUCCESS: $Image" -ForegroundColor Green
Write-Output $Image
