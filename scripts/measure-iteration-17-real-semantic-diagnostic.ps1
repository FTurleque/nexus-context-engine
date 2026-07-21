[CmdletBinding()]
param(
    [string]$BaseUri = "http://localhost:11434",
    [string]$Model = "qwen3-embedding:0.6b",
    [int]$Dimensions = 1024,
    [int]$TimeoutSeconds = 60,
    [string]$Workspace = "target\iteration-17-real-semantic-diagnostic",
    [string]$Output = "target\iteration-17-real-semantic-diagnostic.json",
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
    Write-Host " NEXUS - Diagnostic semantique reel kNN brut vs hybride"
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

    $workspacePath = Resolve-AbsolutePath -Path $Workspace
    $outputPath = Resolve-AbsolutePath -Path $Output
    if (Test-Path $workspacePath) {
        Remove-Item -Recurse -Force $workspacePath
    }
    New-Item -ItemType Directory -Force -Path $workspacePath | Out-Null

    $outputParent = Split-Path -Parent $outputPath
    if (-not (Test-Path $outputParent)) {
        New-Item -ItemType Directory -Force -Path $outputParent | Out-Null
    }
    if (Test-Path $outputPath) {
        Remove-Item -Force $outputPath
    }

    $commit = (& git rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($commit)) {
        throw "Impossible de resoudre HEAD."
    }

    $archivePath = Join-Path $workspacePath "nexus-head.zip"
    $snapshotRoot = Join-Path $workspacePath "nexus-snapshot"
    Write-Host "Snapshot controle : nexus-context-engine @ $commit"
    & git archive --format=zip --output=$archivePath HEAD
    if ($LASTEXITCODE -ne 0) {
        throw "git archive a echoue avec le code $LASTEXITCODE"
    }
    Expand-Archive -LiteralPath $archivePath -DestinationPath $snapshotRoot -Force

    $excludedPaths = @(
        "docs/developer/semantic-search.md",
        "docs/developer/iteration-17-semantic-results.md",
        "scripts/measure-iteration-17-semantic.ps1",
        "scripts/measure-iteration-17-real-semantic.ps1",
        "scripts/measure-iteration-17-real-semantic-diagnostic.ps1",
        "scripts/validate-iteration-17.ps1",
        "src/test/java/com/nexus/search/semantic/SemanticSearchBenchmarkTest.java",
        "src/test/java/com/nexus/search/semantic/RealSemanticSearchBenchmarkTest.java",
        "src/test/java/com/nexus/search/semantic/RealSemanticRetrievalDiagnosticTest.java",
        "src/test/java/com/nexus/application/NexusApplicationSemanticConfigurationTest.java",
        "src/test/java/com/nexus/ranking/SemanticRankingTest.java"
    )

    $snapshotFull = [System.IO.Path]::GetFullPath($snapshotRoot)
    $snapshotPrefix = $snapshotFull.TrimEnd([char[]]@('\', '/')) + [System.IO.Path]::DirectorySeparatorChar
    foreach ($relativePath in $excludedPaths) {
        $target = [System.IO.Path]::GetFullPath((Join-Path $snapshotFull $relativePath))
        if (-not $target.StartsWith($snapshotPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
            throw "Chemin d'exclusion hors snapshot refuse : $relativePath"
        }
        if (Test-Path $target) {
            Remove-Item -Recurse -Force $target
        }
    }
    Remove-Item -Force $archivePath

    Write-Host "Artefacts Iteration 17 exclus du corpus : $($excludedPaths.Count)"
    Write-Host "Corpus                              : $snapshotRoot"
    Write-Host

    $arguments = @(
        "-Dnexus.semantic.realDiagnostic.enabled=true",
        "-Dnexus.semantic.realBenchmark.root=$snapshotRoot",
        "-Dnexus.semantic.realBenchmark.commit=$commit",
        "-Dnexus.semantic.realDiagnostic.output=$outputPath",
        "-Dnexus.semantic.ollama.baseUri=$BaseUri",
        "-Dnexus.semantic.ollama.model=$Model",
        "-Dnexus.semantic.ollama.dimensions=$Dimensions",
        "-Dnexus.semantic.ollama.timeoutSeconds=$TimeoutSeconds",
        "-Dtest=RealSemanticRetrievalDiagnosticTest",
        "test"
    )

    & mvn @arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Le diagnostic semantique Maven a echoue avec le code $LASTEXITCODE"
    }

    if (-not (Test-Path -Path $outputPath -PathType Leaf)) {
        throw "Le diagnostic est termine sans produire le rapport attendu : $outputPath"
    }

    Write-Host
    Write-Host "=== DIAGNOSTIC SEMANTIQUE REEL ITERATION 17 ==="
    Get-Content -Raw -Path $outputPath
    Write-Host "================================================"
    Write-Host
    Write-Host "Rapport : $outputPath"
}
catch {
    Write-Host
    Write-Host "============================================================"
    Write-Host " DIAGNOSTIC SEMANTIQUE REEL ITERATION 17 INTERROMPU"
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
