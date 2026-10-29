# Spec Delta

## Purpose

Définit les règles de dépendance entre packages du cœur `com.nexus`, vérifiées par le test d'architecture, afin que les capacités indexation, recherche, ranking, contexte et sécurité restent séparables.

## ADDED Requirements

### Requirement: Le graphe des packages du cœur est acyclique
Le graphe des dépendances entre packages du code de production du cœur SHALL ne contenir aucun cycle, et le test d'architecture SHALL échouer dès qu'un cycle apparaît, sans baseline de cycles tolérés.

#### Scenario: Graphe courant
- **WHEN** le test d'architecture analyse le code de production du cœur
- **THEN** il ne trouve aucune composante fortement connexe de plus d'un package

#### Scenario: Cycle introduit
- **WHEN** une classe de `com.nexus.ranking` importe un type de `com.nexus.search`
- **THEN** le test d'architecture échoue et nomme les packages du cycle

### Requirement: Le modèle et les ports d'indexation ne dépendent pas de la recherche
Le package `com.nexus.index` (modèle et ports) SHALL NOT importer de type de `com.nexus.search` ni de ses sous-packages ; le service d'indexation SHALL dépendre d'un port d'indexation sémantique plutôt que d'une classe concrète.

#### Scenario: Imports du package index
- **WHEN** le test d'architecture contrôle les imports des classes du package `com.nexus.index`
- **THEN** aucun ne désigne `com.nexus.search` ou un de ses sous-packages

#### Scenario: Indexation sémantique désactivée
- **WHEN** l'application est composée sans recherche sémantique
- **THEN** le service d'indexation reçoit une implémentation sans effet du port, et l'indexation lexicale produit le même rapport qu'avant

### Requirement: Les sources de contexte ne dépendent pas du builder de contexte
Les packages `com.nexus.context.source` et ses sous-packages SHALL NOT importer de type du package `com.nexus.context` ; les types de modèle de contexte qu'ils partagent avec le builder SHALL résider dans un package feuille.

#### Scenario: Imports des sources
- **WHEN** le test d'architecture contrôle les imports de `com.nexus.context.source`, `.git` et `.skill`
- **THEN** aucun ne désigne le package `com.nexus.context`

### Requirement: Le ranking ne dépend pas du service de recherche
Les packages `com.nexus.ranking` et `com.nexus.ranking.graph` SHALL NOT importer de type du package `com.nexus.search` ; les candidats et signaux qu'ils manipulent SHALL résider dans un package de modèle dont dépendent la recherche et le ranking.

#### Scenario: Imports du ranking
- **WHEN** le test d'architecture contrôle les imports de `com.nexus.ranking` et `com.nexus.ranking.graph`
- **THEN** aucun ne désigne le package `com.nexus.search`

### Requirement: Le port d'embeddings ne construit pas son adaptateur
Le package `com.nexus.search.semantic` SHALL NOT importer de type de `com.nexus.search.semantic.ollama` ; le fournisseur d'embeddings SHALL être construit par le composition root.

#### Scenario: Imports du port sémantique
- **WHEN** le test d'architecture contrôle les imports de `com.nexus.search.semantic`
- **THEN** aucun ne désigne `com.nexus.search.semantic.ollama`

#### Scenario: Configuration Ollama inchangée
- **WHEN** l'application est créée avec les variables d'environnement sémantiques existantes
- **THEN** le fournisseur d'embeddings obtenu a le même endpoint, le même modèle et les mêmes bornes qu'avant le déplacement

### Requirement: Le découpage ne change aucun comportement observable
Les déplacements de packages SHALL laisser inchangés les réponses CLI, REST et MCP et les formats persistants SQLite et Lucene.

#### Scenario: Contrats publics
- **WHEN** les tests de contrat REST et MCP et le self-smoke sont exécutés après le découpage
- **THEN** ils passent sans modification de leurs valeurs attendues
