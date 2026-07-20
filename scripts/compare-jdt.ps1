[CmdletBinding()]
param(
    [string]$ProjectName = "nexus-jdt-evaluation",
    [int]$K = 3
)

$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$evaluationHome = Join-Path $repoRoot "target\nexus-jdt-evaluation-home"
$resultsDirectory = Join-Path $repoRoot "target\jdt-evaluation"
$summaryPath = Join-Path $resultsDirectory "summary.json"
$previousNexusHome = $env:NEXUS_HOME
$locationPushed = $false
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
        # Windows PowerShell 5.1 transforme parfois les warnings stderr des
        # processus natifs en NativeCommandError visuels. Le code de sortie
        # du processus Java reste la source de verite.
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
        Query = "JdtLanguageServerCodeIntelligenceProvider"
        Relevant = @("src/main/java/com/nexus/index/jdt/JdtLanguageServerCodeIntelligenceProvider.java")
    },
    [pscustomobject]@{
        Query = "CodeIntelligenceProvider analyze"
        Relevant = @("src/main/java/com/nexus/index/CodeIntelligenceProvider.java")
    },
    [pscustomobject]@{
        Query = "ProjectIndexingService deep Java"
        Relevant = @("src/main/java/com/nexus/index/ProjectIndexingService.java")
    },
    [pscustomobject]@{
        Query = "replaceExternalCodeIntelligence"
        Relevant = @("src/main/java/com/nexus/persistence/sqlite/SqliteIndexRepository.java")
    },
    [pscustomobject]@{
        Query = "GraphCandidateEnricher ProjectGraphBuilder"
        Relevant = @(
            "src/main/java/com/nexus/ranking/graph/GraphCandidateEnricher.java",
            "src/main/java/com/nexus/ranking/graph/ProjectGraphBuilder.java"
        )
    }
)

try {
    Push-Location $repoRoot
    $locationPushed = $true

    if ([string]::IsNullOrWhiteSpace($env:NEXUS_JDTLS_HOME)) {
        throw "NEXUS_JDTLS_HOME n'est pas configure. Lancez d'abord scripts/install-jdtls.ps1."
    }
    if (-not (Test-Path $env:NEXUS_JDTLS_HOME)) {
        throw "NEXUS_JDTLS_HOME pointe vers un chemin introuvable : $env:NEXUS_JDTLS_HOME"
    }

    Write-Host "=== NEXUS JDT LS evaluation ==="
    Write-Host "Repository : $repoRoot"
    Write-Host "JDT LS : $env:NEXUS_JDTLS_HOME"
    Write-Host

    Write-Host "[1/7] Construction du JAR CLI"
    Invoke-Maven -Arguments @("-q", "-DskipTests", "package")
    $script:cliJar = Get-ChildItem -Path (Join-Path $repoRoot "target") -Filter "nexus-context-engine-*-cli.jar" -File |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if ($null -eq $script:cliJar) {
        throw "Le JAR CLI NEXUS est introuvable apres le build."
    }

    Write-Host "[2/7] Preparation d'un NEXUS_HOME dedie"
    if (Test-Path $evaluationHome) {
        Remove-Item -Recurse -Force $evaluationHome
    }
    New-Item -ItemType Directory -Path $evaluationHome -Force | Out-Null
    New-Item -ItemType Directory -Path $resultsDirectory -Force | Out-Null
    $env:NEXUS_HOME = $evaluationHome

    Write-Host "[3/7] Enregistrement du repository"
    $null = Invoke-NexusJson -Arguments @("project", "add", ".", $ProjectName, "--json")

    Write-Host "[4/7] Baseline JavaParser + importers opportunistes"
    $baselineIndex = Invoke-NexusJson -Arguments @("index", $ProjectName, "--rebuild", "--json")
    $baselineInspect = Invoke-NexusJson -Arguments @("inspect", $ProjectName, "--json")
    $baselineQuality = Measure-Quality -Queries $queries -TopK $K

    Write-Host "[5/7] Enrichissement JDT LS avec --deep-java"
    $deepIndex = Invoke-NexusJson -Arguments @("index", $ProjectName, "--rebuild", "--deep-java", "--json")
    $deepInspect = Invoke-NexusJson -Arguments @("inspect", $ProjectName, "--json")
    $deepQuality = Measure-Quality -Queries $queries -TopK $K

    Write-Host "[6/7] Calcul des ecarts"
    $summary = [ordered]@{
        generatedAt = (Get-Date).ToString("o")
        projectName = $ProjectName
        jdtLsHome = $env:NEXUS_JDTLS_HOME
        k = $K
        baseline = [ordered]@{
            provider = "embedded+importers"
            durationMs = [int64]$baselineIndex.report.durationMs
            files = [int64]$baselineInspect.index.files
            symbols = [int64]$baselineInspect.index.symbols
            relations = [int64]$baselineInspect.index.relations
            precisionAtK = [double]$baselineQuality.precisionAtK
            recallAtK = [double]$baselineQuality.recallAtK
        }
        deepJava = [ordered]@{
            provider = "embedded+importers+jdtls"
            durationMs = [int64]$deepIndex.report.durationMs
            files = [int64]$deepInspect.index.files
            symbols = [int64]$deepInspect.index.symbols
            relations = [int64]$deepInspect.index.relations
            precisionAtK = [double]$deepQuality.precisionAtK
            recallAtK = [double]$deepQuality.recallAtK
        }
        delta = [ordered]@{
            durationMs = [int64]$deepIndex.report.durationMs - [int64]$baselineIndex.report.durationMs
            symbols = [int64]$deepInspect.index.symbols - [int64]$baselineInspect.index.symbols
            relations = [int64]$deepInspect.index.relations - [int64]$baselineInspect.index.relations
            precisionAtK = [double]$deepQuality.precisionAtK - [double]$baselineQuality.precisionAtK
            recallAtK = [double]$deepQuality.recallAtK - [double]$baselineQuality.recallAtK
        }
        baselineQueries = $baselineQuality.queries
        deepJavaQueries = $deepQuality.queries
    }

    $summary | ConvertTo-Json -Depth 8 | Set-Content -Path $summaryPath -Encoding UTF8

    Write-Host "[7/7] Resultat"
    Write-Host
    Write-Host "=== COMPARAISON JDT LS ==="
    Write-Host ("Baseline         : {0} fichiers, {1} symboles, {2} relations, {3} ms" -f $summary.baseline.files, $summary.baseline.symbols, $summary.baseline.relations, $summary.baseline.durationMs)
    Write-Host ("Baseline + JDT LS: {0} fichiers, {1} symboles, {2} relations, {3} ms" -f $summary.deepJava.files, $summary.deepJava.symbols, $summary.deepJava.relations, $summary.deepJava.durationMs)
    Write-Host ("Delta            : {0:+#;-#;0} symboles, {1:+#;-#;0} relations, {2:+#;-#;0} ms" -f $summary.delta.symbols, $summary.delta.relations, $summary.delta.durationMs)
    Write-Host ("Precision@{0}      : {1:N4} -> {2:N4} (delta {3:+0.0000;-0.0000;0.0000})" -f $K, $summary.baseline.precisionAtK, $summary.deepJava.precisionAtK, $summary.delta.precisionAtK)
    Write-Host ("Recall@{0}         : {1:N4} -> {2:N4} (delta {3:+0.0000;-0.0000;0.0000})" -f $K, $summary.baseline.recallAtK, $summary.deepJava.recallAtK, $summary.delta.recallAtK)
    Write-Host "Rapport JSON       : $summaryPath"
    Write-Host "========================="
}
finally {
    $env:NEXUS_HOME = $previousNexusHome
    if ($locationPushed) {
        Pop-Location
    }
}
