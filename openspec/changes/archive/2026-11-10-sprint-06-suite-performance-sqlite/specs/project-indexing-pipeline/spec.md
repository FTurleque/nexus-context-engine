# Spec Delta

## ADDED Requirements

### Requirement: Le port d'indexation n'écrit les faits canoniques que par session
Le port `IndexRepository` SHALL n'exposer, pour écrire les analyses de fichiers d'un projet, que l'ouverture d'une session d'écriture qui met en attente puis publie ; il SHALL NOT exposer d'écriture directe des analyses.

#### Scenario: Surface du port
- **WHEN** on lit le port `IndexRepository`
- **THEN** il ne déclare aucune méthode qui applique des analyses de fichiers hors d'une session d'écriture

#### Scenario: Écriture dans un test
- **WHEN** un test doit écrire des analyses de fichiers
- **THEN** il passe par l'ouverture d'une session, la mise en attente et la publication

## MODIFIED Requirements

### Requirement: La réindexation sur base peuplée est mesurée
Le benchmark d'échelle SHALL comporter un scénario qui réindexe des fichiers existants par le chemin d'écriture de l'indexation (ouverture de session, mise en attente par lots, publication) sur une base déjà peuplée.

#### Scenario: Profil CI du benchmark
- **WHEN** le benchmark d'échelle s'exécute avec le profil `ci`
- **THEN** il rapporte la durée de réindexation incrémentale et de rebuild sur base peuplée

#### Scenario: Durée de la transaction de publication
- **WHEN** le benchmark de pic mémoire de l'indexation rapporte la durée de transaction d'écriture
- **THEN** cette durée est celle de la publication de la session, mesurée sur des analyses mises en attente
