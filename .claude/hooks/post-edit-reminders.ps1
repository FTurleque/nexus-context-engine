# PostToolUse (Edit|Write) : rappelle les effets de bord NEXUS d'une modification
# (contrats documentaires, versions de politique, ancres) et vérifie les fins de ligne.

. (Join-Path $PSScriptRoot 'HookSupport.ps1')

$hookEvent = Read-HookInput
$filePath = Get-InputField $hookEvent 'file_path'
$path = Get-RepositoryPath $filePath
if ($null -eq $path) {
    exit 0
}

$messages = [System.Collections.Generic.List[string]]::new()

$reminders = @(
    @{ Pattern = '(^|/)pom\.xml$'
       Text = 'pom.xml modifié : si une version documentée change (Quarkus, SDK MCP, Java), aligner docs/developer/{README,architecture-implementation,rest-api}.md et lancer bash .claude/scripts/contracts.sh.' },
    @{ Pattern = '^core/src/main/java/com/nexus/security/(SensitiveContentRedactor|SecretAssignmentScanner)\.java$'
       Text = 'Politique de redaction modifiée : incrémenter POLICY_VERSION, ajouter une migration d''invalidation, incrémenter CONTENT_PROFILE_VERSION et la doc content-vN (skill nexus-redaction-change).' },
    @{ Pattern = '^core/src/main/java/com/nexus/search/semantic/SemanticIndexingService\.java$'
       Text = 'Profil sémantique : tout changement de CONTENT_PROFILE_VERSION se reflète dans la doc content-vN et dans test-operational-doc-contracts.sh.' },
    @{ Pattern = '^core/src/main/resources/db/migration/'
       Text = 'Nouvelle migration : vérifier numérotation, test de migration, doc release-and-recovery et ADR si le format persistant change (skill nexus-schema-migration).' },
    @{ Pattern = '^\.github/workflows/'
       Text = 'Workflow modifié : actions épinglées par SHA + commentaire de version ; codeql init/analyze à la même version ; vérifier que les noms des checks obligatoires restent inchangés.' },
    @{ Pattern = '^(mvnw|mvnw\.cmd|\.mvn/|config/tool-integrity\.properties)'
       Text = 'Bootstrap Maven modifié : lancer bash scripts/release/test-tool-integrity-anchors.sh puis un build complet via le wrapper.' },
    @{ Pattern = '^adapters/rest-quarkus/src/main/java/com/nexus/api/NexusRest(Security|ExposureGuard)\.java$'
       Text = 'Sécurité REST modifiée : lancer bash scripts/release/test-final-audit-contracts.sh et revoir docs/developer/rest-api.md.' },
    @{ Pattern = '^docs/architecture/adr/\d{4}-'
       Text = 'ADR modifié/ajouté : mettre à jour l''index docs/architecture/adr/README.md.' }
)

foreach ($reminder in $reminders) {
    if ($path -match $reminder.Pattern) {
        $messages.Add($reminder.Text)
    }
}

# Les scripts batch/PowerShell doivent garder leurs CRLF dans la copie de travail Windows.
if ($IsWindows -and $path -match '\.(cmd|bat)$' -and (Test-Path -LiteralPath $filePath -PathType Leaf)) {
    $bytes = [System.IO.File]::ReadAllBytes($filePath)
    $lf = 0; $crlf = 0
    for ($i = 0; $i -lt $bytes.Length; $i++) {
        if ($bytes[$i] -eq 10) {
            if ($i -gt 0 -and $bytes[$i - 1] -eq 13) { $crlf++ } else { $lf++ }
        }
    }
    if ($lf -gt 0) {
        $messages.Add("$path contient $lf fin(s) de ligne LF sans CR : cmd.exe ne trouvera pas ses labels. Restaurer les CRLF.")
    }
}

Write-AdditionalContext 'PostToolUse' $messages.ToArray()
