[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$work = Join-Path ([IO.Path]::GetTempPath()) ('nexus-sbom-smoke-' + [IO.Path]::GetRandomFileName())
try {
    $distribution = Join-Path $work 'dist'
    $runtime = Join-Path $distribution 'app\runtime\bin'
    New-Item -ItemType Directory -Force -Path $runtime | Out-Null
    [IO.File]::WriteAllBytes((Join-Path $runtime 'java.exe'), [byte[]](1, 2, 3, 4))
    Set-Content -LiteralPath (Join-Path $distribution 'VERSION') -Value '0.2.0' -Encoding ascii

    $sbom = Join-Path $distribution 'SBOM.cdx.json'
    @'
{
  "bomFormat": "CycloneDX",
  "specVersion": "1.6",
  "version": 1,
  "metadata": {},
  "components": [
    {
      "type": "library",
      "bom-ref": "pkg:maven/example/fixture@1.0.0",
      "name": "fixture",
      "version": "1.0.0"
    }
  ]
}
'@ | Set-Content -LiteralPath $sbom -Encoding utf8

    $augmenter = Join-Path $PSScriptRoot 'augment-windows-sbom.ps1'
    & $augmenter `
        -DistributionRoot $distribution `
        -SbomPath $sbom `
        -ProjectVersion '0.2.0' `
        -JavaVersion '21.0.12+8-LTS'

    $result = Get-Content -LiteralPath $sbom -Raw | ConvertFrom-Json
    $profile = @($result.metadata.properties) |
        Where-Object { $_.name -eq 'nexus.sbom.profile' } |
        Select-Object -First 1
    if ($null -eq $profile -or $profile.value -ne 'windows-self-contained-v1') {
        throw 'Windows SBOM smoke: profile marker missing.'
    }

    $runtimeComponent = @($result.components) |
        Where-Object { $_.name -eq 'Eclipse Temurin JRE' } |
        Select-Object -First 1
    if ($null -eq $runtimeComponent -or $runtimeComponent.version -ne '21.0.12+8-LTS') {
        throw 'Windows SBOM smoke: runtime component missing.'
    }
    if ([string]$runtimeComponent.purl -notmatch '^pkg:generic/eclipse-temurin-jre@21\.0\.12%2B8-LTS\?arch=x86_64&os=windows$') {
        throw "Windows SBOM smoke: invalid runtime purl: $($runtimeComponent.purl)"
    }

    $javaComponent = @($result.components) |
        Where-Object { $_.type -eq 'file' -and $_.name -eq 'app/runtime/bin/java.exe' } |
        Select-Object -First 1
    if ($null -eq $javaComponent) {
        throw 'Windows SBOM smoke: java.exe file inventory missing.'
    }
    $javaHash = @($javaComponent.hashes) |
        Where-Object { $_.alg -eq 'SHA-256' } |
        Select-Object -First 1
    if ($null -eq $javaHash -or [string]$javaHash.content -notmatch '^[0-9a-f]{64}$') {
        throw 'Windows SBOM smoke: java.exe SHA-256 missing or invalid.'
    }

    $baseComponent = @($result.components) |
        Where-Object { $_.name -eq 'fixture' } |
        Select-Object -First 1
    if ($null -eq $baseComponent) {
        throw 'Windows SBOM smoke: base Maven component was not preserved.'
    }

    Write-Host 'NEXUS Windows SBOM augmentation PASS' -ForegroundColor Green
}
finally {
    Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
}
