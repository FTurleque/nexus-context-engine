# Spec Delta

## Purpose

Définit comment REST et MCP signalent les deux conflits d'état attendus d'un projet, un projet non prêt et une mutation d'index déjà en cours, à partir de leur type et non du texte d'un message.
Documentation durable : docs/developer/rest-api.md#openspec-project-state-conflicts

## ADDED Requirements

### Requirement: REST signale un projet non prêt par son type d'erreur
Quand une opération exige un projet `READY` et que le projet ne l'est pas, REST SHALL répondre `409` avec le code `project_not_ready`, sans `Retry-After`, et SHALL NOT exposer l'identifiant ni l'état interne du projet dans le message.

#### Scenario: Statistiques d'un projet non indexé
- **GIVEN** un projet enregistré dans l'état `NOT_INDEXED`
- **WHEN** un client appelle `GET /api/v1/projects/{projectId}/index`
- **THEN** la réponse est `409` avec le code `project_not_ready` et aucun en-tête `Retry-After`
- **AND** l'état du projet n'est pas modifié

### Requirement: REST signale une mutation d'index en cours comme conflit relançable
Quand une mutation d'index est déjà en cours pour le projet visé, REST SHALL répondre `409` avec le code `project_index_busy` et l'en-tête `Retry-After: 1`.

#### Scenario: Deux indexations concurrentes
- **GIVEN** une indexation en cours qui tient le verrou de mutation du projet
- **WHEN** un client demande une seconde indexation du même projet
- **THEN** la réponse est `409` avec le code `project_index_busy` et `Retry-After: 1`
- **AND** l'indexation en cours n'est pas interrompue

### Requirement: La classification REST ne dépend pas du texte des messages du cœur
Le mapping REST des conflits d'état SHALL dépendre du type de l'erreur levée par le cœur. Une `IllegalStateException` d'un autre type SHALL rester une erreur interne `500` au message public stable, quel que soit son texte.

#### Scenario: Message du cœur reformulé
- **GIVEN** le message d'un projet non prêt est reformulé dans le cœur
- **WHEN** un client interroge un projet non prêt
- **THEN** la réponse reste `409` avec le code `project_not_ready`

#### Scenario: Erreur d'état quelconque qui imite un message de conflit
- **WHEN** une `IllegalStateException` sans type de conflit porte le texte `Une mutation d'index est déjà en cours pour le projet ` suivi d'un UUID
- **THEN** la réponse est `500` avec le code `internal_state_error`

### Requirement: MCP distingue un projet non prêt d'une mutation d'index en cours
Un outil MCP SHALL rendre un résultat en erreur de code `project_not_ready` pour un projet non prêt, et de code `project_index_busy` pour une mutation d'index déjà en cours, avec un message public en français sans identifiant ni chemin. Les autres erreurs d'état SHALL garder le code `nexus_tool_error`.

#### Scenario: Recherche sur un projet non indexé
- **GIVEN** un projet enregistré dans l'état `NOT_INDEXED`
- **WHEN** l'outil `search_code` est appelé sur ce projet
- **THEN** le résultat est en erreur avec le code `project_not_ready`
- **AND** le message ne contient ni l'identifiant du projet ni l'état `NOT_INDEXED`

#### Scenario: Autre erreur d'état
- **WHEN** un outil MCP échoue sur une `IllegalStateException` qui n'est pas un conflit d'état de projet
- **THEN** le résultat est en erreur avec le code `nexus_tool_error` et le message `Opération NEXUS indisponible dans l'état courant`

### Requirement: Les conflits d'état restent des erreurs d'état pour les appelants existants
Les erreurs de projet non prêt et de mutation d'index en cours SHALL rester des `IllegalStateException` pour tout appelant du cœur qui attrape ce type.

#### Scenario: Appelant qui attrape IllegalStateException
- **WHEN** un appelant du cœur attrape `IllegalStateException` autour d'une lecture d'un projet non prêt
- **THEN** il reçoit l'erreur de projet non prêt
