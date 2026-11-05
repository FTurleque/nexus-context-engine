[CmdletBinding()]
param(
    [ValidateSet("ci", "full")]
    [string]$Profile = "full",

    [string]$Output = "target\scale-benchmark.json"
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Import-Module (Join-Path $PSScriptRoot "lib/NexusScript.psm1") -Force
$resolvedOutput = Resolve-AbsolutePath -Path $Output -BasePath $repoRoot

Push-Location $repoRoot
try {
    Write-Host "=== NEXUS scale regression benchmark ==="
    Write-Host "Profile : $Profile"
    Write-Host "Output  : $resolvedOutput"

    Invoke-Maven -Arguments @(
        "-B", "-pl", "core",
        "-Dtest=ScaleRegressionBenchmarkTest",
        "-Djunit.jupiter.execution.timeout.test.method.default=20m",
        "-Dnexus.scale.benchmark.enabled=true",
        "-Dnexus.scale.benchmark.profile=$Profile",
        "-Dnexus.scale.benchmark.output=$resolvedOutput",
        "test"
    )

    if (-not (Test-Path -LiteralPath $resolvedOutput -PathType Leaf)) {
        throw "Scale benchmark report was not produced: $resolvedOutput"
    }

    $report = Get-Content -Raw -LiteralPath $resolvedOutput | ConvertFrom-Json
    Write-Host "Benchmark PASS"
    Write-Host "SQLite max symbols : $($report.sqliteTiers[-1].symbols)"
    Write-Host "Portfolio max      : $($report.portfolio.maximumProjects)"
    Write-Host "Semantic documents : $($report.semanticRecovery.documents)"
}
finally {
    Pop-Location
}
