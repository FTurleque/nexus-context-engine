[CmdletBinding()]
param(
    [string]$Manifest = "scripts\config\iteration-16-java-portfolio.json",
    [string]$Workspace = "target\iteration-16-portfolio",
    [string]$Output = "target\iteration-16-portfolio-baseline.json"
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path

function Resolve-AbsolutePath {
    param([Parameter(Mandatory = $true)][string]$Path)

    if ([System.IO.Path]::IsPathRooted($Path)) {
        return [System.IO.Path]::GetFullPath($Path)
    }
    return [System.IO.Path]::GetFullPath((Join-Path $repoRoot $Path))
}

function Invoke-Git {
    param(
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [switch]$Capture
    )

    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        if ($Capture) {
            $output = & git @Arguments 2>&1
            $code = $LASTEXITCODE
            if ($code -ne 0) {
                throw "La commande Git a echoue avec le code $code : git $($Arguments -join ' ')`n$($output -join [Environment]::NewLine)"
            }
            return (($output | Out-String).Trim())
        }

        & git @Arguments
        $code = $LASTEXITCODE
        if ($code -ne 0) {
            throw "La commande Git a echoue avec le code $code : git $($Arguments -join ' ')"
        }
    }
    finally {
        $ErrorActionPreference = $previousPreference
    }
}

function Normalize-RootKey {
    param([Parameter(Mandatory = $true)][string]$Path)

    return ([System.IO.Path]::GetFullPath($Path).TrimEnd('\', '/')).ToLowerInvariant()
}

function Normalize-RelativePath {
    param([Parameter(Mandatory = $true)][string]$Path)

    return ($Path -replace '\\', '/').TrimStart('/')
}

function New-ControlledCurrentRepositorySnapshot {
    param(
        [Parameter(Mandatory = $true)][string]$RepositoryRoot,
        [Parameter(Mandatory = $true)][string]$WorkspacePath,
        [string[]]$ExcludedPaths = @()
    )

    $snapshotParent = Join-Path $WorkspacePath "current-repository"
    $snapshotPath = Join-Path $snapshotParent "nexus-context-engine"
    $archivePath = Join-Path $WorkspacePath "current-repository.zip"

    if (Test-Path $snapshotParent) {
        Remove-Item -Recurse -Force $snapshotParent
    }
    if (Test-Path $archivePath) {
        Remove-Item -Force $archivePath
    }

    New-Item -ItemType Directory -Force -Path $snapshotPath | Out-Null
    Invoke-Git -Arguments @("-C", $RepositoryRoot, "archive", "--format=zip", "--output=$archivePath", "HEAD")
    Expand-Archive -Path $archivePath -DestinationPath $snapshotPath -Force
    Remove-Item -Force $archivePath

    $snapshotPrefix = [System.IO.Path]::GetFullPath($snapshotPath).TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar
    foreach ($configuredPath in @($ExcludedPaths)) {
        $relativePath = ([string]$configuredPath).Trim()
        if ([string]::IsNullOrWhiteSpace($relativePath)) {
            continue
        }

        $excludedPath = [System.IO.Path]::GetFullPath((Join-Path $snapshotPath $relativePath))
        if (-not $excludedPath.StartsWith($snapshotPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
            throw "Chemin exclu hors du snapshot NEXUS : $relativePath"
        }
        if (Test-Path $excludedPath) {
            Remove-Item -Recurse -Force $excludedPath
        }
    }

    return $snapshotPath
}

try {
    $manifestPath = Resolve-AbsolutePath -Path $Manifest
    $workspacePath = Resolve-AbsolutePath -Path $Workspace
    $outputPath = Resolve-AbsolutePath -Path $Output

    if (-not (Test-Path -Path $manifestPath -PathType Leaf)) {
        throw "Manifest de portefeuille introuvable : $manifestPath"
    }

    $configuration = Get-Content -Raw -Path $manifestPath | ConvertFrom-Json
    if ([string]::IsNullOrWhiteSpace($configuration.name)) {
        throw "Le manifest doit definir un nom de portefeuille."
    }

    $queryDefinitions = @($configuration.queries)
    if ($queryDefinitions.Count -eq 0) {
        throw "Le manifest doit definir au moins une requete."
    }

    $queries = @()
    foreach ($definition in $queryDefinitions) {
        $query = [string]$definition.text
        if ([string]::IsNullOrWhiteSpace($query)) {
            throw "Chaque requete du manifest doit definir un texte non vide."
        }
        if ($queries -notcontains $query.Trim()) {
            $queries += $query.Trim()
        }
    }

    $trackedChanges = Invoke-Git -Arguments @("-C", $repoRoot, "status", "--porcelain", "--untracked-files=no") -Capture
    if (-not [string]::IsNullOrWhiteSpace($trackedChanges)) {
        throw "Le checkout NEXUS contient des modifications suivies. Validez ou annulez-les avant la baseline portefeuille.`n$trackedChanges"
    }

    New-Item -ItemType Directory -Force -Path $workspacePath | Out-Null
    $cloneRoot = Join-Path $workspacePath "repositories"
    New-Item -ItemType Directory -Force -Path $cloneRoot | Out-Null

    $projectRoots = @()
    $sources = @()

    if ($configuration.includeCurrentRepository -eq $true) {
        $currentCommit = Invoke-Git -Arguments @("-C", $repoRoot, "rev-parse", "HEAD") -Capture
        $currentBranch = Invoke-Git -Arguments @("-C", $repoRoot, "branch", "--show-current") -Capture
        $currentRemote = Invoke-Git -Arguments @("-C", $repoRoot, "remote", "get-url", "origin") -Capture
        $currentExcludedPaths = @($configuration.currentRepositoryExcludedPaths)
        $currentRoot = $repoRoot
        $currentSource = "current-checkout"

        if ($currentExcludedPaths.Count -gt 0) {
            Write-Host "Snapshot controle : nexus-context-engine @ $currentCommit"
            $currentRoot = New-ControlledCurrentRepositorySnapshot `
                -RepositoryRoot $repoRoot `
                -WorkspacePath $workspacePath `
                -ExcludedPaths $currentExcludedPaths
            $currentSource = "controlled-current-snapshot"
            Write-Host "Artefacts de benchmark exclus du corpus NEXUS : $($currentExcludedPaths.Count)"
        }

        $projectRoots += $currentRoot
        $sources += [ordered]@{
            name = "nexus-context-engine"
            root = $currentRoot
            url = $currentRemote
            requestedRef = $currentBranch
            resolvedCommit = $currentCommit
            source = $currentSource
            excludedPaths = $currentExcludedPaths
        }
    }

    foreach ($repository in @($configuration.repositories)) {
        $name = [string]$repository.name
        $url = [string]$repository.url
        $ref = [string]$repository.ref
        if ([string]::IsNullOrWhiteSpace($name) -or [string]::IsNullOrWhiteSpace($url) -or [string]::IsNullOrWhiteSpace($ref)) {
            throw "Chaque repository du manifest doit definir name, url et ref."
        }

        $safeName = $name -replace '[^A-Za-z0-9._-]', '-'
        $clonePath = Join-Path $cloneRoot $safeName
        if (-not (Test-Path -Path (Join-Path $clonePath ".git") -PathType Container)) {
            if (Test-Path $clonePath) {
                throw "Le chemin de clone existe mais n'est pas un repository Git : $clonePath"
            }
            Write-Host "Clone controle : $name"
            Invoke-Git -Arguments @("clone", "--no-checkout", $url, $clonePath)
        }

        Invoke-Git -Arguments @("-C", $clonePath, "remote", "set-url", "origin", $url)
        Write-Host "Reference figee : $name @ $ref"
        Invoke-Git -Arguments @("-C", $clonePath, "fetch", "--force", "--depth", "1", "origin", $ref)
        Invoke-Git -Arguments @("-C", $clonePath, "checkout", "--detach", "--force", "FETCH_HEAD")
        Invoke-Git -Arguments @("-C", $clonePath, "clean", "-ffd")

        $resolvedCommit = Invoke-Git -Arguments @("-C", $clonePath, "rev-parse", "HEAD") -Capture
        if ($ref -match '^[0-9a-fA-F]{40}$' -and $resolvedCommit.ToLowerInvariant() -ne $ref.ToLowerInvariant()) {
            throw "La reference resolue pour $name ne correspond pas au commit attendu : $resolvedCommit != $ref"
        }

        $projectRoots += $clonePath
        $sources += [ordered]@{
            name = $name
            root = $clonePath
            url = $url
            requestedRef = $ref
            resolvedCommit = $resolvedCommit
            source = "controlled-clone"
        }
    }

    if ($projectRoots.Count -lt 2) {
        throw "Le portefeuille doit contenir au moins deux repositories."
    }

    $executionManifestPath = Join-Path $workspacePath "resolved-portfolio.json"
    [ordered]@{
        generatedAt = [DateTimeOffset]::UtcNow.ToString("o")
        portfolio = $configuration.name
        sourceManifest = $manifestPath
        queries = $queries
        repositories = $sources
    } | ConvertTo-Json -Depth 10 | Set-Content -Encoding UTF8 -Path $executionManifestPath

    if (Test-Path $outputPath) {
        Remove-Item -Force $outputPath
    }

    Write-Host "============================================================"
    Write-Host " NEXUS - Portefeuille multi-repository Iteration 16"
    Write-Host "============================================================"
    Write-Host "Portefeuille : $($configuration.name)"
    Write-Host "Repositories : $($projectRoots.Count)"
    Write-Host "Requetes     : $($queries.Count)"
    Write-Host "Manifest     : $executionManifestPath"
    Write-Host "Rapport      : $outputPath"
    Write-Host

    & (Join-Path $PSScriptRoot "measure-iteration-16-baseline.ps1") `
        -ProjectRoots $projectRoots `
        -Queries $queries `
        -Output $outputPath

    if (-not (Test-Path $outputPath -PathType Leaf)) {
        throw "La baseline portefeuille n'a pas produit le rapport attendu : $outputPath"
    }

    $baseline = Get-Content -Raw -Path $outputPath | ConvertFrom-Json
    $sourceByRoot = @{}
    foreach ($source in $sources) {
        $sourceByRoot[(Normalize-RootKey -Path $source.root)] = $source.name
    }

    $repositoryByProjectId = @{}
    foreach ($project in @($baseline.projects)) {
        $rootKey = Normalize-RootKey -Path ([string]$project.root)
        if (-not $sourceByRoot.ContainsKey($rootKey)) {
            throw "Impossible de relier le projet baseline a une source du portefeuille : $($project.root)"
        }
        $repositoryByProjectId[[string]$project.projectId] = $sourceByRoot[$rootKey]
    }

    $qualityMetrics = @()
    $precisionValues = @()
    $recallValues = @()
    $hitValues = @()
    $reciprocalRankValues = @()
    foreach ($definition in $queryDefinitions) {
        $query = ([string]$definition.text).Trim()
        $queryMetric = @($baseline.queryMetrics | Where-Object { $_.query -eq $query }) | Select-Object -First 1
        if ($null -eq $queryMetric) {
            throw "Aucune metrique de baseline trouvee pour la requete : $query"
        }

        $rankedIds = @()
        foreach ($hit in @($queryMetric.topResults)) {
            $repositoryName = $repositoryByProjectId[[string]$hit.projectId]
            $relativePath = Normalize-RelativePath -Path ([string]$hit.path)
            $rankedIds += "$repositoryName`:$relativePath"
        }

        $relevantIds = @()
        foreach ($relevant in @($definition.relevant)) {
            $relevantIds += "$([string]$relevant.repository)`:$(Normalize-RelativePath -Path ([string]$relevant.path))"
        }

        $topThree = @($rankedIds | Select-Object -First 3)
        $matches = 0
        $seen = @{}
        foreach ($identity in $topThree) {
            $key = $identity.ToLowerInvariant()
            if (-not $seen.ContainsKey($key)) {
                $seen[$key] = $true
                if (@($relevantIds | Where-Object { $_.ToLowerInvariant() -eq $key }).Count -gt 0) {
                    $matches++
                }
            }
        }

        $firstRelevantRank = 0
        for ($index = 0; $index -lt $topThree.Count; $index++) {
            $key = $topThree[$index].ToLowerInvariant()
            if (@($relevantIds | Where-Object { $_.ToLowerInvariant() -eq $key }).Count -gt 0) {
                $firstRelevantRank = $index + 1
                break
            }
        }

        $precision = $matches / 3.0
        $recall = if ($relevantIds.Count -eq 0) { 1.0 } else { $matches / [double]$relevantIds.Count }
        $hitAt3 = if ($firstRelevantRank -gt 0) { 1.0 } else { 0.0 }
        $reciprocalRankAt3 = if ($firstRelevantRank -gt 0) { 1.0 / [double]$firstRelevantRank } else { 0.0 }

        $precisionValues += $precision
        $recallValues += $recall
        $hitValues += $hitAt3
        $reciprocalRankValues += $reciprocalRankAt3
        $qualityMetrics += [ordered]@{
            query = $query
            relevant = $relevantIds
            rankedTop3 = $topThree
            matches = $matches
            precisionAt3 = $precision
            recallAt3 = $recall
            hitAt3 = $hitAt3
            reciprocalRankAt3 = $reciprocalRankAt3
            firstRelevantRank = $firstRelevantRank
        }
    }

    $meanPrecision = ($precisionValues | Measure-Object -Average).Average
    $meanRecall = ($recallValues | Measure-Object -Average).Average
    $meanHitAt3 = ($hitValues | Measure-Object -Average).Average
    $meanReciprocalRankAt3 = ($reciprocalRankValues | Measure-Object -Average).Average

    $baseline | Add-Member -NotePropertyName portfolio -NotePropertyValue $configuration.name
    $baseline | Add-Member -NotePropertyName resolvedPortfolioManifest -NotePropertyValue $executionManifestPath
    $baseline | Add-Member -NotePropertyName sourceRepositories -NotePropertyValue $sources
    $baseline | Add-Member -NotePropertyName realCorpusQuality -NotePropertyValue ([ordered]@{
        k = 3
        corpusSize = $qualityMetrics.Count
        meanPrecisionAt3 = $meanPrecision
        meanRecallAt3 = $meanRecall
        meanHitAt3 = $meanHitAt3
        meanReciprocalRankAt3 = $meanReciprocalRankAt3
        queries = $qualityMetrics
    })
    $baseline | ConvertTo-Json -Depth 20 | Set-Content -Encoding UTF8 -Path $outputPath

    Write-Host
    Write-Host "=== QUALITE CORPUS REEL ==="
    Write-Host ("precision@3 moyenne : {0:N4}" -f $meanPrecision)
    Write-Host ("recall@3 moyenne    : {0:N4}" -f $meanRecall)
    Write-Host ("hit@3 moyen         : {0:N4}" -f $meanHitAt3)
    Write-Host ("MRR@3 moyen         : {0:N4}" -f $meanReciprocalRankAt3)
    foreach ($metric in $qualityMetrics) {
        Write-Host (" - {0} : p@3={1:N4}, r@3={2:N4}, hit@3={3:N4}, rr@3={4:N4}" -f $metric.query, $metric.precisionAt3, $metric.recallAt3, $metric.hitAt3, $metric.reciprocalRankAt3)
    }
    Write-Host "==========================="
    Write-Host
    Write-Host "Les clones sont confines au workspace de benchmark et les repositories sources ne sont jamais modifies."
}
catch {
    Write-Host
    Write-Host "============================================================"
    Write-Host " PORTEFEUILLE ITERATION 16 INTERROMPU"
    Write-Host "============================================================"
    Write-Host $_.Exception.Message
    Write-Host
    Write-Host "Le terminal reste ouvert. Copiez la sortie depuis l'etape en echec."
}