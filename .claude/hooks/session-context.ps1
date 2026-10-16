# SessionStart : résume l'état Git local utile à NEXUS (sans accès réseau).
# Le texte écrit sur stdout est ajouté au contexte de la session.

Set-StrictMode -Version Latest
$ErrorActionPreference = 'SilentlyContinue'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)

$root = if ($env:CLAUDE_PROJECT_DIR) { $env:CLAUDE_PROJECT_DIR } else { (Get-Location).Path }

function Invoke-Git([string[]] $arguments) {
    $output = & git -C $root @arguments 2>$null
    if ($LASTEXITCODE -ne 0) { return $null }
    return ($output -join "`n").Trim()
}

$branch = Invoke-Git @('rev-parse', '--abbrev-ref', 'HEAD')
if ($null -eq $branch) {
    exit 0
}

$lines = [System.Collections.Generic.List[string]]::new()
$lines.Add("NEXUS — branche courante : $branch")

$dirty = Invoke-Git @('status', '--porcelain')
$dirtyCount = if ([string]::IsNullOrEmpty($dirty)) { 0 } else { ($dirty -split "`n").Count }
$lines.Add("Fichiers modifiés ou non suivis : $dirtyCount")

if ($branch -in @('develop', 'main')) {
    $lines.Add("Attention : $branch est protégée (PR obligatoire). Créer une branche avant de modifier.")
}

$hasDevelop = Invoke-Git @('rev-parse', '--verify', '--quiet', 'refs/remotes/origin/develop')
$hasMain = Invoke-Git @('rev-parse', '--verify', '--quiet', 'refs/remotes/origin/main')
if ($hasDevelop -and $hasMain) {
    # Commits non-merge présents sur main mais absents de develop : correctifs jamais redescendus.
    $mainOnly = Invoke-Git @('rev-list', '--count', '--no-merges', 'origin/develop..origin/main')
    if ($mainOnly -and [int]$mainOnly -gt 0) {
        $lines.Add("Divergence : $mainOnly commit(s) non-merge sur origin/main absents de origin/develop (dernier fetch). La prochaine promotion develop -> main sera en conflit ; voir le skill nexus-pr-workflow, section synchronisation.")
    }
    if ($branch -notin @('develop', 'main')) {
        $behind = Invoke-Git @('rev-list', '--count', "HEAD..origin/develop")
        if ($behind -and [int]$behind -gt 0) {
            $lines.Add("La branche a $behind commit(s) de retard sur origin/develop (dernier fetch).")
        }
    }
}

$lines.Add('Validation d''itération : /nx-validate (mvnw clean install + scripts/self-smoke.ps1 + contrats).')
$lines -join "`n"
exit 0
