Set-StrictMode -Version Latest

# Fonctions communes des scripts PowerShell du depot. Seul ce module appelle
# Maven : par le Maven Wrapper verifie (mvnw.cmd ou mvnw), obligatoire en CI.

$script:RepositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$script:SelectedMavenByRoot = @{}

function Test-NexusContinuousIntegration {
    return (-not [string]::IsNullOrWhiteSpace($env:CI)) -or (-not [string]::IsNullOrWhiteSpace($env:GITHUB_ACTIONS))
}

function Get-NexusMavenWrapperPath {
    param([Parameter(Mandatory = $true)][string]$RepositoryRoot)

    if ($env:OS -eq 'Windows_NT') {
        return (Join-Path $RepositoryRoot 'mvnw.cmd')
    }
    return (Join-Path $RepositoryRoot 'mvnw')
}

function Select-NexusMavenCommand {
    param([Parameter(Mandatory = $true)][string]$RepositoryRoot)

    $wrapper = Get-NexusMavenWrapperPath -RepositoryRoot $RepositoryRoot
    $wrapperFailure = "Le Maven Wrapper est absent : $wrapper."
    if (Test-Path -LiteralPath $wrapper -PathType Leaf) {
        $previousErrorActionPreference = $ErrorActionPreference
        try {
            # Windows PowerShell 5.1 transforme le stderr natif redirige en
            # erreurs : le code de sortie du wrapper reste la source de verite.
            $ErrorActionPreference = 'Continue'
            $versionOutput = & $wrapper --version 2>&1
            $exitCode = $LASTEXITCODE
        }
        catch {
            $versionOutput = @()
            $exitCode = $null
            $wrapperFailure = "Le Maven Wrapper n'a pas pu telecharger Maven : $($_.Exception.Message)."
        }
        finally {
            $ErrorActionPreference = $previousErrorActionPreference
        }
        $versionOutput | ForEach-Object { Write-Host $_ }
        if ($exitCode -eq 0) {
            return $wrapper
        }
        if ($null -ne $exitCode) {
            $wrapperFailure = "Le Maven Wrapper est indisponible (code $exitCode)."
        }
    }

    # En CI, seul le Maven ancre par le wrapper verifie qualifie le build.
    if (Test-NexusContinuousIntegration) {
        throw "$wrapperFailure La CI (CI ou GITHUB_ACTIONS definie) exige le Maven Wrapper verifie : aucun repli sur le Maven du runner."
    }
    Write-Warning "$wrapperFailure Repli local sur le Maven installe (interdit en CI) : la version Maven n'est pas ancree."

    foreach ($candidate in @('mvn.cmd', 'mvn')) {
        $installed = Get-Command $candidate -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($null -ne $installed -and -not [string]::IsNullOrWhiteSpace($installed.Source)) {
            Write-Host "Maven systeme selectionne : $($installed.Source)"
            & $installed.Source --version 2>&1 | ForEach-Object { Write-Host $_ }
            if ($LASTEXITCODE -eq 0) {
                return $installed.Source
            }
        }
    }

    throw "Impossible d'executer Maven : le wrapper et Maven systeme sont indisponibles."
}

function Invoke-Maven {
    <#
    .SYNOPSIS
    Execute Maven par le wrapper verifie du depot.
    .DESCRIPTION
    En CI (CI ou GITHUB_ACTIONS definie), un wrapper absent ou en echec leve une
    exception, sans repli. Hors CI, le repli sur le Maven installe est averti.
    Un code de sortie Maven non nul leve une exception dont Data['ExitCode']
    porte ce code.
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [string]$RepositoryRoot = $script:RepositoryRoot
    )

    $root = [System.IO.Path]::GetFullPath($RepositoryRoot)
    if (-not $script:SelectedMavenByRoot.ContainsKey($root)) {
        $script:SelectedMavenByRoot[$root] = Select-NexusMavenCommand -RepositoryRoot $root
    }
    $maven = $script:SelectedMavenByRoot[$root]

    & $maven @Arguments
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        $exception = New-Object System.Management.Automation.RuntimeException(
            "La commande Maven a echoue avec le code $exitCode : $maven $($Arguments -join ' ')")
        $exception.Data['ExitCode'] = $exitCode
        throw $exception
    }
}

function Resolve-AbsolutePath {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [string]$BasePath = $script:RepositoryRoot
    )

    if ([System.IO.Path]::IsPathRooted($Path)) {
        return [System.IO.Path]::GetFullPath($Path)
    }
    return [System.IO.Path]::GetFullPath((Join-Path $BasePath $Path))
}

function Invoke-NexusJson {
    <#
    .SYNOPSIS
    Execute la CLI NEXUS et retourne sa sortie JSON desserialisee.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$CliJar,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )

    $stdoutFile = [System.IO.Path]::GetTempFileName()
    $stderrFile = [System.IO.Path]::GetTempFileName()
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        # Windows PowerShell 5.1 transforme parfois les warnings stderr des
        # processus natifs en NativeCommandError visuels. Le code de sortie
        # du processus Java reste la source de verite.
        $ErrorActionPreference = 'Continue'
        & java -jar $CliJar @Arguments 1> $stdoutFile 2> $stderrFile
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

Export-ModuleMember -Function Invoke-Maven, Resolve-AbsolutePath, Invoke-NexusJson
