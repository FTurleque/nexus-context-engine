# PreToolUse (Edit|Write) : protège les artefacts append-only ou sensibles du dépôt.

. (Join-Path $PSScriptRoot 'HookSupport.ps1')

$hookEvent = Read-HookInput
$filePath = Get-InputField $hookEvent 'file_path'
$path = Get-RepositoryPath $filePath
if ($null -eq $path) {
    exit 0
}
$exists = Test-Path -LiteralPath $filePath -PathType Leaf

if ($path -match '(^|/)\.env(\.|$)|\.(key|pem|p12|pfx)$|(^|/)secrets/') {
    Write-PreToolDecision 'deny' "Fichier de secrets ($path) : ne jamais l'écrire depuis l'agent."
}

if ($exists -and $path -match '^core/src/main/resources/db/migration/V\d+__.+\.sql$') {
    Write-PreToolDecision 'deny' "Migration existante ($path) : les migrations sont append-only. Créer la migration suivante (skill nexus-schema-migration)."
}

if ($exists -and $path -match '^docs/architecture/adr/archive/.+\.md$') {
    Write-PreToolDecision 'ask' "ADR archivé ($path) : historique, normalement non réécrit (GEN-0057)."
}

if ($exists -and $path -match '^docs/architecture/adr/(ARCH|BE|DATA|GEN|SEC)-\d{4}-.+\.md$') {
    $head = (Get-Content -LiteralPath $filePath -TotalCount 15) -join "`n"
    if ($head -match '(?i)statut\W*:\s*accept') {
        Write-PreToolDecision 'ask' "ADR accepté ($path) : ne pas réécrire la décision. Seuls un lien « Remplacé par / Complété par » ou une correction typographique sont admis ; sinon créer un nouvel ADR (skill nexus-adr)."
    }
}

if ($exists -and $path -match '^docs/project/(audits|history)/') {
    Write-PreToolDecision 'ask' "Relevé daté ($path) : historique, normalement non réécrit."
}

if ($path -eq 'config/tool-integrity.properties') {
    Write-PreToolDecision 'ask' 'Ancre d''intégrité : la valeur doit provenir du SHA publié par la source officielle et être recalculée localement.'
}

exit 0
