# Spec Delta

## Purpose

Garantit que la CLI, REST et MCP interprètent de la même façon les paramètres de requête communs (sources demandées, limites par défaut), par une normalisation unique portée par le cœur.
Documentation durable : docs/developer/architecture-implementation.md#openspec-query-parameter-normalization

## ADDED Requirements

### Requirement: REST et MCP convertissent les sources demandées par une même règle du cœur
Les sources demandées d'un contexte simple ou fédéré SHALL être converties par une seule fonction du cœur, appelée par REST et par MCP. Pour une même liste d'entrées, les deux canaux SHALL retenir le même ensemble de types de source, ou refuser tous deux la requête comme requête invalide.

#### Scenario: Valeurs valides avec casse et blancs
- **GIVEN** un projet `READY`
- **WHEN** un contexte est demandé avec les sources ` instruction ` et `Skill`, une fois par REST et une fois par MCP
- **THEN** les deux canaux retiennent les types `INSTRUCTION` et `SKILL`
- **AND** les deux réponses contiennent les mêmes éléments de contexte

#### Scenario: Valeur inconnue
- **WHEN** un contexte est demandé par REST ou par MCP avec la source `binary`
- **THEN** la requête est refusée comme requête invalide : REST répond `400` avec le code `bad_request`, MCP rend un résultat en erreur
- **AND** aucun contexte n'est construit

### Requirement: Une entrée vide des sources demandées est traitée de la même façon par tous les canaux
Une entrée vide ou formée seulement de blancs dans les sources demandées SHALL être ignorée par REST comme par MCP. Une liste dont toutes les entrées sont ignorées SHALL valoir absence de filtre de source.

#### Scenario: Entrée vide parmi des valeurs valides
- **WHEN** un contexte est demandé avec les sources `""` et `FILE`, par REST puis par MCP
- **THEN** les deux canaux retiennent le seul type `FILE` et aucun ne refuse la requête

#### Scenario: Seulement des entrées vides
- **WHEN** un contexte est demandé avec les sources `""` et `"  "`
- **THEN** le contexte est construit sans filtre de source, comme sans le champ, sur REST et sur MCP

### Requirement: Le nombre de sources demandées est borné de la même façon par tous les canaux
REST et MCP SHALL refuser une liste de sources demandées qui compte plus d'entrées que de types de source existants, comme requête invalide, avant toute conversion.

#### Scenario: Liste trop longue
- **WHEN** un contexte est demandé par REST ou par MCP avec plus d'entrées que de types de source
- **THEN** la requête est refusée comme requête invalide : REST répond `400` avec le code `bad_request`, MCP rend un résultat en erreur
- **AND** aucun contexte n'est construit

### Requirement: Les limites par défaut viennent de la politique commune
Quand une requête ne fixe pas de limite de résultats, chaque canal SHALL appliquer la limite par défaut de la politique commune de résultats, y compris `find_usages` sur MCP.

#### Scenario: find_usages sans limite
- **GIVEN** un symbole qui a plus de dix usages dans un projet `READY`
- **WHEN** `find_usages` est appelé sans argument `limit`
- **THEN** la réponse contient au plus dix relations, la limite par défaut de la recherche

### Requirement: L'absence de sources demandées en CLI est une divergence déclarée
La CLI SHALL construire ses contextes sans filtre de source, et cette absence d'option SHALL être déclarée par le contrat de sortie publique du cœur et par la documentation de la CLI.

#### Scenario: Divergence lue dans le contrat
- **WHEN** un test lit les divergences déclarées par le contrat de sortie publique
- **THEN** il y trouve l'absence d'option de sources demandées pour la CLI
- **AND** `docs/developer/cli.md` la mentionne
