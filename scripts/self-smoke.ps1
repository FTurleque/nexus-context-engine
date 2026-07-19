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
        [string]$Arguments
    )

    # Windows PowerShell 5.1 converts native stderr redirected with 2>&1 into
    # ErrorRecord objects. With ErrorActionPreference=Stop, harmless Maven/JDK
    # warnings would therefore abort the script even when Maven exits with 0.
    # Temporarily use Continue, then rely exclusively on LASTEXITCODE.
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & mvn -q exec:java "-Dexec.args=$Arguments" 2>&1
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }

    $text = ($output | ForEach-Object { $_.ToString() } | Out-String).TrimEnd()

    if (-not [string]::IsNullOrWhiteSpace($text)) {
        Write-Host $text
    }

    if ($exitCode -ne 0) {
        throw "La CLI NEXUS a echoue avec le code $exitCode pour : $Arguments"
    }

    return $text
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

    Write-Host "[1/7] Compilation de la CLI"
    Invoke-Maven -Arguments @("-q", "-DskipTests", "compile")

    Write-Host "[2/7] Enregistrement du repository NEXUS"
    $registration = Invoke-Nexus -Arguments "project add . $ProjectName"
    if ($registration -notmatch [regex]::Escape($ProjectName)) {
        throw "Le projet '$ProjectName' n'apparait pas dans la sortie de project add."
    }

    Write-Host "[3/7] Verification du registre"
    $projectList = Invoke-Nexus -Arguments "project list"
    if ($projectList -notmatch [regex]::Escape($ProjectName)) {
        throw "Le projet '$ProjectName' n'apparait pas dans project list."
    }

    Write-Host "[4/7] Premiere indexation complete"
    $firstIndex = Invoke-Nexus -Arguments "index $ProjectName"
    # Format CLI: Projet <nom> : <scannes>, <modifies>, <supprimes>, ...
    # Match by comma-separated numeric positions so the assertion remains
    # ASCII-safe under Windows PowerShell 5.1.
    if ($firstIndex -notmatch "Projet\s+.+?:\s+\d+\s+\S+,\s+([1-9]\d*)\s+\S+,\s+\d+\s+\S+,") {
        throw "La premiere indexation devait indexer au moins un fichier modifie."
    }

    Write-Host "[5/7] Deuxieme indexation incrementale"
    $secondIndex = Invoke-Nexus -Arguments "index $ProjectName"
    if ($secondIndex -notmatch "Projet\s+.+?:\s+\d+\s+\S+,\s+0\s+\S+,\s+0\s+\S+,") {
        throw "La deuxieme indexation devait etre idempotente : 0 fichier modifie et 0 fichier supprime."
    }

    Write-Host "[6/7] Inspection de l'index"
    $inspection = Invoke-Nexus -Arguments "inspect $ProjectName"
    if ($inspection -notmatch "\bREADY\b") {
        throw "Le projet devait etre dans l'etat READY apres indexation."
    }
    if ($inspection -notmatch "Index\s*:\s+([1-9]\d*)\s+fichiers,\s+([1-9]\d*)\s+symboles,\s+(\d+)\s+relations") {
        throw "L'inspection devait contenir au moins un fichier et un symbole indexes."
    }

    Write-Host "[7/7] Recherche explicable dans NEXUS"
    $search = Invoke-Nexus -Arguments "search $ProjectName ProjectIndexingService --limit 5 --explain"
    if ($search -notmatch "ProjectIndexingService\.java") {
        throw "La recherche devait retrouver ProjectIndexingService.java."
    }

    Write-Host
    Write-Host "SELF-SMOKE SUCCESS"
    Write-Host "NEXUS a enregistre, indexe, reindexe, inspecte puis recherche dans son propre repository avec succes."
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
