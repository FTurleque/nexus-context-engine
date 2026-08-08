<#
.SYNOPSIS
    Valide le contrat du token Bearer REST généré par l'installateur Windows (P2 #8).

.DESCRIPTION
    L'installateur Inno Setup génère le token via BCryptGenRandom (CSPRNG système, 256 bits) encodé
    en hexadécimal minuscule. Ce script vérifie de façon isolée le CONTRAT que ce token doit
    respecter, sans dépendre d'ISCC :
      - 256 bits d'entropie => 64 caractères hexadécimaux ;
      - alphabet strictement [0-9a-f], sûr pour .env / cmd / Docker Compose / HTTP Authorization ;
      - deux générations successives diffèrent ;
      - aucun CR/LF ni caractère nécessitant un échappement ;
      - utilisable tel quel comme valeur d'une ligne .env.

    Le générateur de référence utilise la même famille CSPRNG (.NET RandomNumberGenerator) et le même
    encodage que le code Pascal, afin de garder le contrat testable en local et en CI multiplateforme.
    Le chemin Pascal réel est qualifié par le smoke Windows Installer en CI.

.EXAMPLE
    pwsh -File scripts/release/test-rest-token-contract.ps1
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

function New-NexusRestToken {
    $bytes = [byte[]]::new(32)  # 256 bits
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    -join ($bytes | ForEach-Object { $_.ToString('x2') })
}

$failures = New-Object System.Collections.Generic.List[string]
function Assert([bool]$Condition, [string]$Message) {
    if (-not $Condition) { $script:failures.Add($Message) }
    else { Write-Host "  [PASS] $Message" }
}

Write-Host 'Contrat token REST (P2 #8) :'

$token = New-NexusRestToken
$second = New-NexusRestToken

Assert ($token.Length -eq 64) "longueur = 64 caractères (256 bits)"
Assert ($token -cmatch '^[0-9a-f]{64}$') "alphabet strictement [0-9a-f]"
Assert ($token -ne $second) "deux générations successives diffèrent"
Assert (-not ($token -match "[`r`n]")) "aucun CR/LF"
Assert (-not ($token -match '[\s"''&|^%!()$`\\]')) "aucun caractère nécessitant un échappement .env/cmd"

# Round-trip .env : la valeur doit se relire à l'identique.
$tmp = New-TemporaryFile
try {
    "NEXUS_REST_API_TOKEN=$token" | Set-Content -LiteralPath $tmp -NoNewline -Encoding ascii
    $line = Get-Content -LiteralPath $tmp -Raw
    $parsed = ($line -split '=', 2)[1]
    Assert ($parsed -ceq $token) "round-trip .env préserve la valeur exacte"
}
finally {
    Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue
}

# Distribution : sur un échantillon, l'unicité et le format doivent tenir.
$sample = 1..200 | ForEach-Object { New-NexusRestToken }
Assert (($sample | Sort-Object -Unique).Count -eq 200) "200 générations toutes uniques"
Assert (($sample | Where-Object { $_ -cnotmatch '^[0-9a-f]{64}$' }).Count -eq 0) "200 générations toutes conformes"

if ($failures.Count -gt 0) {
    Write-Host ''
    Write-Host "ECHEC : $($failures.Count) assertion(s) du contrat token non satisfaite(s)" -ForegroundColor Red
    $failures | ForEach-Object { Write-Host "  [FAIL] $_" -ForegroundColor Red }
    exit 1
}

Write-Host ''
Write-Host 'OK : contrat token REST respecté.' -ForegroundColor Green
exit 0
