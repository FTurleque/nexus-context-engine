[CmdletBinding()]
param(
    [string]$ProjectName = "nexus-scip-evaluation",
    [int]$K = 3
)

$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$evaluationHome = Join-Path $repoRoot "target\nexus-scip-evaluation-home"
$resultsDirectory = Join-Path $repoRoot "target\scip-evaluation"
$summaryPath = Join-Path $resultsDirectory "summary.json"
$indexPath = Join-Path $repoRoot "index.scip"
$backupPath = Join-Path $repoRoot "index.scip.nexus-evaluation-backup"
$previousNexusHome = $env:NEXUS_HOME
$locationPushed = $false
$indexMoved = $false
$script:cliJar = $null

function Invoke-Maven {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)

    & mvn @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "La commande Maven a echoue avec le code $LASTEXITCODE : mvn $($Arguments -join ' ')"
    }
}

function Invoke-NexusJson {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)

    $stdoutFile = [System.IO.Path]::GetTempFileName()
    $stderrFile = [System.IO.Path]::GetTempFileName()
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        & java -jar $script:cliJar.FullName @Arguments 1> $stdoutFile 2> $stderrFile
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }

    try {
        $stdout = Get-Content -Raw -Path $stdoutFile -ErrorAction SilentlyContinue
        $stderr = Get-Content -Raw -Path $stderrFile -ErrorAction SilentlyContinue
        if (-not [string]::IsNullOrWhiteSpace($stderr)) {
            Write-Host $stderr.TrimEnd()
        }
        if ($exitCode -ne 0) {
            throw "La CLI NEXUS a echoue avec le code $exitCode pour : $($Arguments -join ' ')"
        }
        if ([string]::IsNullOrWhiteSpace($stdout)) {
            throw "La CLI NEXUS n'a retourne aucune sortie JSON pour : $($Arguments -join ' ')"
        }
        return ($stdout | ConvertFrom-Json)
    }
    finally {
        Remove-Item -Force $stdoutFile, $stderrFile -ErrorAction SilentlyContinue
    }
}

function Measure-Quality {
    param(
        [Parameter(Mandatory = $true)][object[]]$Queries,
        [Parameter(Mandatory = $true)][int]$TopK
    )

    $precisionSum = 0.0
    $recallSum = 0.0
    $details = @()

    foreach ($query in $Queries) {
        $search = Invoke-NexusJson -Arguments @("search", $ProjectName, $query.Query, "--limit", "20", "--json")
        $rankedPaths = @(
            $search.results |
                ForEach-Object { ([string]$_.path).Replace('\', '/') } |
                Select-Object -Unique
        )
        $topPaths = @($rankedPaths | Select-Object -First $TopK)
        $relevant = @($query.Relevant)
        $hits = @($relevant | Where-Object { $topPaths -contains $_ }).Count
        $precision = [double]$hits / [double]$TopK
        $recall = if ($relevant.Count -eq 0) { 0.0 } else { [double]$hits / [double]$relevant.Count }
        $precisionSum += $precision
        $recallSum += $recall
        $details += [pscustomobject]@{
            query = $query.Query
            relevant = $relevant
            topK = $topPaths
            precisionAtK = $precision
            recallAtK = $recall
        }
    }

    return [pscustomobject]@{
        corpusSize = $Queries.Count
        precisionAtK = $precisionSum / [double]$Queries.Count
        recallAtK = $recallSum / [double]$Queries.Count
        queries = $details
    }
}

$queries = @(
    [pscustomobject]@{
        Query = "ProjectIndexingService"
        Relevant = @("src/main/java/com/nexus/index/ProjectIndexingService.java")
    },
    [pscustomobject]@{
        Query = "ScipCodeIndexImporter"
        Relevant = @("src/main/java/com/nexus/index/scip/ScipCodeIndexImporter.java")
    },
    [pscustomobject]@{
        Query = "replaceExternalCodeIntelligence"
        Relevant = @("src/main/java/com/nexus/persistence/sqlite/SqliteIndexRepository.java")
    },
    [pscustomobject]@{
        Query = "CodeIntelligenceSnapshot"
        Relevant = @("src/main/java/com/nexus/index/CodeIntelligenceSnapshot.java")
    },
    [pscustomobject]@{
        Query = "sourceProvider confidence SymbolRelation"
        Relevant = @("src/main/java/com/nexus/index/SymbolRelation.java")
    }
)

try {
    Push-Location $repoRoot
    $locationPushed = $true

    if (-not (Test-Path $indexPath)) {
        throw "index.scip est introuvable a la racine du repository. Generez-le avant de lancer cette comparaison."
    }
    if (Test-Path $backupPath) {
        throw "Le fichier de sauvegarde '$backupPath' existe deja. Verifiez une ancienne execution avant de continuer."
    }

    Write-Host "=== NEXUS SCIP evaluation ==="
    Write-Host "Repository : $repoRoot"
    Write-Host "index.scip : $((Get-Item $indexPath).Length) octets"
    Write-Host

    Write-Host "[1/8] Construction du JAR CLI"
    Invoke-Maven -Arguments @("-q", "-DskipTests", "package")
    $script:cliJar = Get-ChildItem -Path (Join-Path $repoRoot "target") -Filter "nexus-context-engine-*-cli.jar" -File |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if ($null -eq $script:cliJar) {
        throw "Le JAR CLI NEXUS est introuvable apres le build."
    }

    Write-Host "[2/8] Preparation d'un NEXUS_HOME dedie"
    if (Test-Path $evaluationHome) {
        Remove-Item -Recurse -Force $evaluationHome
    }
    New-Item -ItemType Directory -Path $evaluationHome -Force | Out-Null
    New-Item -ItemType Directory -Path $resultsDirectory -Force | Out-Null
    $env:NEXUS_HOME = $evaluationHome

    Write-Host "[3/8] Enregistrement du repository"
    $null = Invoke-NexusJson -Arguments @("project", "add", ".", $ProjectName, "--json")

    Write-Host "[4/8] Baseline JavaParser seul"
    Move-Item -Path $indexPath -Destination $backupPath
    $indexMoved = $true
    $baselineIndex = Invoke-NexusJson -Arguments @("index", $ProjectName, "--rebuild", "--json")
    $baselineInspect = Invoke-NexusJson -Arguments @("inspect", $ProjectName, "--json")
    $baselineQuality = Measure-Quality -Queries $queries -TopK $K

    Write-Host "[5/8] Restauration de index.scip"
    Move-Item -Path $backupPath -Destination $indexPath
    $indexMoved = $false

    Write-Host "[6/8] Enrichissement JavaParser + SCIP"
    $scipIndex = Invoke-NexusJson -Arguments @("index", $ProjectName, "--rebuild", "--json")
    $scipInspect = Invoke-NexusJson -Arguments @("inspect", $ProjectName, "--json")
    $scipQuality = Measure-Quality -Queries $queries -TopK $K

    Write-Host "[7/8] Calcul des ecarts"
    $summary = [ordered]@{
        generatedAt = (Get-Date).ToString("o")
        projectName = $ProjectName
        indexScipBytes = (Get-Item $indexPath).Length
        k = $K
        baseline = [ordered]@{
            provider = "javaparser"
            durationMs = [int64]$baselineIndex.report.durationMs
            files = [int64]$baselineInspect.index.files
            symbols = [int64]$baselineInspect.index.symbols
            relations = [int64]$baselineInspect.index.relations
            precisionAtK = [double]$baselineQuality.precisionAtK
            recallAtK = [double]$baselineQuality.recallAtK
        }
        enriched = [ordered]@{
            provider = "javaparser+scip"
            durationMs = [int64]$scipIndex.report.durationMs
            files = [int64]$scipInspect.index.files
            symbols = [int64]$scipInspect.index.symbols
            relations = [int64]$scipInspect.index.relations
            precisionAtK = [double]$scipQuality.precisionAtK
            recallAtK = [double]$scipQuality.recallAtK
        }
        delta = [ordered]@{
            durationMs = [int64]$scipIndex.report.durationMs - [int64]$baselineIndex.report.durationMs
            symbols = [int64]$scipInspect.index.symbols - [int64]$baselineInspect.index.symbols
            relations = [int64]$scipInspect.index.relations - [int64]$baselineInspect.index.relations
            precisionAtK = [double]$scipQuality.precisionAtK - [double]$baselineQuality.precisionAtK
            recallAtK = [double]$scipQuality.recallAtK - [double]$baselineQuality.recallAtK
        }
        baselineQueries = $baselineQuality.queries
        enrichedQueries = $scipQuality.queries
    }

    $summary | ConvertTo-Json -Depth 8 | Set-Content -Path $summaryPath -Encoding UTF8

    Write-Host "[8/8] Resultat"
    Write-Host
    Write-Host "=== COMPARAISON SCIP ==="
    Write-Host ("JavaParser       : {0} fichiers, {1} symboles, {2} relations, {3} ms" -f $summary.baseline.files, $summary.baseline.symbols, $summary.baseline.relations, $summary.baseline.durationMs)
    Write-Host ("JavaParser + SCIP: {0} fichiers, {1} symboles, {2} relations, {3} ms" -f $summary.enriched.files, $summary.enriched.symbols, $summary.enriched.relations, $summary.enriched.durationMs)
    Write-Host ("Delta            : {0:+#;-#;0} symboles, {1:+#;-#;0} relations, {2:+#;-#;0} ms" -f $summary.delta.symbols, $summary.delta.relations, $summary.delta.durationMs)
    Write-Host ("Precision@{0}     : {1:N4} -> {2:N4} (delta {3:+0.0000;-0.0000;0.0000})" -f $K, $summary.baseline.precisionAtK, $summary.enriched.precisionAtK, $summary.delta.precisionAtK)
    Write-Host ("Recall@{0}        : {1:N4} -> {2:N4} (delta {3:+0.0000;-0.0000;0.0000})" -f $K, $summary.baseline.recallAtK, $summary.enriched.recallAtK, $summary.delta.recallAtK)
    Write-Host "Rapport JSON      : $summaryPath"
    Write-Host "========================"
}
finally {
    if ($indexMoved -and (Test-Path $backupPath)) {
        Move-Item -Path $backupPath -Destination $indexPath -Force
    }
    $env:NEXUS_HOME = $previousNexusHome
    if ($locationPushed) {
        Pop-Location
    }
}
