# Outillage Claude Code du dépôt

Le dépôt versionne une configuration Claude Code destinée au développement et à la maintenance de NEXUS. Elle n'est pas utilisée par le runtime NEXUS et ne crée aucune dépendance du produit envers un fournisseur d'agent (ADR-0017).

## Contenu

```text
AGENTS.md                     instructions du projet (lues par Claude Code)
.claude/CLAUDE.md             repères, flux de travail et table de l'outillage Claude Code
.claude/settings.json         permissions et hooks partagés
.claude/rules/                règles chargées selon les fichiers touchés
.claude/agents/               sous-agents spécialisés
.claude/skills/               procédures réutilisables (Agent Skills)
.claude/commands/             commandes /nx-*
.claude/hooks/                hooks PowerShell 7 et leurs tests
.claude/scripts/contracts.sh  contrats CI exécutables localement
```

`.claude/settings.local.json` reste local et ignoré par Git.

## Interaction avec NEXUS

Conformément à l'ADR-0033, NEXUS sépare les sources contextuelles des configurations opérationnelles :

- `.claude/skills/*/SKILL.md` sont des sources contextuelles que NEXUS peut sélectionner dans un `ContextBundle` ; ils ne contiennent donc ni chemin local, ni secret ;
- `.claude/settings.json`, les hooks et les commandes sont des configurations opérationnelles : NEXUS ne les injecte pas et n'exécute jamais leurs scripts ;
- `.claude/CLAUDE.md` est exclu par `.nexusignore`. NEXUS le traiterait sinon comme instruction native de priorité 80 : sous un budget serré, il ne retient qu'une instruction et ce fichier passerait devant `AGENTS.md`, cas que l'étape 9 du self-smoke refuse. Il n'importe pas non plus `@AGENTS.md`, que Claude Code charge déjà.

Le skill de dogfooding `nexus-context-validation` vit dans `.claude/skills/` : il est à la fois utilisable par Claude Code et sélectionné par l'étape 11 du self-smoke. Toute évolution de `.claude/CLAUDE.md` ou des skills doit être suivie d'un `scripts/self-smoke.ps1`.

## Hooks

Prérequis : PowerShell 7 (`pwsh`) dans le `PATH`.

| Hook | Effet |
|---|---|
| `SessionStart` | résume branche, copie de travail et divergence `main`/`develop` (sans réseau) |
| `PreToolUse` Bash/PowerShell | refuse force-push, push direct `develop`/`main`, `--no-verify`, `git rebase -i`, `mvn` hors wrapper, `gh pr merge` sans `--match-head-commit` ou en squash/rebase, `sed -i` sur `.cmd`/`.ps1` ; demande confirmation avant `git reset --hard`/`git clean -f` |
| `PreToolUse` Edit/Write | refuse l'écriture de secrets et la modification d'une migration publiée ; demande confirmation pour un ADR accepté, un audit daté ou une ancre d'intégrité |
| `PostToolUse` Edit/Write | rappelle les effets de bord (contrats doc, versions de politique, ancres, index ADR) et détecte les fins de ligne LF dans les `.cmd` |

Tests de non-régression :

```powershell
pwsh -NoProfile -File .claude/hooks/tests/Test-Hooks.ps1
```

## Maintenance

- Une nouvelle règle de gouvernance (check obligatoire, borne, procédure) se reflète dans la règle, le skill ou le hook concerné, et dans cette page.
- Un nouveau hook ou une nouvelle règle de garde s'accompagne d'un cas dans `Test-Hooks.ps1`.
