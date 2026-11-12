# Spec Delta

## Purpose
Définit les règles du dépôt GitHub qui ne dépendent pas d'un outil local : méthode d'intégration acceptée sur `main`, propriétaires déclarés des fichiers sensibles.

## MODIFIED Requirements

### Requirement: Seul le merge commit est accepté
Le ruleset protégeant `main` SHALL n'autoriser que la méthode `merge`, et les réglages du dépôt SHALL désactiver le squash et le rebase.

#### Scenario: Lecture du ruleset
- **WHEN** le mainteneur lit le ruleset « Protect main » avec `gh api`
- **THEN** `allowed_merge_methods` vaut `["merge"]`

#### Scenario: Lecture des réglages du dépôt
- **WHEN** le mainteneur lit `allow_squash_merge` et `allow_rebase_merge`
- **THEN** les deux valent `false`

#### Scenario: Documentation alignée
- **WHEN** `docs/developer/branch-governance.md` ne mentionne pas la restriction au merge commit
- **THEN** `scripts/release/test-operational-doc-contracts.sh` échoue
