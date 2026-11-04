# public-output-projection Specification

## Purpose
Définit la projection unique par laquelle passent les résultats publics de NEXUS sur la CLI, REST et MCP : champs exposés, chemins, ordre et redaction des métadonnées.
Documentation durable : docs/developer/rest-api.md#openspec-public-output-projection

## Requirements

### Requirement: Les trois canaux exposent les mêmes clés
Pour un même résultat (recherche, symbole, contexte, contexte fédéré), les sorties JSON de la CLI, de REST et de MCP SHALL contenir le même ensemble de clés, sauf divergence déclarée par la projection publique du cœur.

#### Scenario: Recherche sur un projet de fixture
- **GIVEN** un projet de fixture indexé
- **WHEN** la même requête de recherche est exécutée par la CLI en JSON, par l'endpoint REST et par l'outil MCP
- **THEN** chaque résultat classé porte les mêmes clés sur les trois canaux, dont `path`, `excerpt`, `id` et `scoreComponents`

#### Scenario: Divergence non déclarée
- **WHEN** un adaptateur ajoute, retire ou renomme une clé sans que la projection publique la déclare
- **THEN** le test de parité échoue

### Requirement: Les chemins publics sont relatifs au projet
La projection SHALL exposer tout chemin de fichier relativement à la racine du projet, de la même manière sur les trois canaux, et SHALL NOT exposer de chemin absolu.

#### Scenario: Élément de contexte
- **GIVEN** un contexte qui contient un fragment de `src/main/java/App.java`
- **WHEN** ce contexte est produit par la CLI, par REST et par MCP
- **THEN** la clé `path` vaut `src/main/java/App.java` sur la CLI, REST et MCP

#### Scenario: Chemin hors de la racine du projet
- **WHEN** la projection reçoit un chemin situé hors de la racine du projet
- **THEN** elle refuse de l'exposer
- **AND** aucun chemin absolu n'est produit

### Requirement: L'ordre des composantes de score est déterministe
La projection SHALL ordonner les entrées de `scoreComponents` par clé, pour les résultats classés et les éléments de contexte, simples ou fédérés.

#### Scenario: Deux ordres d'insertion
- **GIVEN** deux résultats identiques dont les composantes sont insérées dans des ordres différents
- **WHEN** ils sont sérialisés en JSON sur MCP et sur REST
- **THEN** les deux sérialisations sont identiques et suivent l'ordre lexicographique des clés

#### Scenario: Deux processus
- **GIVEN** deux démarrages distincts de la JVM
- **WHEN** la même requête est servie par chacun
- **THEN** les deux réponses JSON sont identiques octet pour octet, à l'exception des métriques de durée

### Requirement: Les métadonnées de symbole sont redigées à la sortie
La projection SHALL appliquer la redaction des secrets à `signature`, `qualifiedName`, `source` et `target` avant toute sortie CLI (JSON et rendu humain), REST ou MCP.

#### Scenario: Signature externe porteuse d'un secret
- **GIVEN** un symbole importé de SCIP, MINOS ou JDT dont la signature est `String TOKEN = "ghp_` suivie de 36 caractères alphanumériques
- **WHEN** `find_symbol`, `search_code` ou la recherche REST le renvoient
- **THEN** aucune sortie ne contient le jeton en clair

#### Scenario: Signature trop longue pour la redaction
- **GIVEN** un symbole externe dont la signature dépasse la longueur que la redaction accepte de traiter
- **WHEN** il est projeté pour une sortie publique
- **THEN** la signature sort sous la forme `[REDACTED]`
- **AND** la réponse n'échoue pas

### Requirement: Le REST réutilise les records d'opération du cœur
Le service applicatif REST SHALL renvoyer les records d'opération de `NexusApplication` et SHALL NOT en définir de copie.

#### Scenario: Ajout d'un champ côté cœur
- **WHEN** un champ est ajouté à `NexusApplication.SearchOperation`
- **THEN** il est disponible dans le service REST sans modification de celui-ci
