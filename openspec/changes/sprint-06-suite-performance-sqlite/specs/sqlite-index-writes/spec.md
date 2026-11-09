# Spec Delta

## ADDED Requirements

### Requirement: La publication du staging pagine par un index
La requête qui lit une page des fichiers en attente d'un projet pendant la publication SHALL être servie par un index qui fournit l'ordre de pagination, sans arbre temporaire de tri, sur un schéma migré sans `ANALYZE`.

#### Scenario: Plan de la page de staging
- **GIVEN** une base migrée à la dernière version, sans statistiques `ANALYZE`
- **WHEN** on relève `EXPLAIN QUERY PLAN` de la requête de page des fichiers en attente
- **THEN** le plan cherche par index sur le projet et la clé de pagination
- **AND** le plan ne contient pas `USE TEMP B-TREE FOR ORDER BY`

#### Scenario: Migration sur une base existante
- **GIVEN** une base à la version précédente contenant des fichiers en attente
- **WHEN** la migration s'applique
- **THEN** les fichiers en attente, les faits canoniques et la génération du projet sont inchangés
- **AND** aucun rebuild n'est imposé
