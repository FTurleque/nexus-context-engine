# Spec Delta

## Purpose

Définit les bornes de coût et de temps d'une requête fédérée (recherche ou contexte) sur un scope de projets, afin que son coût ne répète pas par projet ce qui ne dépend que de la requête, et que sa latence reste bornée et explicable.

## ADDED Requirements

### Requirement: Le vecteur de requête est calculé une fois par requête fédérée
Une requête fédérée avec recherche sémantique active SHALL demander au fournisseur d'embeddings au plus un vecteur de requête, quel que soit le nombre de projets du scope, et SHALL transmettre ce vecteur à la recherche sémantique de chaque projet.

#### Scenario: Scope de plusieurs projets
- **WHEN** une recherche fédérée sémantique porte sur 3 projets indexés et compatibles
- **THEN** le fournisseur d'embeddings reçoit exactement un appel `embedQuery`

#### Scenario: Requête redactée avant embedding
- **WHEN** la requête fédérée contient une valeur reconnue comme secret
- **THEN** le texte transmis au fournisseur d'embeddings est la requête redactée

#### Scenario: Fournisseur indisponible
- **WHEN** le fournisseur d'embeddings est indisponible pendant une requête fédérée sur 3 projets
- **THEN** un seul appel est tenté et chaque projet porte le même diagnostic de dégradation sémantique

### Requirement: La fédération respecte une échéance globale
Une requête fédérée SHALL vérifier une échéance globale avant de traiter chaque projet du scope et SHALL NOT commencer un projet après l'échéance. Elle SHALL NOT traiter les projets en parallèle.

#### Scenario: Échéance atteinte en cours de scope
- **WHEN** l'échéance est atteinte après le traitement du deuxième projet d'un scope de 5 projets
- **THEN** la réponse contient les résultats des deux premiers projets et ne contient aucun résultat des trois autres

#### Scenario: Échéance non atteinte
- **WHEN** tous les projets sont traités avant l'échéance
- **THEN** la réponse ne contient aucun diagnostic de projet non servi et ses résultats sont identiques à ceux d'une exécution sans échéance

### Requirement: Les projets non servis sont nommés
Une réponse fédérée interrompue par l'échéance SHALL contenir un diagnostic public qui nomme chaque projet non servi, dans l'ordre déterministe du scope, à l'identique par CLI, REST et MCP.

#### Scenario: Diagnostic ordonné
- **WHEN** les projets `c`, `d` et `e` d'un scope ordonné `a, b, c, d, e` ne sont pas servis
- **THEN** le diagnostic liste `c`, `d`, `e` dans cet ordre

#### Scenario: Parité des interfaces
- **WHEN** la même requête fédérée interrompue est servie par CLI, REST et MCP
- **THEN** les trois réponses contiennent le même diagnostic de projets non servis
