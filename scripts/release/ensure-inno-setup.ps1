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
$expectedSigner = 'Pyrsys B\.V\.'
$expectedVersionPattern = '^7\.0\.2(?:\.0)?(?:\D.*)?$'

if ([string]::IsNullOrWhiteSpace($ToolDirectory)) {
    $ToolDirectory = Join-Path $repo "target\tooling\inno-setup-$innoVersion"
}
$toolRoot = [IO.Path]::GetFullPath($ToolDirectory)

function Test-IsccQualified {
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
        Write-Warning "Ignoring unqualified ISCC.exe with invalid Authenticode signature: $resolved ($($signature.Status))"
        return $false
    }

    $subject = [string]$signature.SignerCertificate.Subject
    if ($subject -notmatch $script:expectedSigner) {
        Write-Warning "Ignoring ISCC.exe signed by unexpected publisher: $resolved ($subject)"
        return $false
    }

    $versionInfo = [Diagnostics.FileVersionInfo]::GetVersionInfo($resolved)
    $productVersion = [string]$versionInfo.ProductVersion
    $fileVersion = [string]$versionInfo.FileVersion
    if ($productVersion -notmatch $script:expectedVersionPattern -and
        $fileVersion -notmatch $script:expectedVersionPattern) {
        Write-Warning "Ignoring ISCC.exe with unexpected version: $resolved (product=$productVersion file=$fileVersion expected=$script:innoVersion)"
        return $false
    }

    Write-Host "Qualified Inno Setup compiler: $resolved (version=$productVersion signer=$subject)"
    return $true
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
$compilerRoot = Join-Path $toolRoot 'compiler'

if (-not (Test-Path -LiteralPath $installer -PathType Leaf)) {
    Write-Host "Downloading pinned Inno Setup $innoVersion..."
    Invoke-WebRequest -Uri $assetUri -OutFile $installer -UseBasicParsing
}

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
    $iscc = Get-ChildItem -LiteralPath $compilerRoot -Recurse -Filter ISCC.exe -File -ErrorAction SilentlyContinue |
        Where-Object { Test-IsccQualified -Path $_.FullName } |
        Select-Object -First 1
}

if ($null -eq $iscc) {
    throw "Inno Setup bootstrap completed but no qualified ISCC.exe $innoVersion was found under $compilerRoot"
}

Write-Host "Inno Setup compiler ready: $($iscc.FullName)"
Write-Output $iscc.FullName
