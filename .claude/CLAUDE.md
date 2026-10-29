# NEXUS — repères et outillage Claude Code

Les règles du projet sont dans `AGENTS.md`, que Claude Code charge directement ; ce fichier les complète pour l'outillage Claude Code. Il est exclu de l'indexation NEXUS par `.nexusignore` : comme instruction native, il passerait devant `AGENTS.md` dans les contextes sous budget serré (étape 9 de `scripts/self-smoke.ps1`). Il ne contient ni chemin local ni secret.

## Repères

- Cœur métier : `core/src/main/java/com/nexus` (Java 21, sans framework). Composition root : `NexusApplication`. Adaptateurs minces : `adapters/rest-quarkus`, `adapters/mcp-java`, `adapters/assistant-clients`.
- SQLite canonique (`persistence/sqlite`, migrations `core/src/main/resources/db/migration`), Lucene dérivé (`search/lucene`, `search/semantic`).
- Documentation courante en français : `docs/architecture.md`, `docs/roadmap.md`, `docs/developer/`. Décisions : `docs/architecture/adr/` (append-only).
- Les règles de `.claude/rules/` se chargent avec les fichiers qu'elles ciblent.

## Flux de travail

- Branche depuis `origin/develop`, PR vers `develop`, merge exact-head en merge commit ; `main` ne reçoit que la promotion de `develop` (skill `nexus-pr-workflow`).
- Valider avant toute PR : `/nx-validate` (contrats, `mvnw clean install`, `scripts/self-smoke.ps1`). Toujours le wrapper Maven, jamais `mvn`.
- Contrats documentaires et d'intégrité locaux : `bash .claude/scripts/contracts.sh`.

## Outillage du dépôt

| Besoin | Outil |
|---|---|
| Valider une itération | `/nx-validate [quick\|full]`, skill `nexus-validate` |
| Relire un changement | `/nx-review [PR]`, agents `nexus-reviewer`, `nexus-security-auditor` |
| Concevoir une évolution | `/nx-feature <description>`, agent `nexus-architect`, skill `nexus-new-capability` |
| Écrire des tests | agent `nexus-test-engineer` |
| CI rouge | `/nx-triage <PR>`, agent et skill `nexus-ci-triage` |
| Dépendances / Dependabot | `/nx-deps`, agent `nexus-dependency-steward`, skill `nexus-dependency-bump` |
| Migration SQLite | skill `nexus-schema-migration` |
| Politique de secrets | skill `nexus-redaction-change` |
| Décision d'architecture | `/nx-adr <sujet>`, skill `nexus-adr` |
| Qualité du contexte produit par NEXUS | skill `nexus-context-validation` |
| Doc courante et contrats | agent `nexus-doc-keeper` |
| Release develop → main | `/nx-release`, skill `nexus-pr-workflow` |
| Bilan d'entretien | `/nx-health` |

Les hooks du projet (`.claude/hooks/`) refusent le force-push, le push direct sur `develop`/`main`, `--no-verify`, `mvn` hors wrapper, `gh pr merge` sans `--match-head-commit`, `sed -i` sur les scripts Windows et la modification d'une migration publiée ; ils demandent confirmation avant de toucher un ADR accepté ou un audit daté. Leurs tests : `pwsh -NoProfile -File .claude/hooks/tests/Test-Hooks.ps1`.
