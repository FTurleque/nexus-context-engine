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
$args = @('build', '--file', $dockerfile, '--tag', $Image)
if (-not $NoPull) { $args += '--pull' }
$args += $repo

Write-Host "Building NEXUS Docker image $Image" -ForegroundColor Cyan
& $docker.Source @args
if ($LASTEXITCODE -ne 0) {
    throw "Docker image build failed with exit code $LASTEXITCODE"
}

Write-Host "NEXUS Docker image SUCCESS: $Image" -ForegroundColor Green
Write-Output $Image
