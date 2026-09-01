[CmdletBinding()]
param(
    [string]$ToolDirectory
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$innoVersion = '7.0.2'
$assetName = "innosetup-$innoVersion-x64.exe"
$assetUri = "https://github.com/jrsoftware/issrc/releases/download/is-7_0_2/$assetName"
# GitHub immutable release asset digest for jrsoftware/issrc tag is-7_0_2.
$expectedInstallerSha256 = '5ad54ca3def786f8f4212552e54cc6d8d61329e2d24a1cfee0571d42c2684ff1'
$expectedSigner = 'Pyrsys B\.V\.'
$expectedVersionPattern = '^7\.0\.2(?:\.0)?(?:\D.*)?$'

if ([string]::IsNullOrWhiteSpace($ToolDirectory)) {
    $ToolDirectory = Join-Path $repo "target\tooling\inno-setup-$innoVersion"
}
$toolRoot = [IO.Path]::GetFullPath($ToolDirectory)
$compilerRoot = Join-Path $toolRoot 'compiler'
$attestationPath = Join-Path $toolRoot 'qualified-iscc.json'

function Test-ExpectedSigner {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return $false
    }

    $resolved = (Resolve-Path -LiteralPath $Path).Path
    $signature = Get-AuthenticodeSignature -LiteralPath $resolved
    if ($signature.Status -ne [System.Management.Automation.SignatureStatus]::Valid) {
        Write-Warning "Ignoring unqualified executable with invalid Authenticode signature: $resolved ($($signature.Status))"
        return $false
    }

    $subject = [string]$signature.SignerCertificate.Subject
    if ($subject -notmatch $script:expectedSigner) {
        Write-Warning "Ignoring executable signed by unexpected publisher: $resolved ($subject)"
        return $false
    }

    return $true
}

function Test-ToolAttestation {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    if (-not (Test-Path -LiteralPath $script:attestationPath -PathType Leaf)) {
        return $false
    }

    $resolved = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $Path).Path)
    $compilerPrefix = [IO.Path]::GetFullPath($script:compilerRoot).TrimEnd('\') + '\'
    if (-not $resolved.StartsWith($compilerPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        return $false
    }

    try {
        $attestation = Get-Content -LiteralPath $script:attestationPath -Raw | ConvertFrom-Json
    }
    catch {
        Write-Warning "Ignoring invalid Inno Setup attestation: $script:attestationPath"
        return $false
    }

    if ([string]$attestation.version -ne $script:innoVersion -or
        [string]$attestation.installerSha256 -ne $script:expectedInstallerSha256) {
        Write-Warning "Ignoring stale Inno Setup attestation: $script:attestationPath"
        return $false
    }

    $actualHash = (Get-FileHash -LiteralPath $resolved -Algorithm SHA256).Hash.ToLowerInvariant()
    if ([string]$attestation.isccSha256 -ne $actualHash) {
        Write-Warning "Ignoring Inno Setup compiler whose SHA-256 no longer matches its attestation: $resolved"
        return $false
    }

    return $true
}

function Test-IsccQualified {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    if (-not (Test-ExpectedSigner -Path $Path)) {
        return $false
    }

    $resolved = (Resolve-Path -LiteralPath $Path).Path
    $versionInfo = [Diagnostics.FileVersionInfo]::GetVersionInfo($resolved)
    $productVersion = [string]$versionInfo.ProductVersion
    $fileVersion = [string]$versionInfo.FileVersion
    if ($productVersion -match $script:expectedVersionPattern -or
        $fileVersion -match $script:expectedVersionPattern) {
        $signature = Get-AuthenticodeSignature -LiteralPath $resolved
        Write-Host "Qualified Inno Setup compiler: $resolved (version=$productVersion signer=$($signature.SignerCertificate.Subject))"
        return $true
    }

    # Official ISCC 7.0.2 can expose 0.0.0.0 through Windows FileVersionInfo.
    # For the repository-managed compiler, accept only an exact hash attestation
    # created from the pinned, hash-verified and Authenticode-verified bootstrap.
    if (Test-ToolAttestation -Path $resolved) {
        $signature = Get-AuthenticodeSignature -LiteralPath $resolved
        Write-Host "Qualified repository-managed Inno Setup compiler: $resolved (attested version=$script:innoVersion signer=$($signature.SignerCertificate.Subject))"
        return $true
    }

    Write-Warning "Ignoring ISCC.exe with unverified version: $resolved (product=$productVersion file=$fileVersion expected=$script:innoVersion)"
    return $false
}

function Find-QualifiedIscc {
    $candidates = [Collections.Generic.List[string]]::new()

    if ($env:NEXUS_ISCC -and (Test-Path -LiteralPath $env:NEXUS_ISCC -PathType Leaf)) {
        $candidates.Add((Resolve-Path -LiteralPath $env:NEXUS_ISCC).Path)
    }

    $command = Get-Command ISCC.exe -ErrorAction SilentlyContinue
    if ($command) {
        $candidates.Add($command.Source)
    }

    if (Test-Path -LiteralPath $script:compilerRoot -PathType Container) {
        Get-ChildItem -LiteralPath $script:compilerRoot -Recurse -Filter ISCC.exe -File -ErrorAction SilentlyContinue |
            ForEach-Object { $candidates.Add($_.FullName) }
    }

    $roots = @($env:LOCALAPPDATA, $env:ProgramFiles, ${env:ProgramFiles(x86)}) | Where-Object { $_ }
    foreach ($root in $roots) {
        foreach ($candidate in @(
            (Join-Path $root 'Programs\Inno Setup 7\ISCC.exe'),
            (Join-Path $root 'Programs\Inno Setup 6\ISCC.exe'),
            (Join-Path $root 'Inno Setup 7\ISCC.exe'),
            (Join-Path $root 'Inno Setup 6\ISCC.exe')
        )) {
            if (Test-Path -LiteralPath $candidate -PathType Leaf) {
                $candidates.Add((Resolve-Path -LiteralPath $candidate).Path)
            }
        }
    }

    foreach ($candidate in ($candidates | Select-Object -Unique)) {
        if (Test-IsccQualified -Path $candidate) {
            return $candidate
        }
    }
    return $null
}

$existing = Find-QualifiedIscc
if ($existing) {
    Write-Output $existing
    exit 0
}

New-Item -ItemType Directory -Force -Path $toolRoot | Out-Null
$installer = Join-Path $toolRoot $assetName

if (-not (Test-Path -LiteralPath $installer -PathType Leaf)) {
    Write-Host "Downloading pinned Inno Setup $innoVersion..."
    Invoke-WebRequest -Uri $assetUri -OutFile $installer -UseBasicParsing
}

$installerHash = (Get-FileHash -LiteralPath $installer -Algorithm SHA256).Hash.ToLowerInvariant()
if ($installerHash -ne $expectedInstallerSha256) {
    throw "Inno Setup bootstrap SHA-256 mismatch. Expected=$expectedInstallerSha256 Actual=$installerHash"
}
Write-Host "Inno Setup bootstrap SHA-256: PASS ($installerHash)"

$signature = Get-AuthenticodeSignature -LiteralPath $installer
if ($signature.Status -ne [System.Management.Automation.SignatureStatus]::Valid) {
    throw "Inno Setup bootstrap Authenticode signature is not valid: $($signature.Status)"
}
$subject = [string]$signature.SignerCertificate.Subject
if ($subject -notmatch $expectedSigner) {
    throw "Unexpected Inno Setup signer: $subject"
}
Write-Host "Inno Setup bootstrap signature: PASS ($subject)"

$iscc = Get-ChildItem -LiteralPath $compilerRoot -Recurse -Filter ISCC.exe -File -ErrorAction SilentlyContinue |
    Where-Object { Test-IsccQualified -Path $_.FullName } |
    Select-Object -First 1
if ($null -eq $iscc) {
    Remove-Item -LiteralPath $compilerRoot -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $attestationPath -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $compilerRoot | Out-Null
    Write-Host "Installing Inno Setup $innoVersion in portable current-user mode..."
    $arguments = @(
        '/VERYSILENT',
        '/SUPPRESSMSGBOXES',
        '/NORESTART',
        '/CURRENTUSER',
        '/PORTABLE=1',
        "/DIR=`"$compilerRoot`""
    )
    $process = Start-Process -FilePath $installer -ArgumentList $arguments -Wait -PassThru
    if ($process.ExitCode -ne 0) {
        throw "Inno Setup bootstrap installer failed with exit code $($process.ExitCode)"
    }

    # The freshly installed compiler inherits its version provenance from the
    # exact immutable release asset verified above. Bind that provenance to the
    # extracted executable by SHA-256 so later invocations cannot substitute it.
    $freshIscc = Get-ChildItem -LiteralPath $compilerRoot -Recurse -Filter ISCC.exe -File -ErrorAction SilentlyContinue |
        Where-Object { Test-ExpectedSigner -Path $_.FullName } |
        Select-Object -First 1
    if ($null -eq $freshIscc) {
        throw "Inno Setup bootstrap completed but no correctly signed ISCC.exe was found under $compilerRoot"
    }

    $freshHash = (Get-FileHash -LiteralPath $freshIscc.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    [ordered]@{
        version = $innoVersion
        installerSha256 = $installerHash
        isccSha256 = $freshHash
        signerSubject = [string](Get-AuthenticodeSignature -LiteralPath $freshIscc.FullName).SignerCertificate.Subject
    } | ConvertTo-Json | Set-Content -LiteralPath $attestationPath -Encoding utf8

    if (-not (Test-IsccQualified -Path $freshIscc.FullName)) {
        throw "Repository-managed ISCC.exe failed post-install qualification: $($freshIscc.FullName)"
    }
    $iscc = $freshIscc
}

if ($null -eq $iscc) {
    throw "Inno Setup bootstrap completed but no qualified ISCC.exe $innoVersion was found under $compilerRoot"
}

Write-Host "Inno Setup compiler ready: $($iscc.FullName)"
Write-Output $iscc.FullName
