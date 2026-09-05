[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$DistributionRoot,
    [Parameter(Mandatory = $true)][string]$SbomPath,
    [Parameter(Mandatory = $true)][string]$ProjectVersion,
    [Parameter(Mandatory = $true)][string]$JavaVersion
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$root = [IO.Path]::GetFullPath($DistributionRoot).TrimEnd('\')
$sbom = [IO.Path]::GetFullPath($SbomPath)
if (-not (Test-Path -LiteralPath $root -PathType Container)) {
    throw "Windows distribution root not found: $root"
}
if (-not (Test-Path -LiteralPath $sbom -PathType Leaf)) {
    throw "Base Maven SBOM not found: $sbom"
}
if (-not $sbom.StartsWith($root + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw 'The Windows SBOM must be located inside the distribution root.'
}

$bom = Get-Content -LiteralPath $sbom -Raw | ConvertFrom-Json
if ([string]$bom.bomFormat -ne 'CycloneDX') {
    throw 'Expected a CycloneDX base SBOM.'
}

$components = [Collections.Generic.List[object]]::new()
if ($null -ne $bom.components) {
    foreach ($component in @($bom.components)) {
        $components.Add($component)
    }
}

$encodedJavaVersion = [Uri]::EscapeDataString($JavaVersion)
$runtimeBomRef = "pkg:generic/eclipse-temurin-jre@$encodedJavaVersion?arch=x86_64&os=windows"
$components.Add([ordered]@{
    type = 'framework'
    'bom-ref' = $runtimeBomRef
    supplier = @{ name = 'Eclipse Adoptium' }
    name = 'Eclipse Temurin JRE'
    version = $JavaVersion
    purl = $runtimeBomRef
    properties = @(
        @{ name = 'nexus.runtime.kind'; value = 'jlink-jpackage' },
        @{ name = 'nexus.runtime.java.major'; value = '21' },
        @{ name = 'nexus.runtime.platform'; value = 'windows-x64' }
    )
})

$sbomRelative = [IO.Path]::GetRelativePath($root, $sbom).Replace('\', '/')
$files = Get-ChildItem -LiteralPath $root -Recurse -File | Sort-Object FullName
foreach ($file in $files) {
    $relative = [IO.Path]::GetRelativePath($root, $file.FullName).Replace('\', '/')
    if ($relative -eq $sbomRelative) {
        continue
    }
    $hash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    $components.Add([ordered]@{
        type = 'file'
        'bom-ref' = "file:$relative"
        name = $relative
        hashes = @(
            @{ alg = 'SHA-256'; content = $hash }
        )
        properties = @(
            @{ name = 'nexus.windows.relativePath'; value = $relative },
            @{ name = 'nexus.windows.sizeBytes'; value = [string]$file.Length }
        )
    })
}
$bom.components = @($components)

if ($null -eq $bom.metadata) {
    $bom | Add-Member -MemberType NoteProperty -Name metadata -Value ([pscustomobject]@{})
}
$properties = [Collections.Generic.List[object]]::new()
if ($null -ne $bom.metadata.properties) {
    foreach ($property in @($bom.metadata.properties)) {
        $properties.Add($property)
    }
}
$properties.Add(@{ name = 'nexus.sbom.profile'; value = 'windows-self-contained-v1' })
$properties.Add(@{ name = 'nexus.project.version'; value = $ProjectVersion })
$properties.Add(@{ name = 'nexus.runtime.java.version'; value = $JavaVersion })
$properties.Add(@{ name = 'nexus.runtime.inventory.files'; value = [string]($files.Count - 1) })
if ($bom.metadata.PSObject.Properties.Name -contains 'properties') {
    $bom.metadata.properties = @($properties)
}
else {
    $bom.metadata | Add-Member -MemberType NoteProperty -Name properties -Value @($properties)
}

$utf8 = New-Object System.Text.UTF8Encoding($false)
[IO.File]::WriteAllText($sbom, ($bom | ConvertTo-Json -Depth 100), $utf8)

$roundTrip = Get-Content -LiteralPath $sbom -Raw | ConvertFrom-Json
if (@($roundTrip.components).Count -lt $components.Count) {
    throw 'Windows SBOM round-trip lost components.'
}
if (-not (@($roundTrip.metadata.properties) | Where-Object { $_.name -eq 'nexus.sbom.profile' -and $_.value -eq 'windows-self-contained-v1' })) {
    throw 'Windows SBOM profile marker missing after serialization.'
}
Write-Host "Windows CycloneDX SBOM augmented: components=$(@($roundTrip.components).Count) files=$($files.Count - 1) java=$JavaVersion" -ForegroundColor Green
