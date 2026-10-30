[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$validationProject = Join-Path $repoRoot "target\nexus-multilang-validation-project"
$validationHome = Join-Path $repoRoot "target\nexus-multilang-validation-home"
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

function Write-ValidationFile {
    param(
        [Parameter(Mandatory = $true)][string]$RelativePath,
        [Parameter(Mandatory = $true)][string]$Content
    )

    $path = Join-Path $validationProject $RelativePath
    $directory = Split-Path -Parent $path
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    Set-Content -Path $path -Value $Content -Encoding UTF8
}

function Find-ResultPath {
    param(
        [Parameter(Mandatory = $true)][object]$Search,
        [Parameter(Mandatory = $true)][string]$ExpectedPath
    )

    return @($Search.results | ForEach-Object { ([string]$_.path).Replace('\', '/') }) -contains $ExpectedPath
}

try {
    Push-Location $repoRoot
    $locationPushed = $true

    Write-Host "============================================================"
    Write-Host " NEXUS - Validation locale Iteration 10 / Multi-langage"
    Write-Host "============================================================"
    Write-Host

    Write-Host "[1/5] mvn clean install"
    Invoke-Maven -Arguments @("clean", "install")

    Write-Host
    Write-Host "[2/5] Self-smoke du repository NEXUS"
    & (Join-Path $PSScriptRoot "self-smoke.ps1")

    Write-Host
    Write-Host "[3/5] Creation d'un projet polyglotte de validation"
    if (Test-Path $validationProject) {
        Remove-Item -Recurse -Force $validationProject
    }
    if (Test-Path $validationHome) {
        Remove-Item -Recurse -Force $validationHome
    }
    New-Item -ItemType Directory -Path $validationProject -Force | Out-Null
    New-Item -ItemType Directory -Path $validationHome -Force | Out-Null

    Write-ValidationFile -RelativePath "src/main/kotlin/demo/InvoiceService.kt" -Content @'
package demo
class InvoiceService {
    fun loadInvoices(): List<String> = emptyList()
}
'@
    Write-ValidationFile -RelativePath "frontend/src/invoice-dashboard.ts" -Content @'
export function renderInvoiceDashboard(invoices: Invoice[]): string {
    return invoices.map(invoice => invoice.id).join(',');
}
'@
    Write-ValidationFile -RelativePath "frontend/src/invoice-format.js" -Content @'
export function formatInvoiceNumber(id) {
    return `INV-${id}`;
}
'@
    Write-ValidationFile -RelativePath "python/invoice_pipeline.py" -Content @'
def reconcile_invoice_payments(invoices):
    return [invoice for invoice in invoices if invoice.is_paid]
'@
    Write-ValidationFile -RelativePath "db/invoice_report.sql" -Content @'
select invoice_id, paid_at
from invoice_payment
where paid_at is not null;
'@
    Write-ValidationFile -RelativePath "legacy/ignored.rb" -Content "puts 'ignored'"

    Write-Host
    Write-Host "[4/5] Indexation, recherche et contexte polyglottes"
    Invoke-Maven -Arguments @("-q", "-DskipTests", "package")
    $script:cliJar = Get-ChildItem -Path (Join-Path $repoRoot "target") -Filter "nexus-context-engine-*-cli.jar" -File |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if ($null -eq $script:cliJar) {
        throw "Le JAR CLI NEXUS est introuvable apres le build."
    }

    $env:NEXUS_HOME = $validationHome
    $projectName = "nexus-multilang-validation"
    $null = Invoke-NexusJson -Arguments @("project", "add", $validationProject, $projectName, "--json")
    $index = Invoke-NexusJson -Arguments @("index", $projectName, "--json")
    $inspect = Invoke-NexusJson -Arguments @("inspect", $projectName, "--json")
    $projects = Invoke-NexusJson -Arguments @("project", "list", "--json")
    $project = @($projects.projects | Where-Object { $_.name -eq $projectName }) | Select-Object -First 1
    if ($null -eq $project) {
        throw "Le projet polyglotte n'est pas present dans le registre NEXUS."
    }

    $pythonSearch = Invoke-NexusJson -Arguments @("search", $projectName, "reconcile invoice payments", "--limit", "5", "--json")
    $typescriptSearch = Invoke-NexusJson -Arguments @("search", $projectName, "render invoice dashboard", "--limit", "5", "--json")
    $sqlSearch = Invoke-NexusJson -Arguments @("search", $projectName, "invoice payment paid at", "--limit", "5", "--json")
    $context = Invoke-NexusJson -Arguments @("context", $projectName, "reconcile invoice payments", "--budget", "400", "--json")

    $expectedLanguages = @("javascript", "kotlin", "python", "sql", "typescript")
    $actualLanguages = @($project.languages | Sort-Object)
    if (($actualLanguages -join ',') -ne ($expectedLanguages -join ',')) {
        throw "Langages detectes inattendus : $($actualLanguages -join ', ')"
    }
    if ([int64]$inspect.index.files -ne 5) {
        throw "Le projet polyglotte devait contenir 5 fichiers indexes, valeur obtenue : $($inspect.index.files)"
    }
    if (-not (Find-ResultPath -Search $pythonSearch -ExpectedPath "python/invoice_pipeline.py")) {
        throw "Le fichier Python attendu n'apparait pas dans les resultats de recherche."
    }
    if (-not (Find-ResultPath -Search $typescriptSearch -ExpectedPath "frontend/src/invoice-dashboard.ts")) {
        throw "Le fichier TypeScript attendu n'apparait pas dans les resultats de recherche."
    }
    if (-not (Find-ResultPath -Search $sqlSearch -ExpectedPath "db/invoice_report.sql")) {
        throw "Le fichier SQL attendu n'apparait pas dans les resultats de recherche."
    }
    if ([int]$context.estimatedTokens -gt [int]$context.tokenBudget) {
        throw "Le ContextBundle depasse son budget de tokens."
    }
    $contextPaths = @($context.items | ForEach-Object { ([string]$_.path).Replace('\', '/') })
    if ($contextPaths -notcontains "python/invoice_pipeline.py") {
        throw "Le contexte polyglotte ne contient pas le fichier Python attendu."
    }

    Write-Host
    Write-Host "[5/5] Resultat"
    Write-Host
    Write-Host "=== VALIDATION MULTI-LANGAGE ==="
    Write-Host ("Fichiers indexes : {0}" -f $inspect.index.files)
    Write-Host ("Langages         : {0}" -f ($actualLanguages -join ', '))
    Write-Host ("Symboles         : {0}" -f $inspect.index.symbols)
    Write-Host ("Relations        : {0}" -f $inspect.index.relations)
    Write-Host ("Indexation       : {0} ms" -f $index.report.durationMs)
    Write-Host ("Python trouve    : {0}" -f (Find-ResultPath -Search $pythonSearch -ExpectedPath "python/invoice_pipeline.py"))
    Write-Host ("TypeScript trouve: {0}" -f (Find-ResultPath -Search $typescriptSearch -ExpectedPath "frontend/src/invoice-dashboard.ts"))
    Write-Host ("SQL trouve       : {0}" -f (Find-ResultPath -Search $sqlSearch -ExpectedPath "db/invoice_report.sql"))
    Write-Host ("Contexte         : {0}/{1} tokens" -f $context.estimatedTokens, $context.tokenBudget)
    Write-Host "================================"
    Write-Host
    Write-Host "VALIDATION ITERATION 10 TERMINEE"
}
catch {
    Write-Host
    Write-Host "============================================================"
    Write-Host " VALIDATION ITERATION 10 INTERROMPUE"
    Write-Host "============================================================"
    Write-Host $_.Exception.Message
    Write-Host
    Write-Host "Le terminal reste ouvert. Copiez la sortie depuis l'etape en echec."
}
finally {
    $env:NEXUS_HOME = $previousNexusHome
    if ($locationPushed) {
        Pop-Location
    }
}
