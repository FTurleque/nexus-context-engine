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
$scip = Join-Path $fixturePath '.minos-m0\scip-typescript\index.scip'

if (-not (Test-Path $scip -PathType Leaf)) {
    throw "Missing SCIP fixture: $scip"
}

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

$nexusHome = Join-Path ([System.IO.Path]::GetTempPath()) ("nexus-minos-m13-" + [guid]::NewGuid())
$integrationDir = Join-Path $nexusHome 'integrations\minos'
$minosHome = Join-Path $integrationDir 'home'
$installedJar = Join-Path $integrationDir 'minos-code-intelligence-all.jar'

New-Item -ItemType Directory -Force -Path $minosHome | Out-Null
Copy-Item -Force $jar $installedJar

Write-Host 'NEXUS MINOS integration replay'
Write-Host "  NEXUS Java : $java21"
Write-Host "  MINOS Java : $java24"
Write-Host "  MINOS JAR  : $installedJar"
Write-Host "  NEXUS_HOME : $nexusHome"
Write-Host "  Fixture    : $fixturePath"

function Invoke-Minos {
    param([string[]]$Arguments)

    & $java24 "-Dminos.home=$minosHome" -jar $installedJar @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "MINOS fixture setup failed with exit code $LASTEXITCODE"
    }
}

$previousPath = $env:PATH
try {
    Invoke-Minos @('project', 'add', $fixturePath, '--name', 'm13-fixture')
    Invoke-Minos @(
        'index', 'm13-fixture',
        '--scip', $scip,
        '--provider', 'scip-typescript',
        '--provider-version', '0.4.0'
    )

    # Maven remains on Java 21 through JAVA_HOME. The MINOS child process launched
    # by NEXUS resolves the fixed command `java` from PATH, so prepend Java 24 here.
    $env:PATH = "$(Split-Path -Parent $java24);$previousPath"

    mvn `
        '-Dtest=MinosRealIntegrationTest' `
        "-Dnexus.minos.integration.home=$nexusHome" `
        "-Dnexus.minos.integration.fixture=$fixturePath" `
        test

    if ($LASTEXITCODE -ne 0) {
        throw "MINOS -> NEXUS replay failed with exit code $LASTEXITCODE"
    }

    Write-Host 'M13 MINOS -> NEXUS replay SUCCESS'
}
finally {
    $env:PATH = $previousPath
    if (Test-Path $nexusHome) {
        Remove-Item -Recurse -Force $nexusHome
    }
}
