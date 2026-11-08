[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string[]]$Path,
    [string]$TimestampServer = 'http://timestamp.digicert.com',
    # CI: base64 PFX received by the signing step only. Decoded into a directory private to
    # this invocation and erased in the finally block below; its path is never published.
    [string]$PfxBase64 = [string]$env:NEXUS_WINDOWS_SIGNING_PFX_BASE64,
    # Local workstation: path to an existing PFX file owned by the developer.
    [string]$PfxPath = [string]$env:NEXUS_WINDOWS_SIGNING_PFX,
    [string]$PfxPassword = [string]$env:NEXUS_WINDOWS_SIGNING_PFX_PASSWORD
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ($env:OS -ne 'Windows_NT') {
    throw 'Authenticode signing is supported only on Windows.'
}

$required = [string]$env:NEXUS_WINDOWS_REQUIRE_AUTHENTICODE -eq 'true'
$hasBase64 = -not [string]::IsNullOrWhiteSpace($PfxBase64)
$hasPath = -not [string]::IsNullOrWhiteSpace($PfxPath)

if (-not $hasBase64 -and -not $hasPath) {
    if ($required) {
        throw 'Authenticode signing is required, but neither NEXUS_WINDOWS_SIGNING_PFX_BASE64 nor NEXUS_WINDOWS_SIGNING_PFX is configured for this step.'
    }
    Write-Host 'Authenticode signing not configured; keeping non-release qualification artifact unsigned.' -ForegroundColor DarkYellow
    return
}
if ($hasBase64 -and $hasPath) {
    throw 'Configure either NEXUS_WINDOWS_SIGNING_PFX_BASE64 or NEXUS_WINDOWS_SIGNING_PFX, not both.'
}
if ([string]::IsNullOrEmpty($PfxPassword)) {
    throw 'NEXUS_WINDOWS_SIGNING_PFX_PASSWORD is required when a signing PFX is configured.'
}

$materialRoot = $null
try {
    if ($hasBase64) {
        $tempBase = if (-not [string]::IsNullOrWhiteSpace($env:RUNNER_TEMP)) { $env:RUNNER_TEMP } else { [IO.Path]::GetTempPath() }
        $materialRoot = Join-Path $tempBase ('nexus-windows-signing-' + [guid]::NewGuid().ToString('N'))
        New-Item -ItemType Directory -Path $materialRoot | Out-Null
        try {
            $bytes = [Convert]::FromBase64String($PfxBase64)
        }
        catch {
            throw 'NEXUS_WINDOWS_SIGNING_PFX_BASE64 is not valid base64.'
        }
        $PfxPath = Join-Path $materialRoot 'signing.pfx'
        try {
            [IO.File]::WriteAllBytes($PfxPath, $bytes)
        }
        finally {
            [Array]::Clear($bytes, 0, $bytes.Length)
        }
    }
    elseif (-not (Test-Path -LiteralPath $PfxPath -PathType Leaf)) {
        throw "Authenticode PFX not found: $PfxPath"
    }

    $securePassword = ConvertTo-SecureString -String $PfxPassword -AsPlainText -Force
    $certificate = New-Object System.Security.Cryptography.X509Certificates.X509Certificate2
    try {
        $flags = [System.Security.Cryptography.X509Certificates.X509KeyStorageFlags]::Exportable -bor
            [System.Security.Cryptography.X509Certificates.X509KeyStorageFlags]::EphemeralKeySet
        $certificate.Import($PfxPath, $securePassword, $flags)
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
}
finally {
    # The decoded PFX never outlives the signing call that created it.
    if ($null -ne $materialRoot -and (Test-Path -LiteralPath $materialRoot)) {
        Remove-Item -LiteralPath $materialRoot -Recurse -Force
    }
}
