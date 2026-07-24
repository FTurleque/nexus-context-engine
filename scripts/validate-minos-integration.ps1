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
$java = (Resolve-Path $Java24).Path
$fixturePath = (Resolve-Path $Fixture).Path

Write-Host "NEXUS MINOS integration replay"
Write-Host "  MINOS JAR : $jar"
Write-Host "  Java 24   : $java"
Write-Host "  Fixture   : $fixturePath"

mvn `
    '-Dtest=MinosRealIntegrationTest' `
    "-Dnexus.minos.integration.jar=$jar" `
    "-Dnexus.minos.integration.java=$java" `
    "-Dnexus.minos.integration.fixture=$fixturePath" `
    test

if ($LASTEXITCODE -ne 0) {
    throw "MINOS -> NEXUS replay failed with exit code $LASTEXITCODE"
}

Write-Host 'M13 MINOS -> NEXUS replay SUCCESS'
