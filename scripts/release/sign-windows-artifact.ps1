[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string[]]$Path,
    [string]$TimestampServer = 'http://timestamp.digicert.com'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ($env:OS -ne 'Windows_NT') {
    throw 'Authenticode signing is supported only on Windows.'
}

$required = [string]$env:NEXUS_WINDOWS_REQUIRE_AUTHENTICODE -eq 'true'
$pfxPath = [string]$env:NEXUS_WINDOWS_SIGNING_PFX
$password = [string]$env:NEXUS_WINDOWS_SIGNING_PFX_PASSWORD

if ([string]::IsNullOrWhiteSpace($pfxPath)) {
    if ($required) {
        throw 'Authenticode signing is required, but NEXUS_WINDOWS_SIGNING_PFX is not configured.'
    }
    Write-Host 'Authenticode signing not configured; keeping non-release qualification artifact unsigned.' -ForegroundColor DarkYellow
    return
}
if (-not (Test-Path -LiteralPath $pfxPath -PathType Leaf)) {
    throw "Authenticode PFX not found: $pfxPath"
}
if ([string]::IsNullOrEmpty($password)) {
    throw 'NEXUS_WINDOWS_SIGNING_PFX_PASSWORD is required when a signing PFX is configured.'
}

$securePassword = ConvertTo-SecureString -String $password -AsPlainText -Force
$certificate = New-Object System.Security.Cryptography.X509Certificates.X509Certificate2
try {
    $flags = [System.Security.Cryptography.X509Certificates.X509KeyStorageFlags]::Exportable -bor
        [System.Security.Cryptography.X509Certificates.X509KeyStorageFlags]::EphemeralKeySet
    $certificate.Import($pfxPath, $securePassword, $flags)
    if (-not $certificate.HasPrivateKey) {
        throw 'Configured Authenticode certificate does not contain a private key.'
    }

    foreach ($candidate in $Path) {
        $resolved = [IO.Path]::GetFullPath($candidate)
        if (-not (Test-Path -LiteralPath $resolved -PathType Leaf)) {
            throw "Authenticode target not found: $resolved"
        }

        $signature = Set-AuthenticodeSignature `
            -LiteralPath $resolved `
            -Certificate $certificate `
            -HashAlgorithm SHA256 `
            -TimestampServer $TimestampServer
        if ($signature.Status -ne [System.Management.Automation.SignatureStatus]::Valid) {
            throw "Authenticode signing failed for $resolved`: $($signature.Status) $($signature.StatusMessage)"
        }

        $verified = Get-AuthenticodeSignature -LiteralPath $resolved
        if ($verified.Status -ne [System.Management.Automation.SignatureStatus]::Valid) {
            throw "Authenticode verification failed for $resolved`: $($verified.Status) $($verified.StatusMessage)"
        }
        if ($verified.SignerCertificate.Thumbprint -ne $certificate.Thumbprint) {
            throw "Authenticode signer mismatch for $resolved"
        }
        Write-Host "Authenticode: PASS $resolved signer=$($verified.SignerCertificate.Subject)" -ForegroundColor Green
    }
}
finally {
    $certificate.Dispose()
}
