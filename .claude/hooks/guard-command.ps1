# PreToolUse (Bash|PowerShell) : refuse les commandes qui contournent la gouvernance
# NEXUS (PR obligatoire, exact-head, wrapper Maven vérifié) ou qui corrompent les CRLF.

. (Join-Path $PSScriptRoot 'HookSupport.ps1')

$hookEvent = Read-HookInput
$command = Get-InputField $hookEvent 'command'
if ([string]::IsNullOrWhiteSpace($command)) {
    exit 0
}

# Les motifs ne visent que le début d'une commande (début de ligne ou après ; & | ( ) :
# un message de commit ou un corps de PR peut citer ces commandes sans les exécuter.
$start = '(?:^|[\n;&|(])\s*'

# Chaque règle : motif regex (insensible à la casse), décision, raison.
$rules = @(
    @{
        Pattern  = $start + 'git\s+push\b[^\n]*(\s--force(-with-lease)?\b|\s-f\b|\s\+\S)'
        Decision = 'deny'
        Reason   = 'Force-push interdit : develop et main refusent les non-fast-forward. Intégrer par merge (jamais rebase/force) puis pousser normalement.'
    },
    @{
        Pattern  = $start + 'git\s+push\b[^\n]*\s(\S+:)?(refs/heads/)?(develop|main)(\s|$)'
        Decision = 'deny'
        Reason   = 'Push direct vers develop/main interdit : le ruleset exige une pull request. Pousser une branche dédiée puis ouvrir une PR.'
    },
    @{
        Pattern  = $start + 'git\b[^\n]*(\s--no-verify\b|\s--no-gpg-sign\b|commit\.gpgsign=false)'
        Decision = 'deny'
        Reason   = 'Ne pas contourner les hooks ni la signature : corriger la cause de l''échec.'
    },
    @{
        Pattern  = $start + 'git\s+(rebase|add)\s+(-i\b|--interactive\b)'
        Decision = 'deny'
        Reason   = 'Commandes git interactives non supportées dans cet environnement.'
    },
    @{
        Pattern  = $start + 'gh\s+pr\s+merge\b(?![^\n]*--match-head-commit)'
        Decision = 'deny'
        Reason   = 'Merge exact-head obligatoire : ajouter --match-head-commit <sha> (le SHA dont les checks sont verts) et --merge.'
    },
    @{
        Pattern  = $start + 'gh\s+pr\s+merge\b[^\n]*\s--(squash|rebase)\b'
        Decision = 'deny'
        Reason   = 'Le dépôt intègre par merge commit : utiliser gh pr merge --merge --match-head-commit <sha>.'
    },
    @{
        Pattern  = $start + 'mvn(\.cmd)?\s'
        Decision = 'deny'
        Reason   = 'Utiliser le wrapper vérifié (./mvnw ou .\mvnw.cmd) : il contrôle l''archive Maven contre l''ancre SHA-512 versionnée.'
    },
    @{
        Pattern  = $start + 'sed\s+(-[a-zA-Z]*i|--in-place)[^\n]*\.(cmd|bat|ps1)\b'
        Decision = 'deny'
        Reason   = 'sed -i sous Git Bash retire les CRLF des scripts Windows (labels GOTO cassés). Utiliser l''outil Edit ou perl -pi.'
    },
    @{
        Pattern  = $start + 'git\s+(reset\s+--hard|clean\s+-[a-zA-Z]*f|checkout\s+--\s+\.|restore\s+\.)'
        Decision = 'ask'
        Reason   = 'Commande destructive sur la copie de travail : confirmer après avoir vérifié git status.'
    }
)

foreach ($rule in $rules) {
    if ($command -match "(?i)$($rule.Pattern)") {
        Write-PreToolDecision $rule.Decision $rule.Reason
    }
}
exit 0
