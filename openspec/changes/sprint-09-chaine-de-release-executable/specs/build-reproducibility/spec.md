# Spec Delta

## Purpose

Définit les garanties de reproductibilité du build Maven et d'épinglage de son outillage, afin qu'un artefact publié puisse être recalculé depuis ses sources.

## ADDED Requirements

### Requirement: Les JAR et le ZIP de distribution sont reproductibles
Deux builds successifs du même commit avec le wrapper Maven SHALL produire un JAR CLI et un ZIP de distribution aux empreintes SHA-256 identiques.

#### Scenario: Deux builds successifs
- **WHEN** `./mvnw -B -ntp clean package -DskipTests` est exécuté deux fois sur le même commit
- **THEN** les SHA-256 du JAR CLI et du ZIP de `target/distribution/` sont identiques entre les deux exécutions

### Requirement: Les plugins du build ont une version explicite
Le build SHALL échouer si un plugin utilisé n'a pas de version explicite, y compris les plugins du cycle de vie.

#### Scenario: Plugin sans version
- **WHEN** un pom déclare un plugin sans version et sans entrée de `pluginManagement`
- **THEN** l'enforcer fait échouer le build

### Requirement: Les dépendances résolues respectent les bornes supérieures
Le build SHALL échouer si une dépendance transitive est résolue à une version inférieure à celle qu'un autre chemin du graphe exige, sauf exclusion listée et justifiée dans le pom.

#### Scenario: Borne supérieure violée
- **WHEN** une montée de dépendance fait résoudre un transitif sous la version requise par un autre chemin
- **THEN** l'enforcer fait échouer le build en nommant la dépendance

### Requirement: La version minimale de Maven suit le wrapper
Le build SHALL refuser une version de Maven inférieure à celle que fixe le wrapper.

#### Scenario: Maven trop ancien
- **WHEN** le build est lancé avec un Maven antérieur à 3.10.0
- **THEN** l'enforcer fait échouer le build
