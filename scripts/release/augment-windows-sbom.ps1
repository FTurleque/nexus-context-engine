[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$DistributionRoot,
    [Parameter(Mandatory = $true)][string]$SbomPath,
    [Parameter(Mandatory = $true)][string]$ProjectVersion,
    [Parameter(Mandatory = $true)][string]$JavaVersion
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-NexusRelativePath {
    param(
        [Parameter(Mandatory = $true)][string]$BaseRoot,
        [Parameter(Mandatory = $true)][string]$Path
    )

    # Windows Installer runs under Windows PowerShell 5.1 / .NET Framework on
    # hosted runners. System.IO.Path.GetRelativePath is a .NET Core API and is
    # therefore intentionally avoided here.
    $base = [IO.Path]::GetFullPath($BaseRoot).TrimEnd([char[]]@('\', '/'))
    $candidate = [IO.Path]::GetFullPath($Path)
    if ($candidate.Equals($base, [StringComparison]::OrdinalIgnoreCase)) {
        return ''
    }

    $prefix = $base + [IO.Path]::DirectorySeparatorChar
    if (-not $candidate.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Path escapes Windows distribution root: $candidate"
    }

    return $candidate.Substring($prefix.Length).Replace('\', '/')
}

$root = [IO.Path]::GetFullPath($DistributionRoot).TrimEnd([char[]]@('\', '/'))
$sbom = [IO.Path]::GetFullPath($SbomPath)
if (-not (Test-Path -LiteralPath $root -PathType Container)) {
    throw "Windows distribution root not found: $root"
}
if (-not (Test-Path -LiteralPath $sbom -PathType Leaf)) {
    throw "Base Maven SBOM not found: $sbom"
}
$sbomRelative = Get-NexusRelativePath -BaseRoot $root -Path $sbom
if ([string]::IsNullOrWhiteSpace($sbomRelative)) {
    throw 'The Windows SBOM must be a file inside the distribution root.'
}

$bom = Get-Content -LiteralPath $sbom -Raw | ConvertFrom-Json
if ([string]$bom.bomFormat -ne 'CycloneDX') {
    throw 'Expected a CycloneDX base SBOM.'
}

$components = [Collections.Generic.List[object]]::new()
if ($bom.PSObject.Properties.Name -contains 'components') {
    foreach ($component in @($bom.components)) {
        $components.Add($component)
    }
}

$encodedJavaVersion = [Uri]::EscapeDataString($JavaVersion)
$runtimeBomRef = "pkg:generic/eclipse-temurin-jre@${encodedJavaVersion}?arch=x86_64&os=windows"
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

$files = Get-ChildItem -LiteralPath $root -Recurse -File | Sort-Object FullName
$inventoriedFiles = 0
foreach ($file in $files) {
    $relative = Get-NexusRelativePath -BaseRoot $root -Path $file.FullName
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
    $inventoriedFiles++
}
if ($bom.PSObject.Properties.Name -contains 'components') {
    $bom.components = @($components)
}
else {
    $bom | Add-Member -MemberType NoteProperty -Name components -Value @($components)
}

if (-not ($bom.PSObject.Properties.Name -contains 'metadata') -or $null -eq $bom.metadata) {
    if ($bom.PSObject.Properties.Name -contains 'metadata') {
        $bom.metadata = [pscustomobject]@{}
    }
    else {
        $bom | Add-Member -MemberType NoteProperty -Name metadata -Value ([pscustomobject]@{})
    }
}
$properties = [Collections.Generic.List[object]]::new()
if ($bom.metadata.PSObject.Properties.Name -contains 'properties') {
    foreach ($property in @($bom.metadata.properties)) {
        $properties.Add($property)
    }
}
$properties.Add(@{ name = 'nexus.sbom.profile'; value = 'windows-self-contained-v1' })
$properties.Add(@{ name = 'nexus.project.version'; value = $ProjectVersion })
$properties.Add(@{ name = 'nexus.runtime.java.version'; value = $JavaVersion })
$properties.Add(@{ name = 'nexus.runtime.inventory.files'; value = [string]$inventoriedFiles })
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
Write-Host "Windows CycloneDX SBOM augmented: components=$(@($roundTrip.components).Count) files=$inventoriedFiles java=$JavaVersion" -ForegroundColor Green
