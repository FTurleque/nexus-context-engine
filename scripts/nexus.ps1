$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$targetDirectory = Join-Path $repoRoot "target"
$cliJar = Get-ChildItem -Path $targetDirectory -Filter "nexus-context-engine-*-cli.jar" -File -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

if ($null -eq $cliJar) {
    Write-Error "JAR CLI NEXUS introuvable dans target/. Lancez d'abord : mvn clean package"
    exit 1
}

& java -jar $cliJar.FullName @args
exit $LASTEXITCODE
