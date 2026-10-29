# Spec Delta

## Purpose

Définit le périmètre de travail de la recherche de symboles SQLite, afin que son coût dépende du projet ciblé et du nombre de termes de la requête, et non du nombre total de symboles de la base.

## ADDED Requirements

### Requirement: La recherche de symboles est scopée projet avant le tri
La recherche de symboles SHALL restreindre la recherche plein texte et le préfiltre approché au projet ciblé par un index dont la première colonne identifie le projet, et SHALL NOT parcourir l'index des symboles de tous les projets.

#### Scenario: Plan de requête
- **WHEN** le plan `EXPLAIN QUERY PLAN` de la requête de préfiltre approché est calculé sur une base de 25 projets
- **THEN** il utilise l'index scopé projet et ne contient aucun parcours complet de la table ou de l'index des symboles

#### Scenario: Résultats inchangés
- **WHEN** la même recherche de symboles est exécutée avant et après la migration
- **THEN** les symboles rendus et leur ordre sont identiques

### Requirement: Les termes d'une requête sont interrogés en lot
La recherche de symboles SHALL interroger l'ensemble des termes d'une requête en un nombre de requêtes SQL qui ne croît pas avec le nombre de termes.

#### Scenario: Requête de plusieurs termes
- **WHEN** une requête de 8 termes est recherchée dans un projet
- **THEN** le nombre de requêtes SQL exécutées est le même que pour une requête d'un terme
