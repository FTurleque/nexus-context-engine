[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string[]]$ProjectRoots,

    [Alias("Query")]
    [string[]]$Queries = @("SearchService"),

    [string]$Output = "target\iteration-16-baseline.json"
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$locationPushed = $false
$inputPath = $null

function Invoke-Maven {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)

    & mvn @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "La commande Maven a echoue avec le code $LASTEXITCODE : mvn $($Arguments -join ' ')"
    }
}

function Resolve-OutputPath {
    param([Parameter(Mandatory = $true)][string]$Path)

    if ([System.IO.Path]::IsPathRooted($Path)) {
        return [System.IO.Path]::GetFullPath($Path)
    }
    return [System.IO.Path]::GetFullPath((Join-Path $repoRoot $Path))
}

try {
    Push-Location $repoRoot
    $locationPushed = $true

    $resolvedRoots = @()
    foreach ($root in $ProjectRoots) {
        $resolved = (Resolve-Path $root).Path
        if (-not (Test-Path -Path $resolved -PathType Container)) {
            throw "Le repository baseline n'est pas un repertoire : $resolved"
        }
        if ($resolvedRoots -notcontains $resolved) {
            $resolvedRoots += $resolved
        }
    }

    if ($resolvedRoots.Count -eq 0) {
        throw "Au moins un repository doit etre fourni via -ProjectRoots."
    }

    $resolvedQueries = @()
    foreach ($query in $Queries) {
        $trimmed = $query.Trim()
        if (-not [string]::IsNullOrWhiteSpace($trimmed) -and $resolvedQueries -notcontains $trimmed) {
            $resolvedQueries += $trimmed
        }
    }

    if ($resolvedQueries.Count -eq 0) {
        throw "Au moins une requete doit etre fournie via -Queries."
    }

    $resolvedOutput = Resolve-OutputPath -Path $Output
    $inputDirectory = Join-Path $repoRoot "target"
    New-Item -ItemType Directory -Force -Path $inputDirectory | Out-Null
    $inputPath = Join-Path $inputDirectory ("iteration-16-baseline-input-{0}.json" -f [Guid]::NewGuid().ToString("N"))

    [ordered]@{
        projects = $resolvedRoots
        queries = $resolvedQueries
    } | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 -Path $inputPath

    Write-Host "============================================================"
    Write-Host " NEXUS - Baseline Iteration 16 / Large Scale Search"
    Write-Host "============================================================"
    Write-Host "Repositories : $($resolvedRoots.Count)"
    foreach ($root in $resolvedRoots) {
        Write-Host " - $root"
    }
    Write-Host "Requetes     : $($resolvedQueries.Count)"
    foreach ($query in $resolvedQueries) {
        Write-Host " - $query"
    }
    Write-Host "Entree       : $inputPath"
    Write-Host "Rapport      : $resolvedOutput"
    Write-Host

    Invoke-Maven -Arguments @(
        "-Dtest=LargeScaleSearchBaselineTest",
        "-Dnexus.baseline.input=$inputPath",
        "-Dnexus.baseline.output=$resolvedOutput",
        "test"
    )

    if (-not (Test-Path $resolvedOutput)) {
        throw "Le rapport baseline attendu n'a pas ete produit : $resolvedOutput"
    }

    Write-Host
    Write-Host "=== RAPPORT BASELINE ==="
    Get-Content -Raw -Path $resolvedOutput | Write-Host
    Write-Host "========================"
    Write-Host
    Write-Host "La baseline de performance est produite sans modifier les repositories sources."
    Write-Host "L'indexation incrementale avec petit delta doit etre mesuree separement sur une copie de travail controlee."
    Write-Host "Les metriques precision@3 et recall@3 restent couvertes par les corpus golden et devront etre etendues a un corpus multi-repository reel."
}
catch {
    Write-Host
    Write-Host "============================================================"
    Write-Host " BASELINE ITERATION 16 INTERROMPUE"
    Write-Host "============================================================"
    Write-Host $_.Exception.Message
    Write-Host
    Write-Host "Le terminal reste ouvert. Copiez la sortie depuis l'etape en echec."
}
finally {
    if ($null -ne $inputPath -and (Test-Path -Path $inputPath -PathType Leaf)) {
        Remove-Item -Force -Path $inputPath
    }
    if ($locationPushed) {
        Pop-Location
    }
}
