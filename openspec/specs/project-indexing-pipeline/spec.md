# project-indexing-pipeline Specification

## Purpose
Définit l'orchestration de l'indexation d'un projet : point d'entrée unique d'écriture, verrouillage, cycle de vie des writers Lucene et construction du service d'indexation.
Documentation durable : docs/developer/indexing-pipeline.md#openspec-project-indexing-pipeline

## Requirements

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
Le benchmark d'échelle SHALL comporter un scénario qui réindexe des fichiers existants par le chemin d'écriture de l'indexation (ouverture de session, mise en attente par lots, publication) sur une base déjà peuplée.

#### Scenario: Profil CI du benchmark
- **WHEN** le benchmark d'échelle s'exécute avec le profil `ci`
- **THEN** il rapporte la durée de réindexation incrémentale et de rebuild sur base peuplée

#### Scenario: Durée de la transaction de publication
- **WHEN** le benchmark de pic mémoire de l'indexation rapporte la durée de transaction d'écriture
- **THEN** cette durée est celle de la publication de la session, mesurée sur des analyses mises en attente

### Requirement: Un échec à la barrière de publication rend le projet à reconstruire
Si la publication canonique échoue après le commit des index dérivés, ou si le commit sémantique échoue après le commit lexical, l'indexation SHALL échouer, SHALL laisser le projet `FAILED` avec la génération canonique précédente et sans analyse en attente, et le passage suivant SHALL reconstruire entièrement le projet.

#### Scenario: Publication canonique en échec après le commit Lucene
- **GIVEN** un projet `READY` à la génération N
- **WHEN** une réindexation commit les index dérivés puis la publication canonique lève une exception
- **THEN** l'indexation échoue avec cette exception et le projet est `FAILED`
- **AND** la génération canonique reste N et aucune analyse en attente ne subsiste pour ce projet

#### Scenario: Commit sémantique en échec après le commit lexical
- **GIVEN** un projet `READY` avec recherche sémantique active
- **WHEN** le commit lexical réussit puis le commit sémantique lève une exception
- **THEN** l'indexation échoue, le projet est `FAILED` et la publication canonique n'a pas lieu
- **AND** la génération canonique est inchangée

#### Scenario: Passage suivant
- **GIVEN** un projet laissé `FAILED` par l'un de ces échecs
- **WHEN** l'indexation suivante réussit
- **THEN** elle reconstruit entièrement le projet et le laisse `READY` avec des index dérivés cohérents avec la génération publiée

#### Scenario: Aucun service avant READY
- **GIVEN** un projet laissé `FAILED` par l'un de ces échecs
- **WHEN** une recherche porte sur ce projet
- **THEN** elle est refusée par la garde de disponibilité sans lire les index dérivés

### Requirement: Le port d'indexation n'écrit les faits canoniques que par session
Le port `IndexRepository` SHALL n'exposer, pour écrire les analyses de fichiers d'un projet, que l'ouverture d'une session d'écriture qui met en attente puis publie ; il SHALL NOT exposer d'écriture directe des analyses.

#### Scenario: Surface du port
- **WHEN** on lit le port `IndexRepository`
- **THEN** il ne déclare aucune méthode qui applique des analyses de fichiers hors d'une session d'écriture

#### Scenario: Écriture dans un test
- **WHEN** un test doit écrire des analyses de fichiers
- **THEN** il passe par l'ouverture d'une session, la mise en attente et la publication
