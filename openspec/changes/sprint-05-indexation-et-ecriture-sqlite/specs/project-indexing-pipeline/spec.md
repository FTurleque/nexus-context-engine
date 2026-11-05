# Spec Delta

## Purpose

Définit l'orchestration de l'indexation d'un projet : point d'entrée unique d'écriture, verrouillage, cycle de vie des writers Lucene et construction du service d'indexation.

## ADDED Requirements

### Requirement: Toute écriture d'index passe par ProjectIndexingService
Toute écriture d'index d'un projet, y compris l'import d'intelligence de code MINOS, SHALL passer par `ProjectIndexingService` et SHALL appliquer le même verrouillage single-flight et inter-processus que l'indexation.

#### Scenario: Import MINOS pendant une indexation
- **WHEN** un import MINOS est demandé pendant une indexation du même projet
- **THEN** il est refusé comme une indexation concurrente, avec le même diagnostic

#### Scenario: Lecture du payload par la CLI
- **WHEN** la CLI importe un payload MINOS
- **THEN** elle le lit par la façade, avec les mêmes bornes que REST et MCP, sans appeler `MinosCodeIndexImporter` directement

### Requirement: Le service d'indexation exige un gestionnaire de verrou explicite
`ProjectIndexingService` SHALL exposer un seul constructeur public, qui SHALL exiger un `ProjectIndexLockManager` explicite.

#### Scenario: Composition de production
- **WHEN** `NexusApplicationFactory` construit `ProjectIndexingService`
- **THEN** elle fournit le gestionnaire de verrou inter-processus et aucun constructeur public ne retombe sur `processLocalOnly()`

### Requirement: Un writer Lucene par indexation, committé à la publication
L'index lexical et l'index sémantique SHALL utiliser un seul `IndexWriter` par indexation, SHALL ne committer qu'à la barrière de publication, et SHALL annuler les écritures non committées si l'indexation échoue.

#### Scenario: Indexation de plusieurs lots
- **WHEN** une indexation publie plus de 128 fichiers, soit plusieurs lots
- **THEN** chaque index n'ouvre qu'un writer et ne committe qu'une fois

#### Scenario: Échec avant publication
- **WHEN** l'indexation échoue après plusieurs lots
- **THEN** les recherches lexicales et sémantiques servent le contenu de la génération précédente

### Requirement: La réindexation sur base peuplée est mesurée
Le benchmark d'échelle SHALL comporter un scénario qui réindexe des fichiers existants par le chemin d'écriture de l'indexation sur une base déjà peuplée.

#### Scenario: Profil CI du benchmark
- **WHEN** le benchmark d'échelle s'exécute avec le profil `ci`
- **THEN** il rapporte la durée de réindexation incrémentale et de rebuild sur base peuplée
