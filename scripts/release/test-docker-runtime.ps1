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
$restToken = "nexus-docker-smoke-$PID"

function Remove-Container([string]$Name) {
    & $docker.Source rm -f $Name *> $null
}

try {
    Write-Host 'Docker CLI smoke...' -ForegroundColor Cyan
    $versionJson = (& $docker.Source run --rm $Image cli --version --json | Out-String)
    if ($LASTEXITCODE -ne 0) { throw 'Docker CLI smoke failed.' }
    $parsed = $versionJson | ConvertFrom-Json
    if ($parsed.version -ne $version) { throw "Docker CLI version mismatch: $($parsed.version) != $version" }

    Write-Host 'Docker MCP STDIO process smoke...' -ForegroundColor Cyan
    & $docker.Source run -d -i --name $mcpName $Image mcp | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Unable to start the MCP STDIO container.' }
    Start-Sleep -Seconds 2
    $mcpRunning = (& $docker.Source inspect -f '{{.State.Running}}' $mcpName | Out-String).Trim()
    if ($mcpRunning -ne 'true') {
        $logs = (& $docker.Source logs $mcpName 2>&1 | Out-String)
        throw "MCP container did not remain available for STDIO: $logs"
    }
    Remove-Container $mcpName

    Write-Host "Docker REST smoke on host port $HostPort..." -ForegroundColor Cyan
    & $docker.Source run -d --name $restName -e "NEXUS_REST_API_TOKEN=$restToken" -p "127.0.0.1:${HostPort}:8080" $Image rest | Out-Null
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

    Write-Host 'NEXUS Docker CLI/MCP/REST smoke PASS' -ForegroundColor Green
}
finally {
    Remove-Container $mcpName
    Remove-Container $restName
}
