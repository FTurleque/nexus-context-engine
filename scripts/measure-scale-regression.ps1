[CmdletBinding()]
param(
    [ValidateSet("ci", "full")]
    [string]$Profile = "full",

    [string]$Output = "target\scale-benchmark.json"
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$resolvedOutput = if ([System.IO.Path]::IsPathRooted($Output)) {
    [System.IO.Path]::GetFullPath($Output)
} else {
    [System.IO.Path]::GetFullPath((Join-Path $repoRoot $Output))
}

Push-Location $repoRoot
try {
    Write-Host "=== NEXUS scale regression benchmark ==="
    Write-Host "Profile : $Profile"
    Write-Host "Output  : $resolvedOutput"

    & .\mvnw.cmd -B -pl core `
        "-Dtest=ScaleRegressionBenchmarkTest" `
        "-Djunit.jupiter.execution.timeout.test.method.default=20m" `
        "-Dnexus.scale.benchmark.enabled=true" `
        "-Dnexus.scale.benchmark.profile=$Profile" `
        "-Dnexus.scale.benchmark.output=$resolvedOutput" `
        test

    if ($LASTEXITCODE -ne 0) {
        throw "Scale benchmark failed with exit code $LASTEXITCODE"
    }
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
