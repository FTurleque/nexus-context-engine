# Proposition

## Why

Le dépôt intègre sur `develop` puis promeut vers `main` pour chaque release. Ce double passage ajoute une PR de promotion et un tour de CI complet par release, et une source de divergence quand un correctif arrive directement sur `main` (section « Resynchroniser main → develop » du skill `nexus-pr-workflow`). Le mainteneur, seul contributeur, juge que `develop` lui coûte plus qu'elle ne protège et demande de la supprimer.

Source : demande du mainteneur du 2026-11-12 ; état relevé le même jour : `main` et `develop` ont le même contenu après la promotion FTurleque/nexus-context-engine#381 (`git rev-list --count origin/main..origin/develop` = 0), et le ruleset 20503835 s'appelle « Protect main » et ne cible plus que `~DEFAULT_BRANCH` (`gh api repos/FTurleque/nexus-context-engine/rulesets/20503835`).

## What Changes

- [Existant vérifié] Le ruleset 20503835 « Protect main » protège `main` (branche par défaut) : PR obligatoire, merge commit seul, pas de suppression ni de force-push, huit checks requis dont « Hermetic scale benchmark » (relevé `gh api` du 2026-11-12). `develop` n'est plus protégée (`gh api .../branches/develop/protection` : 404).
- [Existant vérifié] Dix-neuf workflows de `.github/workflows/` déclenchent sur `develop` (`pull_request`, `push`, ou les deux) ; quatre enveloppes `develop-push-*.yml` relancent sur un push de `develop` les qualifications que leur workflow appelé ne lance que sur PR.
- [Existant vérifié] `.github/workflows/docker-distribution.yml:37-55` écoute déjà les push sur `main` avec les mêmes chemins que `develop-push-docker-distribution.yml` (plus `publish-github-release-assets.sh`).
- [Existant vérifié] `.github/dependabot.yml:5,13,21` cible `develop` ; `.archgate/config.json:5` compare à `origin/develop` ; les hooks `.claude/hooks/guard-command.ps1:21-26` et `session-context.ps1:28-43`, le skill `nexus-pr-workflow`, les commandes `/nx-*` et la documentation courante décrivent le flux par `develop`.
- [Existant vérifié] BE-0084 (accepté) fait exécuter le corpus sémantique `full` sur un push de `develop` ; GEN-0057 (accepté) déclare la base Archgate `origin/develop`.
- [Exigence] Toute PR cible `main` ; les checks de PR et les qualifications post-merge s'exécutent pour `main`.
- [Exigence] Le corpus sémantique complet et le benchmark d'échelle post-merge s'exécutent sur un push de `main`.
- [Décision] ADR GEN-0089 « Intégrer et publier depuis la seule branche main » ; BE-0090 consolide BE-0084 sur `main` et le remplace ; GEN-0057 n'est pas réécrit : il reçoit le lien « Complété par : GEN-0089 » et un écart constaté sur sa base Archgate (choix du mainteneur du 2026-11-12, règle 5 de GEN-0057).
- [Décision] Les enveloppes `develop-push-scale-benchmark.yml`, `develop-push-scanner-corpus-benchmark.yml` et `develop-push-windows-installer.yml` deviennent `main-push-*.yml` ; `develop-push-docker-distribution.yml` est supprimée, `docker-distribution.yml` couvrant déjà le push sur `main`.
- [Écart] `openspec/specs/repository-governance/spec.md` nomme encore le ruleset « Protect main & develop » ; `openspec/specs/scale-benchmark-gate/spec.md` exige le check sur la protection de `develop`, qui n'existe plus.
- **BREAKING** (processus) : une PR vers `develop` n'est plus qualifiée ; la promotion `develop → main` disparaît. Une release se pose par tag `vX.Y.Z` sur un SHA de `main`, comme aujourd'hui.

## Type de changement

outillage

## Périmètre et exclusions

**Dans le périmètre :**
- Déclencheurs des workflows GitHub, Dependabot, base Archgate.
- Hooks, skills, agents, commandes et règles Claude Code du dépôt.
- Documentation courante (`README.md`, `SECURITY.md`, `docs/architecture/`, `docs/developer/`, `docs/project/governance/ROADMAP.md`), ADR, specs OpenSpec et contrats documentaires.
- Suppression de la branche `develop` sur GitHub et en local, après le merge (geste mainteneur).

**Hors périmètre :**
- Documents datés (`docs/project/history/`, `docs/project/audits/`, changements archivés, ADR archivés) : ils décrivent l'état de leur date.
- Règles du ruleset « Protect main » : déjà conformes (huit checks requis, merge seul).
- Politique des tags et de release (`release.yml`, `validate-release-tag.sh`) : elle se fonde déjà sur `main`.

## Capabilities

### New Capabilities

### Modified Capabilities
- `repository-governance`: le ruleset protège `main` seule, sous le nom « Protect main ».
- `scale-benchmark-gate`: le check est produit sur toute PR vers `main`, le run post-merge suit `main` et la protection de `main` exige le check.
- `adr-rule-enforcement`: `archgate check` s'exécute sur toute PR vers `main`.
- `semantic-search-qualification`: le corpus complet s'exécute après fusion dans `main`.

## Impact

- `.github/workflows/*.yml`, `.github/dependabot.yml`, `.archgate/config.json`.
- `.claude/CLAUDE.md`, `.claude/hooks/`, `.claude/skills/`, `.claude/agents/`, `.claude/commands/`, `.claude/rules/`, `.claude/agent-memory/`.
- `README.md`, `SECURITY.md`, `docs/architecture/` (ADR GEN-0089, BE-0090, GEN-0057, arc42), `docs/developer/`, `docs/project/governance/ROADMAP.md`.
- `scripts/release/test-operational-doc-contracts.sh`, `scripts/release/test-final-audit-contracts.sh`, `.claude/hooks/tests/Test-Hooks.ps1`.
