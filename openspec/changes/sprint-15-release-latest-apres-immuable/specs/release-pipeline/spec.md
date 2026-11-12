# Spec Delta

## ADDED Requirements

### Requirement: Le badge Latest n'est posé qu'après le contrôle d'immuabilité
`scripts/release/publish-github-release-assets.sh` SHALL publier toute release GitHub sans la désigner Latest. Pour une version finale dont le champ `immutable` vaut `true`, il SHALL ensuite la désigner Latest et vérifier qu'elle l'est, avant le déplacement du tag d'image `latest`. Une pré-version et une release non immuable SHALL NOT être désignées Latest.

#### Scenario: Version finale immuable
- **GIVEN** une version finale `vX.Y.Z` en brouillon dont tous les assets sont vérifiés, et une release précédente désignée Latest
- **WHEN** le script publie la release et lit `immutable` à `true`
- **THEN** la publication demande `make_latest` à `false`, puis la release `vX.Y.Z` est désignée Latest après la lecture de `immutable`
- **AND** le script sort en 0

#### Scenario: Version finale non immuable
- **GIVEN** une version finale `vX.Y.Z` en brouillon et une release précédente désignée Latest
- **WHEN** le script publie la release et lit `immutable` à `false`
- **THEN** il sort en 79 et la release précédente reste désignée Latest
- **AND** l'étape qui déplace le tag d'image `latest` ne s'exécute pas

#### Scenario: Pré-version
- **GIVEN** une pré-version `vX.Y.Z-rc.N` immuable et une release précédente désignée Latest
- **WHEN** le script termine la publication
- **THEN** la release précédente reste désignée Latest et le script sort en 0

#### Scenario: Reprise d'une version finale publiée hors Latest
- **GIVEN** une version finale déjà publiée et immuable qui n'est pas désignée Latest
- **WHEN** le script est relancé avec les mêmes assets
- **THEN** il la désigne Latest sans téléverser d'asset ni changer son état brouillon ou pré-version, et sort en 0

#### Scenario: Reprise d'une version finale déjà Latest
- **GIVEN** une version finale publiée, immuable et déjà désignée Latest
- **WHEN** le script est relancé avec les mêmes assets
- **THEN** il sort en 0 sans aucune modification de la release

#### Scenario: Badge non posé
- **GIVEN** une version finale immuable dont la désignation Latest échoue ou ne désigne pas son tag à la relecture
- **WHEN** le script termine la publication
- **THEN** il sort en 80 avec un message qui nomme le tag
- **AND** l'étape qui déplace le tag d'image `latest` ne s'exécute pas

#### Scenario: Relance d'une version finale plus ancienne
- **GIVEN** une version finale `vX.Y.Z` publiée et immuable, et une version finale plus récente désignée Latest
- **WHEN** le job `publish` de `vX.Y.Z` est relancé avec les mêmes assets
- **THEN** le script désigne `vX.Y.Z` Latest sans comparer les versions ni téléverser d'asset, et sort en 0
- **AND** l'étape suivante déplace le tag d'image `latest` sur `vX.Y.Z`

## MODIFIED Requirements

### Requirement: Les releases publiées sont immuables
Le dépôt SHALL avoir les releases immuables activées. Après avoir publié ou retrouvé publiée une release GitHub, `scripts/release/publish-github-release-assets.sh` SHALL vérifier que son champ `immutable` vaut `true` et SHALL échouer sinon, avant le déplacement de `latest`.

#### Scenario: Lecture du réglage
- **WHEN** le mainteneur exécute `gh api repos/FTurleque/nexus-context-engine/immutable-releases`
- **THEN** la réponse est un code 200 dont le champ `enabled` vaut `true`

#### Scenario: Release publiée non immuable
- **GIVEN** une release dont le champ `immutable` vaut `false` après sa sortie du brouillon
- **WHEN** le script de publication termine la publication
- **THEN** il sort avec un code non nul distinct des codes existants et un message qui nomme le tag
- **AND** l'étape qui déplace `latest` ne s'exécute pas

#### Scenario: Release immuable
- **GIVEN** une release dont le champ `immutable` vaut `true` après sa sortie du brouillon
- **WHEN** le script de publication termine la publication
- **THEN** il sort en 0 sans téléverser, remplacer ni supprimer d'asset
- **AND** la seule modification postérieure à la lecture de `immutable` est la désignation Latest d'une version finale
