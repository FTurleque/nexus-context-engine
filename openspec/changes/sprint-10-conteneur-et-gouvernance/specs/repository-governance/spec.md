# Spec Delta

## Purpose

Définit les règles du dépôt GitHub qui ne dépendent pas d'un outil local : méthodes d'intégration acceptées sur `develop` et `main`, propriétaires déclarés des fichiers sensibles.

## ADDED Requirements

### Requirement: Seul le merge commit est accepté
Le ruleset protégeant `main` et `develop` SHALL n'autoriser que la méthode `merge`, et les réglages du dépôt SHALL désactiver le squash et le rebase.

#### Scenario: Lecture du ruleset
- **WHEN** le mainteneur lit le ruleset « Protect main & develop » avec `gh api`
- **THEN** `allowed_merge_methods` vaut `["merge"]`

#### Scenario: Lecture des réglages du dépôt
- **WHEN** le mainteneur lit `allow_squash_merge` et `allow_rebase_merge`
- **THEN** les deux valent `false`

#### Scenario: Documentation alignée
- **WHEN** `docs/developer/branch-governance.md` ne mentionne pas la restriction au merge commit
- **THEN** `scripts/release/test-operational-doc-contracts.sh` échoue

### Requirement: Les fichiers sensibles ont un propriétaire déclaré
`.github/CODEOWNERS` SHALL désigner un propriétaire pour `.github/workflows/`, `scripts/release/`, `config/tool-integrity.properties`, les wrappers Maven et `packaging/`.

#### Scenario: Fichier de workflow modifié
- **WHEN** une PR modifie un fichier sous `.github/workflows/`
- **THEN** GitHub affiche le propriétaire déclaré comme code owner du fichier
