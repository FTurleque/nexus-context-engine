[CmdletBinding()]
param(
    [string]$ProjectName = "nexus-context-engine-self-smoke",
    [switch]$KeepData
)

$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$smokeHome = Join-Path $repoRoot "target\nexus-self-smoke-home"
$previousNexusHome = $env:NEXUS_HOME
$locationPushed = $false
$script:cliJar = $null

function Invoke-Maven {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments
    )

    & mvn @Arguments
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        throw "La commande Maven a echoue avec le code $exitCode : mvn $($Arguments -join ' ')"
    }
}

function Invoke-Nexus {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments
    )

    if ($null -eq $script:cliJar) {
        throw "Le JAR CLI NEXUS n'a pas ete initialise."
    }

    $stdoutFile = [System.IO.Path]::GetTempFileName()
    $stderrFile = [System.IO.Path]::GetTempFileName()
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        # Windows PowerShell 5.1 transforme les lignes stderr des processus natifs
        # en NativeCommandError lorsque ErrorActionPreference vaut Stop, meme si
        # stderr est redirige vers un fichier. Les warnings SLF4J/JDK sont alors
        # susceptibles d'interrompre le script avant la lecture de LASTEXITCODE.
        # Pendant l'appel Java uniquement, on repasse donc a Continue puis on se
        # fie au vrai code de sortie du processus pour decider du succes.
        $ErrorActionPreference = "Continue"
        & java -jar $script:cliJar.FullName @Arguments 1> $stdoutFile 2> $stderrFile
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }

    try {
        $stdout = (Get-Content -Raw -Path $stdoutFile -ErrorAction SilentlyContinue)
        $stderr = (Get-Content -Raw -Path $stderrFile -ErrorAction SilentlyContinue)

        if (-not [string]::IsNullOrWhiteSpace($stderr)) {
            Write-Host $stderr.TrimEnd()
        }
        if (-not [string]::IsNullOrWhiteSpace($stdout)) {
            Write-Host $stdout.TrimEnd()
        }

        if ($exitCode -ne 0) {
            throw "La CLI NEXUS a echoue avec le code $exitCode pour : $($Arguments -join ' ')"
        }

        return $stdout.TrimEnd()
    }
    finally {
        Remove-Item -Force $stdoutFile, $stderrFile -ErrorAction SilentlyContinue
    }
}

try {
    Push-Location $repoRoot
    $locationPushed = $true

    if (Test-Path $smokeHome) {
        Remove-Item -Recurse -Force $smokeHome
    }
    New-Item -ItemType Directory -Path $smokeHome -Force | Out-Null
    $env:NEXUS_HOME = $smokeHome

    Write-Host "=== NEXUS self-smoke ==="
    Write-Host "Repository : $repoRoot"
    Write-Host "NEXUS_HOME : $smokeHome"
    Write-Host

    Write-Host "[1/13] Construction du JAR CLI autonome"
    Invoke-Maven -Arguments @("-q", "-DskipTests", "package")
    $script:cliJar = Get-ChildItem -Path (Join-Path $repoRoot "target") -Filter "nexus-context-engine-*-cli.jar" -File |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if ($null -eq $script:cliJar) {
        throw "Le build devait produire un JAR nexus-context-engine-*-cli.jar."
    }
    Write-Host "JAR CLI : $($script:cliJar.FullName)"

    Write-Host "[2/13] Validation du point d'entree autonome"
    $versionJson = Invoke-Nexus -Arguments @("--version", "--json")
    $version = $versionJson | ConvertFrom-Json
    if ($version.command -ne "version" -or [string]::IsNullOrWhiteSpace($version.version)) {
        throw "Le JAR autonome devait exposer une version JSON valide."
    }

    Write-Host "[3/13] Enregistrement du repository NEXUS en JSON"
    $registrationJson = Invoke-Nexus -Arguments @("project", "add", ".", $ProjectName, "--json")
    $registration = $registrationJson | ConvertFrom-Json
    if ($registration.project.name -ne $ProjectName) {
        throw "Le projet '$ProjectName' n'apparait pas dans la sortie JSON de project add."
    }

    Write-Host "[4/13] Verification du registre en JSON"
    $projectListJson = Invoke-Nexus -Arguments @("project", "list", "--json")
    $projectList = $projectListJson | ConvertFrom-Json
    $registeredProject = $projectList.projects | Where-Object { $_.name -eq $ProjectName } | Select-Object -First 1
    if ($null -eq $registeredProject) {
        throw "Le projet '$ProjectName' n'apparait pas dans project list --json."
    }

    Write-Host "[5/13] Premiere indexation complete en JSON"
    $firstIndexJson = Invoke-Nexus -Arguments @("index", $ProjectName, "--json")
    $firstIndex = $firstIndexJson | ConvertFrom-Json
    if ([int]$firstIndex.report.changedFiles -le 0) {
        throw "La premiere indexation devait indexer au moins un fichier modifie."
    }
    if ($firstIndex.project.indexStatus -ne "READY") {
        throw "Le projet devait etre READY apres la premiere indexation."
    }
    if ($firstIndex.project.languages -notcontains "markdown") {
        throw "L'indexation devait declarer le langage markdown apres l'Iteration 5."
    }

    Write-Host "[6/13] Deuxieme indexation incrementale en JSON"
    $secondIndexJson = Invoke-Nexus -Arguments @("index", $ProjectName, "--json")
    $secondIndex = $secondIndexJson | ConvertFrom-Json
    if ([int]$secondIndex.report.changedFiles -ne 0 -or [int]$secondIndex.report.removedFiles -ne 0) {
        throw "La deuxieme indexation devait etre idempotente : 0 fichier modifie et 0 fichier supprime."
    }

    Write-Host "[7/13] Inspection de l'index en JSON"
    $inspectionJson = Invoke-Nexus -Arguments @("inspect", $ProjectName, "--json")
    $inspection = $inspectionJson | ConvertFrom-Json
    if ($inspection.project.indexStatus -ne "READY") {
        throw "Le projet devait etre dans l'etat READY apres indexation."
    }
    if ([int]$inspection.index.files -le 0 -or [int]$inspection.index.symbols -le 0) {
        throw "L'inspection devait contenir au moins un fichier et un symbole indexes."
    }

    Write-Host "[8/13] Recherche explicable en JSON"
    $searchJson = Invoke-Nexus -Arguments @("search", $ProjectName, "ProjectIndexingService", "--limit", "5", "--explain", "--json")
    $search = $searchJson | ConvertFrom-Json
    $searchHit = $search.results | Where-Object { $_.path -match "ProjectIndexingService\.java$" } | Select-Object -First 1
    if ($null -eq $searchHit) {
        throw "La recherche JSON devait retrouver ProjectIndexingService.java."
    }

    Write-Host "[9/13] Construction d'un ContextBundle strict sous budget avec instructions natives"
    $contextBudget = 180
    $contextJson = Invoke-Nexus -Arguments @("context", $ProjectName, "ProjectIndexingService", "--budget", "$contextBudget", "--explain", "--json")
    $context = $contextJson | ConvertFrom-Json
    if ([int]$context.estimatedTokens -gt $contextBudget) {
        throw "Le ContextBundle a depasse le budget : $($context.estimatedTokens) > $contextBudget."
    }
    if ($context.items.Count -le 0) {
        throw "Le ContextBundle devait contenir au moins un item."
    }
    $contextHit = $context.items | Where-Object { $_.path -match "ProjectIndexingService\.java$" } | Select-Object -First 1
    if ($null -eq $contextHit) {
        throw "Le contexte JSON devait contenir un fragment de ProjectIndexingService.java."
    }
    $instructionHit = $context.items | Where-Object { $_.type -eq "INSTRUCTION" -and $_.path -eq "AGENTS.md" } | Select-Object -First 1
    if ($null -eq $instructionHit) {
        throw "Le contexte devait reutiliser le AGENTS.md natif du repository."
    }
    if ([int]$context.metadata.nativeSourcesDiscovered -le 0) {
        throw "Les metadonnees devaient indiquer au moins une source native decouverte."
    }
    if ([bool]$context.metadata.gitEnabled) {
        throw "Le contexte Git devait rester desactive sous un budget global de 500 tokens."
    }

    Write-Host "[10/13] Construction d'un contexte multi-source code documentation instructions"
    $multiSourceBudget = 1200
    $multiSourceJson = Invoke-Nexus -Arguments @("context", $ProjectName, "ProjectIndexingService indexation architecture", "--budget", "$multiSourceBudget", "--explain", "--json")
    $multiSource = $multiSourceJson | ConvertFrom-Json
    if ([int]$multiSource.estimatedTokens -gt $multiSourceBudget) {
        throw "Le contexte multi-source a depasse son budget."
    }
    if ($null -eq ($multiSource.items | Where-Object { $_.type -eq "INSTRUCTION" } | Select-Object -First 1)) {
        throw "Le contexte multi-source devait contenir une instruction native."
    }
    if ($null -eq ($multiSource.items | Where-Object { $_.type -eq "DOCUMENTATION" } | Select-Object -First 1)) {
        throw "Le contexte multi-source devait contenir de la documentation Markdown."
    }
    if ($null -eq ($multiSource.items | Where-Object { $_.path -match "ProjectIndexingService\.java$" } | Select-Object -First 1)) {
        throw "Le contexte multi-source devait conserver le code ProjectIndexingService.java."
    }

    Write-Host "[11/13] Activation progressive d'un Agent Skill"
    $skillContextBudget = 1200
    $skillContextJson = Invoke-Nexus -Arguments @("context", $ProjectName, "validate NEXUS context quality progressive disclosure", "--budget", "$skillContextBudget", "--explain", "--json")
    $skillContext = $skillContextJson | ConvertFrom-Json
    if ([int]$skillContext.estimatedTokens -gt $skillContextBudget) {
        throw "Le contexte avec skill a depasse son budget."
    }
    $skillHit = $skillContext.items | Where-Object { $_.type -eq "SKILL" -and $_.path -match "nexus-context-validation/SKILL\.md$" } | Select-Object -First 1
    if ($null -eq $skillHit) {
        throw "Le skill nexus-context-validation devait etre active pour la requete de validation."
    }
    if ($skillHit.content -notmatch "NEXUS_SKILL_VALIDATION_WORKFLOW") {
        throw "Le SKILL.md complet devait etre charge apres selection."
    }
    if ($null -ne ($skillContext.items | Where-Object { $_.path -match "quality-checks\.md$" } | Select-Object -First 1)) {
        throw "La reference quality-checks.md ne devait pas etre chargee automatiquement."
    }
    if ([int]$skillContext.metadata.skillsDiscovered -le 0 -or [int]$skillContext.metadata.skillSelectedItems -le 0) {
        throw "Les metadonnees devaient confirmer la decouverte et la selection d'un skill."
    }
    if ([int]$skillContext.metadata.skillResourcesDiscovered -le 0) {
        throw "Les ressources du skill devaient etre inventoriees."
    }
    if ([bool]$skillContext.metadata.skillsExecuted) {
        throw "NEXUS ne doit jamais executer les skills ou leurs scripts."
    }

    Write-Host "[12/13] Construction d'un contexte Git local borne et explicable"
    $gitContextBudget = 1600
    $gitContextJson = Invoke-Nexus -Arguments @("context", $ProjectName, "DefaultContextBuilder git context budget recent changes", "--budget", "$gitContextBudget", "--explain", "--json")
    $gitContext = $gitContextJson | ConvertFrom-Json
    if ([int]$gitContext.estimatedTokens -gt $gitContextBudget) {
        throw "Le contexte Git a depasse son budget global."
    }
    if (-not [bool]$gitContext.metadata.gitEnabled) {
        throw "Le contexte Git devait etre active avec un budget superieur a 500 tokens."
    }
    if (-not [bool]$gitContext.metadata.gitRepositoryAvailable) {
        throw "Le repository NEXUS devait etre detecte comme repository Git local."
    }
    if ([int]$gitContext.metadata.gitRelatedCommits -le 0) {
        throw "Le contexte Git devait retrouver au moins un commit lie aux chemins candidats."
    }
    if ([int]$gitContext.metadata.gitSelectedItems -le 0) {
        throw "Le contexte Git devait selectionner au moins un fragment Git."
    }
    $gitHit = $gitContext.items | Where-Object { $_.type -eq "GIT" } | Select-Object -First 1
    if ($null -eq $gitHit) {
        throw "Le ContextBundle devait contenir au moins un item GIT."
    }

    Write-Host "[13/13] Validation de la sortie humaine par defaut"
    $humanSearch = Invoke-Nexus -Arguments @("search", $ProjectName, "ProjectIndexingService", "--limit", "3")
    if ($humanSearch -notmatch "Recherche\s+'ProjectIndexingService'" -or $humanSearch -notmatch "ProjectIndexingService\.java") {
        throw "La sortie humaine devait rester disponible sans --json."
    }

    Write-Host
    Write-Host "=== METRICS ==="
    Write-Host "Indexation complete : $($firstIndex.report.durationMs) ms"
    Write-Host "Indexation incrementale : $($secondIndex.report.durationMs) ms"
    Write-Host "Recherche : $($search.durationMs) ms"
    Write-Host "Construction contexte strict : $($context.durationMs) ms"
    Write-Host "Contexte strict : $($context.items.Count) item(s), $($context.estimatedTokens)/$($context.tokenBudget) tokens"
    Write-Host "Construction contexte multi-source : $($multiSource.durationMs) ms"
    Write-Host "Contexte multi-source : $($multiSource.items.Count) item(s), $($multiSource.estimatedTokens)/$($multiSource.tokenBudget) tokens"
    Write-Host "Construction contexte avec skill : $($skillContext.durationMs) ms"
    Write-Host "Skills : $($skillContext.metadata.skillsDiscovered) decouvert(s), $($skillContext.metadata.skillSelectedItems) selectionne(s), $($skillContext.metadata.skillResourcesDiscovered) ressource(s) inventoriee(s)"
    Write-Host "Construction contexte Git : $($gitContext.durationMs) ms"
    Write-Host "Git : $($gitContext.metadata.gitCommitsInspected) commit(s) inspecte(s), $($gitContext.metadata.gitRelatedCommits) lie(s), $($gitContext.metadata.gitSelectedItems) fragment(s) selectionne(s)"
    if ($null -ne $context.metadata.reductionRatio) {
        $reductionPercent = [Math]::Round(([double]$context.metadata.reductionRatio * 100.0), 2)
        Write-Host "Reduction du contexte candidat strict : $reductionPercent %"
    }

    Write-Host
    Write-Host "SELF-SMOKE SUCCESS"
    Write-Host "NEXUS a valide son JAR autonome, ses instructions natives, sa documentation Markdown, ses Agent Skills, sa divulgation progressive et son contexte Git local avec succes."
}
finally {
    if ($locationPushed) {
        Pop-Location
    }

    if ($null -eq $previousNexusHome) {
        Remove-Item Env:NEXUS_HOME -ErrorAction SilentlyContinue
    }
    else {
        $env:NEXUS_HOME = $previousNexusHome
    }

    if (-not $KeepData -and (Test-Path $smokeHome)) {
        Remove-Item -Recurse -Force $smokeHome
    }
    elseif ($KeepData) {
        Write-Host "Donnees de smoke test conservees dans : $smokeHome"
    }
}
