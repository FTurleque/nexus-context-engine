# Proposal

## Why

Le sprint 1 de l'audit du 17/10/2026 (« Remettre les mesures d'aplomb ») a pour critère de sortie des gates qui mesurent ce qu'ils annoncent. Six de ses huit constats sont fermés par les PR #265 à #268. Deux restent ouverts :

- `AUD-DEP-30` : sur ce dépôt public, les alertes Dependabot, les mises à jour de sécurité Dependabot, le secret scanning et la push protection sont désactivés (vérifié le 2026-10-29 avec `gh api`). Une CVE sur une dépendance déjà fusionnée n'est signalée qu'au prochain run OSV d'une PR, et un secret poussé par erreur n'est ni bloqué ni signalé.
- `AUD-DEP-14` (partie restante) : les filtres de chemins du run post-merge sont alignés (#265), mais « Hermetic scale benchmark » n'est pas un check requis. Il ne peut pas le devenir tant que `scale-benchmark.yml` filtre par chemins au niveau du déclencheur : un check requis absent laisse la PR en attente indéfinie.

Les tests d'évasion par symlink introduits par #267 créent aussi leur cible hors du `@TempDir` (`resolveSibling`) dans `NativeProjectCustomizationDetectorTest` et `AiSkillsRegistryProviderTest`. Ces fichiers ne sont jamais nettoyés.

## What Changes

- Réglages de sécurité du dépôt activés par le mainteneur, et leur traitement documenté.
- `scale-benchmark.yml` se déclenche sur toute PR, et une détection interne de changements conclut en succès, sans mesurer, quand aucun chemin surveillé n'est touché. Le check devient ainsi requis sans bloquer les autres PR.
- Les cibles « hors projet » des tests symlink sont créées dans le `@TempDir`, dans un répertoire distinct de la racine du projet simulé.

## Capabilities

### New Capabilities
- `repository-security-settings` : surveillance des dépendances et des secrets active sur le dépôt.
- `scale-benchmark-gate` : benchmark d'échelle produit sur toute PR et requis par la protection de `develop`.
- `test-filesystem-isolation` : les tests ne laissent aucun fichier hors de leur répertoire temporaire géré.

### Modified Capabilities

(aucune)

## Impact

- Réglages GitHub (action du mainteneur) : Code security et ruleset « Protect main & develop ».
- Workflows : `.github/workflows/scale-benchmark.yml`, `.github/workflows/develop-push-scale-benchmark.yml` si le filtre partagé change, contrat anti-dérive de #265 dans `scripts/release/test-operational-doc-contracts.sh`.
- Tests : `core/src/test/java/com/nexus/context/source/NativeProjectCustomizationDetectorTest.java`, `core/src/test/java/com/nexus/context/source/skill/AiSkillsRegistryProviderTest.java`.
- Documentation : `SECURITY.md` (traitement des alertes, aussi attendu par `AUD-DEP-17` au sprint 10), `docs/developer/scale-regression-benchmark.md`, `docs/developer/branch-governance.md`.
- Hors périmètre : constats des sprints 2 à 11.
