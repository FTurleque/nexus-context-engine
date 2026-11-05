# Spec Delta

## Purpose

Définit les propriétés vérifiables du chemin d'écriture SQLite de l'indexation : plans de requête des suppressions par fichier, absence d'index redondants, préparation des instructions et rétention des analyses.

## ADDED Requirements

### Requirement: La suppression des relations d'un fichier utilise un index
La suppression des relations d'un fichier par `file_id` et `source_provider`, et la vérification de cascade depuis `indexed_files`, SHALL être servies par un index dont la première colonne est `file_id`.

#### Scenario: Plan de la suppression par fichier
- **WHEN** on exécute `EXPLAIN QUERY PLAN DELETE FROM symbol_relations WHERE file_id = ? AND source_provider = ?` sur une base migrée
- **THEN** le plan contient `SEARCH symbol_relations USING INDEX` et ne contient pas `SCAN symbol_relations`

#### Scenario: Plan de la cascade
- **WHEN** on exécute `EXPLAIN QUERY PLAN SELECT 1 FROM symbol_relations WHERE file_id = ?`
- **THEN** le plan utilise un index et ne parcourt pas la table

### Requirement: Le schéma ne porte pas d'index préfixe redondant
Le schéma migré SHALL NOT contenir d'index dont les colonnes sont un préfixe strict d'un autre index de la même table, et chaque requête servie auparavant par un tel index SHALL rester servie par un index.

#### Scenario: Liste des index après migration
- **WHEN** on liste les index de `symbol_relations`, `indexed_files` et `symbols` sur une base migrée
- **THEN** `idx_symbol_relations_project`, `idx_indexed_files_project` et `idx_symbols_file` sont absents

#### Scenario: Requêtes existantes
- **WHEN** le test de plan exécute les requêtes de projet et de fichier connues
- **THEN** aucune ne produit un `SCAN` de `symbol_relations`, `indexed_files` ou `symbols`

### Requirement: Les migrations d'index conservent les données
Les migrations qui créent ou suppriment des index SHALL NOT modifier les faits canoniques ni le statut d'indexation des projets.

#### Scenario: Upgrade d'une base peuplée
- **WHEN** une base peuplée à la migration précédente est migrée
- **THEN** les fichiers, symboles et relations sont inchangés et les projets gardent leur statut

### Requirement: L'écriture d'un fichier ne relit pas son identifiant
L'écriture d'un fichier indexé SHALL obtenir l'identifiant de `indexed_files` par l'upsert lui-même, sans requête de lecture supplémentaire, et SHALL réutiliser les instructions préparées pour tous les fichiers d'une même transaction.

#### Scenario: Écriture de plusieurs fichiers
- **WHEN** une transaction écrit N fichiers
- **THEN** l'upsert utilise `RETURNING id` et le nombre d'instructions préparées ne croît pas avec N

### Requirement: Les analyses en attente sont bornées par lot
Pendant une indexation, les analyses (symboles, relations) en attente d'écriture SQLite SHALL être bornées par le lot de publication de documents, et la génération d'index SHALL n'être publiée qu'à la fin de l'indexation.

#### Scenario: Rebuild complet
- **WHEN** un rebuild porte sur plus de fichiers modifiés qu'un lot de publication
- **THEN** les analyses sont écrites au rythme des lots et la recherche sert la génération précédente jusqu'à la publication finale

#### Scenario: Échec en cours d'indexation
- **WHEN** l'indexation échoue après l'écriture de plusieurs lots
- **THEN** la génération précédente reste celle servie et aucun fait partiel n'est visible
