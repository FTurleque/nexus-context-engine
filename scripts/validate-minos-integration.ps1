param(
    [Parameter(Mandatory = $true)]
    [string]$MinosJar,

    [Parameter(Mandatory = $true)]
    [string]$Java24,

    [Parameter(Mandatory = $true)]
    [string]$Fixture
)

$ErrorActionPreference = 'Stop'

$jar = (Resolve-Path $MinosJar).Path
$java24 = (Resolve-Path $Java24).Path
$fixturePath = (Resolve-Path $Fixture).Path
$mavenWrapper = (Resolve-Path (Join-Path $PSScriptRoot '..\mvnw.cmd')).Path

if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
    throw 'JAVA_HOME must point to a JDK 21 or newer used to validate NEXUS.'
}

$nexusJava = Join-Path $env:JAVA_HOME 'bin\java.exe'
if (-not (Test-Path $nexusJava -PathType Leaf)) {
    throw "JAVA_HOME does not contain java.exe: $nexusJava"
}

function Get-JavaVersionText {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Executable
    )

    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = $Executable
    $startInfo.Arguments = '-version'
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.CreateNoWindow = $true

    $process = [System.Diagnostics.Process]::Start($startInfo)
    try {
        $stdout = $process.StandardOutput.ReadToEnd()
        $stderr = $process.StandardError.ReadToEnd()
        $process.WaitForExit()

        if ($process.ExitCode -ne 0) {
            throw "Java version probe failed with exit code $($process.ExitCode): $Executable"
        }

        return (($stderr + [Environment]::NewLine + $stdout).Trim())
    }
    finally {
        $process.Dispose()
    }
}

function Get-JavaMajorVersion {
    param(
        [Parameter(Mandatory = $true)]
        [string]$VersionText
    )

    if ($VersionText -notmatch 'version\s+"(?<version>[0-9]+)(?:\.[^"]*)?"') {
        return $null
    }
    return [int]$Matches['version']
}

$nexusJavaVersion = Get-JavaVersionText -Executable $nexusJava
$nexusJavaMajor = Get-JavaMajorVersion -VersionText $nexusJavaVersion
if ($null -eq $nexusJavaMajor -or $nexusJavaMajor -lt 21) {
    throw "NEXUS replay requires JAVA_HOME on Java 21 or newer. Detected: $nexusJavaVersion"
}

$java24Version = Get-JavaVersionText -Executable $java24
if ($java24Version -notmatch 'version "24(?:\.|\")') {
    throw "The -Java24 executable must be Java 24. Detected: $java24Version"
}

$replayRoot = Join-Path (Get-Location) 'target\m13-replay'
$nexusHome = Join-Path $replayRoot 'nexus-home'
$replayFixture = Join-Path $replayRoot 'fixture'
$minosHome = Join-Path $replayRoot 'minos-home'
$exportJson = Join-Path $replayRoot 'minos-export.json'

if (Test-Path $replayRoot) {
    Remove-Item -Recurse -Force $replayRoot
}
New-Item -ItemType Directory -Force -Path $minosHome | Out-Null
New-Item -ItemType Directory -Force -Path $nexusHome | Out-Null
Copy-Item -Recurse -Force $fixturePath $replayFixture

$scip = Join-Path $replayFixture '.minos-m0\scip-typescript\index.scip'
if (-not (Test-Path $scip -PathType Leaf)) {
    throw "Missing SCIP fixture in replay sandbox: $scip"
}

Write-Host 'NEXUS MINOS integration replay'
Write-Host "  NEXUS Java : $nexusJava"
Write-Host "  MINOS Java : $java24"
Write-Host "  MINOS JAR  : $jar"
Write-Host "  NEXUS_HOME : $nexusHome"
Write-Host "  Fixture    : $replayFixture"

function Invoke-Minos {
    param([string[]]$Arguments)

    $output = & $java24 "-Dminos.home=$minosHome" -jar $jar @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "MINOS command failed with exit code $LASTEXITCODE"
    }
    return $output
}

try {
    Invoke-Minos @('project', 'add', $replayFixture, '--name', 'm13-fixture') | Out-Null
    Invoke-Minos @(
        'index', 'm13-fixture',
        '--scip', $scip,
        '--provider', 'scip-typescript',
        '--provider-version', '0.4.0'
    ) | Out-Null

    $export = Invoke-Minos @('nexus-export', '--root', $replayFixture)
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText(
        $exportJson,
        ($export -join [Environment]::NewLine),
        $utf8NoBom)

    $env:NEXUS_HOME = $nexusHome
    & $mavenWrapper `
        '-Dtest=MinosRealIntegrationTest' `
        '-Dnexus.minos.integration.replay=true' `
        test

    if ($LASTEXITCODE -ne 0) {
        throw "MINOS -> NEXUS replay failed with exit code $LASTEXITCODE"
    }

    Write-Host 'M13 MINOS -> NEXUS replay SUCCESS'
}
finally {
    Remove-Item Env:NEXUS_HOME -ErrorAction SilentlyContinue
    if (Test-Path $replayRoot) {
        Remove-Item -Recurse -Force $replayRoot
    }
}
