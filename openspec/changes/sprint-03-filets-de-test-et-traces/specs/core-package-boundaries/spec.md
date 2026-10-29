# Spec Delta

## Purpose

Définit les frontières technologiques du cœur `com.nexus` et la baseline de cycles de packages, vérifiées par le test d'architecture de la suite par défaut.

## ADDED Requirements

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
