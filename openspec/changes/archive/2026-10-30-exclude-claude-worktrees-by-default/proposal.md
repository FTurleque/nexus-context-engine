# Proposal

## Why

Un worktree Git créé par Claude Code sous `.claude/worktrees/<nom>/` est une copie complète du dépôt. NEXUS l'indexe comme du contenu du projet : son `AGENTS.md` et sa documentation concurrencent ceux du dépôt dans les `ContextBundle`. Le 2026-10-17, l'étape 9 du `scripts/self-smoke.ps1` a échoué pour cette raison (le contexte retenait `.claude/worktrees/…/AGENTS.md` au lieu de `AGENTS.md`).

Le moteur ne l'évite pas seul : `ProjectIgnoreMatcher` n'exclut que des répertoires d'un segment (`target`, `node_modules`…) et ne lit pas `.git/info/exclude`, seul endroit où Claude Code déclare ce répertoire. Le dépôt NEXUS le contourne par `.nexusignore`, mais tout autre projet utilisé avec des worktrees Claude Code reste exposé.

## What Changes

- `ProjectIgnoreMatcher` exclut par défaut tout chemin dont deux segments consécutifs sont `.claude` puis `worktrees`, à n'importe quelle profondeur, comme les exclusions intégrées existantes.
- Test de non-régression dans `ProjectScannerTest` (et dans le test du matcher si une borne s'y prête).
- Nouvel ADR qui complète l'ADR-0021 (liste intégrée d'exclusions) sans le réécrire.
- Mise à jour de la documentation courante qui décrit les exclusions intégrées.

## Capabilities

### New Capabilities
- `project-indexing-exclusions`: exclusions intégrées du scan de projet, indépendantes de `.gitignore` et `.nexusignore` (répertoires générés, fichiers sensibles, worktrees d'outils).

### Modified Capabilities

(aucune : `openspec/specs/` est vide, le comportement existant n'a pas encore de spec)

## Impact

- Code : `core/src/main/java/com/nexus/index/scan/ProjectIgnoreMatcher.java`.
- Tests : `core/src/test/java/com/nexus/index/scan/ProjectScannerTest.java`.
- Documentation : `docs/architecture/adr/` (nouvel ADR + index), `docs/developer/indexing-pipeline.md`.
- Comportement observable : les fichiers sous `.claude/worktrees/` disparaissent de l'index, de la recherche et des contextes. Aucune migration SQLite : un réindexage les retire.
- Hors périmètre : lecture de `.git/info/exclude`, exclusion d'autres outils (`.cursor/`, `.codex/`…).
