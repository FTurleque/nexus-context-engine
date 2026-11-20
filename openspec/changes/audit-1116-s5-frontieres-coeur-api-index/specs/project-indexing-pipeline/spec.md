# Spec Delta

## ADDED Requirements

### Requirement: Les index dérivés n'écrivent les documents que par session
Les ports d'index lexical et sémantique SHALL n'exposer, pour écrire les documents d'un projet, que l'ouverture d'une session qui applique des lots puis committe ou annule ; ils SHALL NOT exposer de reconstruction ni d'application de lot hors d'une session, et aucune session de repli qui applique chaque lot immédiatement SHALL NOT exister dans le code de production.

#### Scenario: Surface des ports
- **WHEN** on lit les ports d'index lexical et sémantique
- **THEN** aucune méthode n'écrit de documents hors de l'ouverture d'une session
- **AND** l'ouverture de session n'a pas d'implémentation par défaut dans le port

#### Scenario: Écriture dans un test
- **WHEN** un test doit écrire des documents dans un index lexical ou sémantique
- **THEN** il ouvre une session, applique ses lots et committe, directement ou par une fabrique de test

#### Scenario: Rebuild par session
- **GIVEN** un projet déjà indexé
- **WHEN** une session est ouverte en reconstruction puis committée avec de nouveaux documents
- **THEN** les recherches ne servent que les documents de cette session
- **AND** avant le commit, elles servent le contenu du commit précédent

### Requirement: La provenance d'un index sémantique n'est jamais ignorée
L'ouverture d'une session sémantique SHALL exiger une provenance non nulle, et le commit de la session SHALL persister cette provenance avec les vecteurs ; aucune implémentation par défaut du port SHALL NOT accepter une provenance sans la transmettre.

#### Scenario: Provenance persistée au commit
- **GIVEN** une session sémantique ouverte avec une provenance
- **WHEN** la session est committée
- **THEN** l'index sémantique du projet est compatible avec cette provenance et incompatible avec une autre

#### Scenario: Provenance absente
- **WHEN** une session sémantique est ouverte sans provenance
- **THEN** l'ouverture échoue avec une `NullPointerException` qui nomme la provenance
- **AND** aucun writer n'est ouvert

### Requirement: Le service d'indexation sémantique n'expose que son port
Le service qui implémente le port d'indexation sémantique SHALL NOT exposer de méthode publique d'écriture absente de ce port.

#### Scenario: Méthodes publiques du service
- **WHEN** on compare les méthodes publiques du service d'indexation sémantique à celles du port `SemanticIndexing`
- **THEN** le service n'expose aucune méthode qui reconstruit ou applique un lot hors du port

## MODIFIED Requirements

### Requirement: Le service d'indexation exige un gestionnaire de verrou explicite
`ProjectIndexingService` SHALL exposer un seul constructeur public, qui SHALL exiger un `ProjectIndexLockManager` explicite.

#### Scenario: Composition de production
- **WHEN** `NexusApplicationFactory` construit `ProjectIndexingService`
- **THEN** elle fournit le gestionnaire de verrou inter-processus et aucun constructeur public ne retombe sur un verrouillage limité au processus

#### Scenario: Verrouillage limité au processus dans les tests
- **WHEN** un test unitaire sans `NEXUS_HOME` a besoin d'un gestionnaire de verrou limité au processus
- **THEN** il l'obtient de la fabrique de test du service d'indexation, et le code de production n'en expose aucune fabrique publique
