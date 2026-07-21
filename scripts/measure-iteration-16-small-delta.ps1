[CmdletBinding()]
param(
    [string]$Manifest = "scripts\config\iteration-16-java-portfolio.json",
    [string]$RepositoryName = "collection-manager",
    [string]$ModifiedPath = "infra/src/main/java/com/collectionmanager/infra/migration/FlywayMigrator.java",
    [string]$AddedPath = "infra/src/main/java/com/collectionmanager/infra/migration/NexusIteration16DeltaProbe.java",
    [string]$Workspace = "target\iteration-16-small-delta",
    [string]$Output = "target\iteration-16-small-delta-baseline.json"
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

function Invoke-Maven {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)

    & mvn @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "La commande Maven a echoue avec le code $LASTEXITCODE : mvn $($Arguments -join ' ')"
    }
}

try {
    Push-Location $repoRoot
    $locationPushed = $true

    $manifestPath = Resolve-AbsolutePath -Path $Manifest
    $workspacePath = Resolve-AbsolutePath -Path $Workspace
    $outputPath = Resolve-AbsolutePath -Path $Output

    if (-not (Test-Path -Path $manifestPath -PathType Leaf)) {
        throw "Manifest de portefeuille introuvable : $manifestPath"
    }

    $trackedChanges = Invoke-Git -Arguments @("-C", $repoRoot, "status", "--porcelain", "--untracked-files=no") -Capture
    if (-not [string]::IsNullOrWhiteSpace($trackedChanges)) {
        throw "Le checkout NEXUS contient des modifications suivies. Validez ou annulez-les avant la baseline petit delta.`n$trackedChanges"
    }

    $configuration = Get-Content -Raw -Path $manifestPath | ConvertFrom-Json
    $repository = @($configuration.repositories | Where-Object { $_.name -eq $RepositoryName }) | Select-Object -First 1
    if ($null -eq $repository) {
        throw "Repository '$RepositoryName' absent du manifest : $manifestPath"
    }

    $url = [string]$repository.url
    $ref = [string]$repository.ref
    if ([string]::IsNullOrWhiteSpace($url) -or [string]::IsNullOrWhiteSpace($ref)) {
        throw "Le repository '$RepositoryName' doit definir url et ref dans le manifest."
    }

    New-Item -ItemType Directory -Force -Path $workspacePath | Out-Null
    $clonePath = Join-Path $workspacePath "repository"

    if (-not (Test-Path -Path (Join-Path $clonePath ".git") -PathType Container)) {
        if (Test-Path $clonePath) {
            throw "Le chemin de clone existe mais n'est pas un repository Git : $clonePath"
        }
        Write-Host "Clone controle : $RepositoryName"
        Invoke-Git -Arguments @("clone", "--no-checkout", $url, $clonePath)
    }

    Invoke-Git -Arguments @("-C", $clonePath, "remote", "set-url", "origin", $url)
    Write-Host "Reference figee : $RepositoryName @ $ref"
    Invoke-Git -Arguments @("-C", $clonePath, "fetch", "--force", "--depth", "1", "origin", $ref)
    Invoke-Git -Arguments @("-C", $clonePath, "checkout", "--detach", "--force", "FETCH_HEAD")
    Invoke-Git -Arguments @("-C", $clonePath, "clean", "-ffd")

    $resolvedCommit = Invoke-Git -Arguments @("-C", $clonePath, "rev-parse", "HEAD") -Capture
    if ($ref -match '^[0-9a-fA-F]{40}$' -and $resolvedCommit.ToLowerInvariant() -ne $ref.ToLowerInvariant()) {
        throw "La reference resolue ne correspond pas au commit attendu : $resolvedCommit != $ref"
    }

    if (Test-Path $outputPath) {
        Remove-Item -Force $outputPath
    }

    Write-Host "============================================================"
    Write-Host " NEXUS - Baseline incrementale petit delta / Iteration 16"
    Write-Host "============================================================"
    Write-Host "Repository   : $RepositoryName"
    Write-Host "Reference    : $resolvedCommit"
    Write-Host "Source clone : $clonePath"
    Write-Host "Fichier mod. : $ModifiedPath"
    Write-Host "Fichier ajout: $AddedPath"
    Write-Host "Rapport      : $outputPath"
    Write-Host

    Invoke-Maven -Arguments @(
        "-Dtest=SmallDeltaIndexingBaselineTest",
        "-Dnexus.delta.source=$clonePath",
        "-Dnexus.delta.modified=$ModifiedPath",
        "-Dnexus.delta.added=$AddedPath",
        "-Dnexus.delta.output=$outputPath",
        "test"
    )

    if (-not (Test-Path -Path $outputPath -PathType Leaf)) {
        throw "La baseline petit delta n'a pas produit le rapport attendu : $outputPath"
    }

    $baseline = Get-Content -Raw -Path $outputPath | ConvertFrom-Json
    $fullMs = [double]$baseline.fullIndex.durationMs
    $noChangeMs = [double]$baseline.incrementalNoChange.durationMs
    $deltaMs = [double]$baseline.incrementalSmallDelta.durationMs
    $rollbackMs = [double]$baseline.rollback.durationMs

    $baseline | Add-Member -NotePropertyName sourceRepository -NotePropertyValue ([ordered]@{
        name = $RepositoryName
        url = $url
        requestedRef = $ref
        resolvedCommit = $resolvedCommit
    })
    $baseline | Add-Member -NotePropertyName ratios -NotePropertyValue ([ordered]@{
        deltaToFull = if ($fullMs -gt 0) { $deltaMs / $fullMs } else { 0.0 }
        fullToDeltaSpeedup = if ($deltaMs -gt 0) { $fullMs / $deltaMs } else { 0.0 }
        noChangeToFull = if ($fullMs -gt 0) { $noChangeMs / $fullMs } else { 0.0 }
        rollbackToFull = if ($fullMs -gt 0) { $rollbackMs / $fullMs } else { 0.0 }
    })
    $baseline | ConvertTo-Json -Depth 20 | Set-Content -Encoding UTF8 -Path $outputPath

    Write-Host
    Write-Host "=== RESULTAT PETIT DELTA ==="
    Write-Host ("Indexation complete     : {0:N0} ms" -f $fullMs)
    Write-Host ("Incremental sans change : {0:N0} ms" -f $noChangeMs)
    Write-Host ("Incremental petit delta : {0:N0} ms" -f $deltaMs)
    Write-Host ("Rollback incremental    : {0:N0} ms" -f $rollbackMs)
    Write-Host ("Fichiers modifies delta : {0}" -f $baseline.incrementalSmallDelta.changedFiles)
    Write-Host ("Fichiers supprimes delta: {0}" -f $baseline.incrementalSmallDelta.removedFiles)
    if ($deltaMs -gt 0) {
        Write-Host ("Acceleration full/delta : {0:N2}x" -f ($fullMs / $deltaMs))
    }
    Write-Host ("Probe recherche trouvee : {0}" -f $baseline.probeSearchFound)
    Write-Host ("Rollback purge validee  : {0}" -f $baseline.rollbackRemovedPathAbsent)
    Write-Host "============================"
    Write-Host
    Write-Host "Le repository source et le clone Git restent inchanges : le delta est applique uniquement a la copie temporaire JUnit."
}
catch {
    Write-Host
    Write-Host "============================================================"
    Write-Host " BASELINE PETIT DELTA ITERATION 16 INTERROMPUE"
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
