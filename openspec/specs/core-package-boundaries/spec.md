# core-package-boundaries Specification

## Purpose
Définit les frontières technologiques du cœur `com.nexus` et la baseline de cycles de packages, vérifiées par le test d'architecture de la suite par défaut.
Documentation durable : docs/developer/architecture-implementation.md#openspec-core-package-boundaries

## Requirements

### Requirement: Le test d'architecture fait partie de la suite par défaut
Le module `core` SHALL contenir un test d'architecture exécuté par `./mvnw test` sans propriété d'activation, qui analyse le code de production du cœur sans dépendance de test supplémentaire, et dont chaque échec nomme la règle, la classe et l'import fautifs.

#### Scenario: Build standard
- **WHEN** on lance `./mvnw -B -ntp -pl core test`
- **THEN** le test d'architecture s'exécute et passe sur le code courant

#### Scenario: Message d'échec
- **WHEN** une règle est violée
- **THEN** le message cite le nom de la règle, la classe source et l'import interdit

### Requirement: Le cœur ne dépend d'aucun framework applicatif
Le code de production du cœur SHALL NOT importer de type des packages `io.quarkus`, `jakarta`, `javax.ws`, `org.springframework` ou `io.modelcontextprotocol`.

#### Scenario: Import Quarkus dans le cœur
- **WHEN** une classe de `com.nexus.search` importe `io.quarkus.runtime.Startup`
- **THEN** le test d'architecture échoue sur la règle `core-sans-framework`

### Requirement: Lucene est confiné à ses adaptateurs
Les types `org.apache.lucene` SHALL être importés uniquement depuis `com.nexus.search.lucene` et `com.nexus.search.semantic.lucene`.

#### Scenario: Lucene hors de son package
- **WHEN** une classe de `com.nexus.ranking` importe `org.apache.lucene.search.Query`
- **THEN** le test d'architecture échoue sur la règle `lucene-confine`

#### Scenario: Nom pleinement qualifié
- **WHEN** une classe hors des packages autorisés utilise `org.apache.lucene.document.Document` sans import
- **THEN** le test d'architecture échoue sur la règle `lucene-confine`

### Requirement: SQLite et JDBC sont confinés à la persistance
Les types `org.sqlite` et `java.sql` SHALL être importés uniquement depuis `com.nexus.persistence.sqlite`, à l'exception d'une liste fermée, versionnée dans le test, d'usages de `java.sql.SQLException` dans la composition et la CLI.

#### Scenario: JDBC dans le contexte
- **WHEN** une classe de `com.nexus.context` importe `java.sql.Connection`
- **THEN** le test d'architecture échoue sur la règle `sqlite-confine`

#### Scenario: Exception autorisée
- **WHEN** `com.nexus.application.NexusApplicationFactory` importe `java.sql.SQLException`
- **THEN** la règle `sqlite-confine` passe

#### Scenario: Usage hors de la liste fermée
- **WHEN** une classe de `com.nexus.application` absente de la liste fermée importe `java.sql.SQLException`
- **THEN** le test d'architecture échoue sur la règle `sqlite-confine`

### Requirement: Le package project est une feuille
Le package `com.nexus.project` SHALL NOT importer de type d'un autre package `com.nexus`.

#### Scenario: Dépendance montante
- **WHEN** une classe de `com.nexus.project` importe un type de `com.nexus.index`
- **THEN** le test d'architecture échoue sur la règle `project-feuille`

### Requirement: Les cycles de packages ne peuvent que décroître
Le test d'architecture SHALL comparer les arêtes de cycle du graphe des packages du cœur à une baseline versionnée : une arête de cycle absente de la baseline SHALL faire échouer le test, et une entrée de baseline qui n'est plus une arête de cycle SHALL aussi le faire échouer.

#### Scenario: Nouveau cycle
- **WHEN** une modification introduit une dépendance qui crée un cycle absent de la baseline
- **THEN** le test d'architecture échoue et nomme les packages du cycle

#### Scenario: Cycle cassé sans mise à jour de la baseline
- **WHEN** une modification casse un cycle listé dans la baseline sans retirer l'entrée
- **THEN** le test d'architecture échoue et demande de retirer l'entrée

### Requirement: La politique de sortie ne dépend pas du package security
La politique d'exposition des contextes SHALL résider hors du package `com.nexus.security`, et ce package SHALL NOT importer de type du package `com.nexus.context`.

#### Scenario: Contrôle des imports
- **WHEN** on recherche `import com.nexus.context` dans `core/src/main/java/com/nexus/security/`
- **THEN** aucune occurrence n'est trouvée

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
