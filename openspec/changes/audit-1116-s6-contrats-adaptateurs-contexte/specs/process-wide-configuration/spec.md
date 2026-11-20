# Spec Delta

## Purpose

Garantit qu'une valeur de configuration installée pour tout le processus par la composition root (borne de taille des lectures, postures de sécurité, concurrence d'indexation) n'est jamais remplacée ni ignorée en silence par une seconde composition.
Documentation durable : docs/developer/architecture-implementation.md#openspec-process-wide-configuration

## ADDED Requirements

### Requirement: Une borne de taille différente de celle installée est refusée
Une fois une borne de taille des lectures installée pour le processus, l'installation d'une borne différente SHALL échouer par une `IllegalStateException` au message en français qui nomme `NEXUS_MAX_FILE_SIZE_BYTES`, et la borne installée SHALL rester en vigueur. L'installation de la même borne SHALL réussir sans effet.

#### Scenario: Seconde composition avec une autre borne
- **GIVEN** une `NexusApplication` composée avec `NEXUS_MAX_FILE_SIZE_BYTES=1024`
- **WHEN** une seconde `NexusApplication` est composée dans le même processus avec `NEXUS_MAX_FILE_SIZE_BYTES=2048`
- **THEN** la seconde composition échoue avec un message qui nomme `NEXUS_MAX_FILE_SIZE_BYTES`
- **AND** la borne en vigueur reste 1024 octets

#### Scenario: Seconde composition avec la même borne
- **GIVEN** une `NexusApplication` composée avec `NEXUS_MAX_FILE_SIZE_BYTES=1024`
- **WHEN** une seconde `NexusApplication` est composée avec la même valeur
- **THEN** la composition réussit et la borne en vigueur reste 1024 octets

### Requirement: Une posture de sécurité différente de celle installée est refusée
Une fois les postures `NEXUS_REQUIRE_STRICT_PATH_IO` et `NEXUS_REQUIRE_PRIVATE_STORAGE` installées pour le processus, l'installation de postures différentes SHALL échouer par une `IllegalStateException` au message en français qui nomme la variable en conflit, et les postures installées SHALL rester en vigueur. Les mêmes postures SHALL être acceptées sans effet.

#### Scenario: Adaptateur REST puis composition root
- **GIVEN** le garde d'exposition REST a installé les postures lues dans l'environnement du processus
- **WHEN** la composition root installe les mêmes postures, filtrées sur les deux variables
- **THEN** l'installation réussit sans effet

#### Scenario: Posture stricte remplacée
- **GIVEN** les postures installées portent `NEXUS_REQUIRE_STRICT_PATH_IO=true`
- **WHEN** une installation porte `NEXUS_REQUIRE_STRICT_PATH_IO=false`
- **THEN** elle échoue avec un message qui nomme `NEXUS_REQUIRE_STRICT_PATH_IO`
- **AND** la posture stricte reste en vigueur

### Requirement: Une limite de concurrence d'indexation différente est refusée
Une fois le budget de concurrence d'indexation du processus créé, une demande de permis avec une limite différente SHALL échouer par une `IllegalStateException` au message en français qui nomme `NEXUS_MAX_CONCURRENT_INDEXING`, sans consommer de permis. Une demande avec la même limite SHALL suivre le budget existant.

#### Scenario: Deux limites dans le même processus
- **GIVEN** un premier permis d'indexation acquis avec la limite 2
- **WHEN** un permis est demandé avec la limite 4
- **THEN** la demande échoue avec un message qui nomme `NEXUS_MAX_CONCURRENT_INDEXING`
- **AND** le nombre de permis disponibles du budget existant n'a pas changé

### Requirement: Les tests remettent les états de processus à zéro explicitement
Les états de processus SHALL offrir une remise à zéro réservée aux tests, qu'aucun code de production du cœur ni des adaptateurs n'appelle, pour qu'un test qui compose plusieurs configurations ne dépende pas de l'ordre d'exécution.

#### Scenario: Deux tests aux bornes différentes
- **GIVEN** un test qui compose une application avec `NEXUS_MAX_FILE_SIZE_BYTES=1024`, puis remet les états à zéro
- **WHEN** un autre test compose une application avec `NEXUS_MAX_FILE_SIZE_BYTES=2048`
- **THEN** les deux tests passent dans les deux ordres d'exécution

#### Scenario: Remise à zéro appelée par du code de production
- **WHEN** une classe de `core/src/main/java` ou de `adapters/*/src/main/java` appelle la remise à zéro d'un état de processus
- **THEN** le test d'architecture qui garde cette règle échoue
