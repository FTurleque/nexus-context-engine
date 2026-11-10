# Spec Delta

## Purpose

Définit les contrôles statiques que la documentation OpenSpec du dépôt subit à chaque build, pour que les liens qui relient un changement, actif ou archivé, à ses pages durables restent valides.
Documentation durable : docs/developer/openspec.md#openspec-openspec-documentation-contracts

## ADDED Requirements

### Requirement: Les liens relatifs des changements archivés sont contrôlés
Le contrat OpenSpec SHALL vérifier que chaque lien relatif des fichiers Markdown de `openspec/changes/`, archives comprises, désigne un fichier existant et, pour une ancre, un titre existant de ce fichier.

#### Scenario: Lien d'archive cassé
- **GIVEN** un `design.md` archivé qui pointe vers `../../../docs/developer/git-context.md`
- **WHEN** `bash scripts/release/test-openspec-doc-contracts.sh` s'exécute
- **THEN** le contrat échoue en nommant le fichier et le lien
- **AND** la commande se termine avec un code non nul

#### Scenario: Ancre inexistante
- **GIVEN** un lien d'archive vers `docs/developer/secret-redaction.md#section-absente`
- **WHEN** le contrat s'exécute
- **THEN** le contrat échoue en signalant l'ancre introuvable

#### Scenario: Archives conformes
- **WHEN** tous les liens relatifs des changements actifs et archivés résolvent
- **THEN** le contrat passe
