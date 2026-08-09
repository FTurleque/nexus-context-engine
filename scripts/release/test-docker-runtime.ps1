[CmdletBinding()]
param(
    [string]$Image = '',
    [int]$HostPort = 18080
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
[xml]$pom = Get-Content -LiteralPath (Join-Path $repo 'pom.xml') -Raw
$version = [string]$pom.project.version
if ([string]::IsNullOrWhiteSpace($Image)) { $Image = "nexus-context-engine:$version" }

$docker = Get-Command docker -ErrorAction SilentlyContinue
if ($null -eq $docker) { throw 'Docker CLI is required for the Docker smoke tests.' }

$prefix = "nexus-smoke-$PID"
$restName = "$prefix-rest"
$mcpName = "$prefix-mcp"
$missingForwardName = "$prefix-rest-missing-forward"
$remoteForwardName = "$prefix-rest-remote-forward"
$restToken = '6df1462d571a6925e3bc3934ee10c6c55a965116fb47e2bc4db77ac7a5d69d34'

function Remove-Container([string]$Name) {
    & $docker.Source rm -f $Name *> $null
}

function Assert-RestContainerRejected([string]$Name, [string[]]$DockerArgs, [string]$ExpectedDiagnostic) {
    & $docker.Source run -d --name $Name @DockerArgs $Image rest | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Unable to create negative REST container $Name." }
    & $docker.Source wait $Name *> $null
    $exitCode = [int](& $docker.Source inspect -f '{{.State.ExitCode}}' $Name | Select-Object -First 1)
    $logs = (& $docker.Source logs $Name 2>&1 | Out-String)
    if ($exitCode -eq 0) {
        throw "Negative REST container $Name unexpectedly exited successfully. Logs: $logs"
    }
    if ($logs.IndexOf($ExpectedDiagnostic, [StringComparison]::Ordinal) -lt 0) {
        throw "Negative REST container $Name did not report '$ExpectedDiagnostic'. Logs: $logs"
    }
}

try {
    Write-Host 'Docker CLI smoke...' -ForegroundColor Cyan
    $versionJson = (& $docker.Source run --rm $Image cli --version --json | Out-String)
    if ($LASTEXITCODE -ne 0) { throw 'Docker CLI smoke failed.' }
    $parsed = $versionJson | ConvertFrom-Json
    if ($parsed.version -ne $version) { throw "Docker CLI version mismatch: $($parsed.version) != $version" }

    Write-Host 'Docker MCP JSON-RPC protocol smoke...' -ForegroundColor Cyan
    # Vrai smoke protocolaire (initialize/tools/list/tools/call) et non plus un simple test de
    # liveness du conteneur. --rm : le conteneur éphémère est retiré à la fin du run STDIO.
    $mcpSmoke = Join-Path $PSScriptRoot 'test-mcp-protocol.ps1'
    & $mcpSmoke -Exe $docker.Source `
        -LaunchArgs @('run', '-i', '--rm', '--name', $mcpName, $Image, 'mcp') `
        -Label 'Docker'
    Remove-Container $mcpName

    Write-Host "Docker REST smoke on host port $HostPort..." -ForegroundColor Cyan
    & $docker.Source run -d --name $restName `
        -e "NEXUS_REST_API_TOKEN=$restToken" `
        -e 'NEXUS_DOCKER_HOST_FORWARD_ADDRESS=127.0.0.1' `
        -p "127.0.0.1:${HostPort}:8080" $Image rest | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Unable to start the REST container.' }

    $healthy = $false
    $headers = @{ Authorization = "Bearer $restToken" }
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        try {
            $response = Invoke-RestMethod -Uri "http://127.0.0.1:$HostPort/q/health/live" -Headers $headers -TimeoutSec 2
            if ($response.status -eq 'UP') { $healthy = $true; break }
        }
        catch {
            Start-Sleep -Seconds 1
        }
    }
    if (-not $healthy) {
        $logs = (& $docker.Source logs $restName 2>&1 | Out-String)
        throw "Docker REST health smoke failed: $logs"
    }

    Write-Host 'Docker REST missing-forward negative smoke...' -ForegroundColor Cyan
    Assert-RestContainerRejected `
        -Name $missingForwardName `
        -DockerArgs @('-e', "NEXUS_REST_API_TOKEN=$restToken") `
        -ExpectedDiagnostic 'NEXUS_DOCKER_HOST_FORWARD_ADDRESS'

    Write-Host 'Docker REST remote-forward negative smoke...' -ForegroundColor Cyan
    Assert-RestContainerRejected `
        -Name $remoteForwardName `
        -DockerArgs @(
            '-e', "NEXUS_REST_API_TOKEN=$restToken",
            '-e', 'NEXUS_DOCKER_HOST_FORWARD_ADDRESS=0.0.0.0') `
        -ExpectedDiagnostic 'NEXUS_DOCKER_HOST_FORWARD_ADDRESS'

    Write-Host 'NEXUS Docker CLI/MCP/REST positive and negative smokes PASS' -ForegroundColor Green
}
finally {
    Remove-Container $mcpName
    Remove-Container $restName
    Remove-Container $missingForwardName
    Remove-Container $remoteForwardName
}
