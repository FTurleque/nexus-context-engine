<#
.SYNOPSIS
    Smoke protocolaire MCP JSON-RPC STDIO (P2 #14) — natif ou Docker.

.DESCRIPTION
    Contrairement à un simple test de liveness (« le process reste vivant »), ce script parle
    réellement le protocole MCP sur STDIO :
      1. initialize
      2. notifications/initialized
      3. tools/list
      4. tools/call list_projects (outil non destructif) sur un NEXUS_HOME de test
    puis vérifie :
      - des réponses JSON-RPC 2.0 valides ;
      - un serveur identifié (serverInfo) ;
      - la présence des outils NEXUS attendus ;
      - un appel d'outil non destructif renvoyant un result (pas une erreur) ;
      - qu'aucun log applicatif parasite ne pollue stdout (stdout = protocole uniquement).

    Le lanceur est paramétrable pour couvrir le MCP natif (java -jar) et le MCP Docker
    (docker run -i ... mcp).

.PARAMETER Exe
    Exécutable à lancer (ex. chemin de java.exe, ou 'docker').

.PARAMETER LaunchArgs
    Arguments du lanceur (ex. @('-jar', 'C:\...\nexus-mcp.jar')).

.PARAMETER NexusHome
    NEXUS_HOME de test (natif). Ignoré si le conteneur gère son propre home.

.PARAMETER Label
    Étiquette affichée (Native / Docker).

.PARAMETER TimeoutSeconds
    Délai maximum d'attente des réponses.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Exe,
    [Parameter(Mandatory = $true)][string[]]$LaunchArgs,
    [string]$NexusHome,
    [string]$Label = 'MCP',
    [int]$TimeoutSeconds = 45
)

$ErrorActionPreference = 'Stop'

$expectedTools = @(
    'list_projects', 'search_code', 'search_across_projects',
    'find_symbol', 'find_usages',
    'build_context', 'explain_context',
    'build_context_across_projects', 'explain_context_across_projects'
)

$messages = @(
    '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"nexus-smoke","version":"1.0"}}}'
    '{"jsonrpc":"2.0","method":"notifications/initialized"}'
    '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'
    '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"list_projects","arguments":{}}}'
)

$work = Join-Path ([System.IO.Path]::GetTempPath()) ("nexus-mcp-smoke-" + [System.IO.Path]::GetRandomFileName())
New-Item -ItemType Directory -Path $work -Force | Out-Null
$inFile = Join-Path $work 'in.jsonl'
$outFile = Join-Path $work 'out.jsonl'
$errFile = Join-Path $work 'err.log'
# Encodage ASCII sans BOM : le transport STDIO lit du JSON ligne à ligne.
[System.IO.File]::WriteAllText($inFile, ($messages -join "`n") + "`n", (New-Object System.Text.ASCIIEncoding))

$failures = New-Object System.Collections.Generic.List[string]
function Assert([bool]$Condition, [string]$Message) {
    if (-not $Condition) { $script:failures.Add($Message) } else { Write-Host "  [PASS] $Message" }
}
# Accès propriété tolérant à Set-StrictMode (les appelants comme test-docker-runtime.ps1 l'activent).
function HasProp($Object, [string]$Name) {
    return ($null -ne $Object) -and ($Object.PSObject.Properties.Name -contains $Name)
}
function Prop($Object, [string]$Name) {
    if (HasProp $Object $Name) { return $Object.$Name }
    return $null
}

Write-Host "[$Label] smoke protocolaire MCP JSON-RPC..." -ForegroundColor Cyan

$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = $Exe
foreach ($a in $LaunchArgs) { $psi.ArgumentList.Add($a) }
$psi.RedirectStandardInput = $true
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true
$psi.UseShellExecute = $false
if ($NexusHome) { $psi.Environment['NEXUS_HOME'] = $NexusHome }

$proc = $null
try {
    $proc = [System.Diagnostics.Process]::Start($psi)

    # Lecture asynchrone de stdout/stderr vers fichiers pour éviter tout blocage de pipe.
    $outSb = New-Object System.Text.StringBuilder
    $errSb = New-Object System.Text.StringBuilder
    $outHandler = { if ($EventArgs.Data -ne $null) { [void]$Event.MessageData.AppendLine($EventArgs.Data) } }
    Register-ObjectEvent -InputObject $proc -EventName OutputDataReceived -Action $outHandler -MessageData $outSb | Out-Null
    Register-ObjectEvent -InputObject $proc -EventName ErrorDataReceived -Action $outHandler -MessageData $errSb | Out-Null
    $proc.BeginOutputReadLine()
    $proc.BeginErrorReadLine()

    # Envoi cadencé (comme un vrai client MCP qui attend chaque réponse) : un burst simultané peut
    # perturber le dispatch du handshake initialize -> initialized -> requêtes.
    foreach ($m in $messages) {
        $proc.StandardInput.WriteLine($m)
        $proc.StandardInput.Flush()
        Start-Sleep -Milliseconds 800
    }
    $proc.StandardInput.Close()

    # Attente des trois réponses (id 1,2,3) ou timeout.
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $seen = @{}
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Milliseconds 300
        $current = $outSb.ToString()
        foreach ($line in ($current -split "`n")) {
            $trim = $line.Trim()
            if (-not $trim) { continue }
            try { $obj = $trim | ConvertFrom-Json } catch { continue }
            if ($obj.PSObject.Properties.Name -contains 'id' -and $null -ne $obj.id) { $seen[[string]$obj.id] = $obj }
        }
        if ($seen.ContainsKey('1') -and $seen.ContainsKey('2') -and $seen.ContainsKey('3')) { break }
    }

    $stdout = $outSb.ToString()
    [System.IO.File]::WriteAllText($outFile, $stdout)
    [System.IO.File]::WriteAllText($errFile, $errSb.ToString())

    # --- Assertions ---
    Assert ($seen.ContainsKey('1')) 'réponse initialize reçue'
    if ($seen.ContainsKey('1')) {
        $init = $seen['1']
        Assert ((Prop $init 'jsonrpc') -eq '2.0') 'initialize : JSON-RPC 2.0'
        $initResult = Prop $init 'result'
        $serverInfo = Prop $initResult 'serverInfo'
        Assert ($null -ne $serverInfo) 'initialize : serverInfo présent'
        $serverName = Prop $serverInfo 'name'
        Assert ($serverName -match 'nexus') "initialize : serveur identifié ($serverName)"
    }

    Assert ($seen.ContainsKey('2')) 'réponse tools/list reçue'
    if ($seen.ContainsKey('2')) {
        $tools = Prop (Prop $seen['2'] 'result') 'tools'
        Assert ($null -ne $tools -and @($tools).Count -ge 1) 'tools/list : liste non vide'
        $names = @($tools | ForEach-Object { Prop $_ 'name' })
        foreach ($t in $expectedTools) {
            Assert ($names -contains $t) "tools/list : outil '$t' présent"
        }
    }

    Assert ($seen.ContainsKey('3')) 'réponse tools/call list_projects reçue'
    if ($seen.ContainsKey('3')) {
        $call = $seen['3']
        Assert ($null -ne (Prop $call 'result')) 'tools/call list_projects : result (pas erreur)'
        Assert (-not (HasProp $call 'error')) 'tools/call list_projects : aucune erreur JSON-RPC'
    }

    # stdout doit être exclusivement du protocole : chaque ligne non vide parse en JSON-RPC.
    $nonJson = @()
    foreach ($line in ($stdout -split "`n")) {
        $trim = $line.Trim()
        if (-not $trim) { continue }
        try { $o = $trim | ConvertFrom-Json } catch { $nonJson += $trim; continue }
        if (-not (HasProp $o 'jsonrpc')) { $nonJson += $trim }
    }
    Assert ($nonJson.Count -eq 0) 'stdout ne contient que du JSON-RPC (aucun log parasite)'
    if ($nonJson.Count -gt 0) { Write-Host "    lignes non-protocole: $($nonJson -join ' | ')" -ForegroundColor Yellow }
}
finally {
    if ($proc -and -not $proc.HasExited) { try { $proc.Kill($true) } catch { try { $proc.Kill() } catch {} } }
    Get-EventSubscriber -ErrorAction SilentlyContinue | Unregister-Event -ErrorAction SilentlyContinue
    Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
}

if ($failures.Count -gt 0) {
    Write-Host ''
    $failures | ForEach-Object { Write-Host "  [FAIL] $_" -ForegroundColor Red }
    # throw plutôt qu'exit : composable depuis un autre script (try/catch) tout en produisant un
    # code de sortie non nul lorsqu'il est lancé en standalone via `pwsh -File`.
    throw "[$Label] smoke protocolaire MCP ECHEC : $($failures.Count) assertion(s) non satisfaite(s)"
}

Write-Host "[$Label] smoke protocolaire MCP PASS" -ForegroundColor Green
