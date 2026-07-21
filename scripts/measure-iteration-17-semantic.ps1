[CmdletBinding()]
param(
    [string]$BaseUri = "http://localhost:11434",
    [string]$Model = "qwen3-embedding:0.6b",
    [int]$Dimensions = 1024,
    [int]$TimeoutSeconds = 60,
    [string]$Output = "target\iteration-17-semantic-benchmark.json",
    [switch]$PullModel
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$locationPushed = $false

function Resolve-AbsolutePath {
    param([Parameter(Mandatory = $true)][string]$Path)

    if ([System.IO.Path]::IsPathRooted($Path)) {
        return [System.IO.Path]::GetFullPath($Path)
    }
    return [System.IO.Path]::GetFullPath((Join-Path $repoRoot $Path))
}

try {
    if ($Dimensions -le 0 -or $Dimensions -gt 1024) {
        throw "Dimensions doit etre compris entre 1 et 1024."
    }
    if ($TimeoutSeconds -le 0) {
        throw "TimeoutSeconds doit etre strictement positif."
    }

    Push-Location $repoRoot
    $locationPushed = $true

    Write-Host "============================================================"
    Write-Host " NEXUS - Iteration 17 / Benchmark semantique A-B"
    Write-Host "============================================================"
    Write-Host "Endpoint   : $BaseUri"
    Write-Host "Modele     : $Model"
    Write-Host "Dimensions : $Dimensions"
    Write-Host

    if ($PullModel) {
        $ollamaCommand = Get-Command ollama -ErrorAction SilentlyContinue
        if ($null -eq $ollamaCommand) {
            throw "La commande 'ollama' est introuvable. Installez Ollama ou retirez -PullModel si le serveur est distant."
        }
        Write-Host "Preparation du modele Ollama : $Model"
        & ollama pull $Model
        if ($LASTEXITCODE -ne 0) {
            throw "ollama pull a echoue avec le code $LASTEXITCODE"
        }
        Write-Host
    }

    $outputPath = Resolve-AbsolutePath -Path $Output
    $outputParent = Split-Path -Parent $outputPath
    if (-not (Test-Path $outputParent)) {
        New-Item -ItemType Directory -Force -Path $outputParent | Out-Null
    }
    if (Test-Path $outputPath) {
        Remove-Item -Force $outputPath
    }

    $arguments = @(
        "-Dnexus.semantic.benchmark.enabled=true",
        "-Dnexus.semantic.ollama.baseUri=$BaseUri",
        "-Dnexus.semantic.ollama.model=$Model",
        "-Dnexus.semantic.ollama.dimensions=$Dimensions",
        "-Dnexus.semantic.ollama.timeoutSeconds=$TimeoutSeconds",
        "-Dnexus.semantic.benchmark.output=$outputPath",
        "-Dtest=SemanticSearchBenchmarkTest",
        "test"
    )

    & mvn @arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Le benchmark Maven a echoue avec le code $LASTEXITCODE"
    }

    if (-not (Test-Path -Path $outputPath -PathType Leaf)) {
        throw "Le benchmark est termine sans produire le rapport attendu : $outputPath"
    }

    Write-Host
    Write-Host "=== RAPPORT SEMANTIQUE ITERATION 17 ==="
    Get-Content -Raw -Path $outputPath
    Write-Host "========================================"
    Write-Host
    Write-Host "Rapport : $outputPath"
}
catch {
    Write-Host
    Write-Host "============================================================"
    Write-Host " BENCHMARK SEMANTIQUE ITERATION 17 INTERROMPU"
    Write-Host "============================================================"
    Write-Host $_.Exception.Message
    Write-Host
    Write-Host "Le terminal reste ouvert. Copiez la sortie depuis l'etape en echec."
}
finally {
    if ($locationPushed) {
        Pop-Location
    }
}
