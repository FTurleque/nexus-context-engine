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

if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
    throw 'JAVA_HOME must point to the Java 21 JDK used to validate NEXUS.'
}

$java21 = Join-Path $env:JAVA_HOME 'bin\java.exe'
if (-not (Test-Path $java21 -PathType Leaf)) {
    throw "JAVA_HOME does not contain java.exe: $java21"
}

$java21Version = (& $java21 -version 2>&1 | Out-String)
if ($java21Version -notmatch 'version "21(?:\.|\")') {
    throw "NEXUS replay requires JAVA_HOME on Java 21. Detected: $java21Version"
}

$java24Version = (& $java24 -version 2>&1 | Out-String)
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
Write-Host "  NEXUS Java : $java21"
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
    mvn `
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
