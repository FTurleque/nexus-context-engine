# Spec Delta

## ADDED Requirements

### Requirement: Les termes courts d'une requête ne déclenchent pas de recherche par sous-chaîne
La recherche de symboles SHALL NOT interroger par sous-chaîne un terme de moins de trois points de code issu du découpage d'une requête, et SHALL toujours interroger la requête entière. Ces termes SHALL rester pris en compte dans le score des symboles candidats.

#### Scenario: Requête en langage naturel
- **GIVEN** un projet indexé dont un symbole se nomme `mapDatabaseId`
- **WHEN** la requête `how is the db id mapped` est recherchée
- **THEN** les requêtes envoyées à la base de symboles comprennent la requête entière et les termes `how`, `the` et `mapped`
- **AND** aucune requête n'est envoyée pour les termes `is`, `db` et `id`

#### Scenario: Requête dont tous les termes sont courts
- **WHEN** la requête `db id` est recherchée
- **THEN** seule la requête entière `db id` est envoyée à la base de symboles
- **AND** le nombre de requêtes SQL exécutées est le même que pour une requête d'un terme

### Requirement: Une requête entière courte ne cherche que l'égalité et le préfixe du nom
Pour une requête entière de moins de trois points de code, la recherche de symboles SHALL ne retenir que les symboles du projet ciblé dont le nom en minuscules est égal à la requête ou commence par elle, l'égalité avant le préfixe, et SHALL contraindre le nom en minuscules par un index, sans comparaison par sous-chaîne.

#### Scenario: Égalité et préfixe
- **GIVEN** un projet dont les symboles se nomment `Id`, `idle`, `getId` et `Widget`
- **WHEN** la base de symboles est interrogée avec la requête `id`
- **THEN** les candidats rendus sont `Id` puis `idle`
- **AND** ils ne comprennent ni `getId` ni `Widget`

#### Scenario: Plan de requête
- **WHEN** le plan `EXPLAIN QUERY PLAN` de la requête de symboles courte est calculé sur le schéma courant
- **THEN** il contraint le nom en minuscules par un index, par égalité ou par intervalle
- **AND** il ne contient aucun parcours de la table des symboles contraint seulement par le fichier ou le projet

#### Scenario: Projet voisin
- **GIVEN** une base de deux projets dont seul le second contient un symbole `idle`
- **WHEN** la base de symboles est interrogée avec la requête `id` pour le premier projet
- **THEN** les candidats rendus ne comprennent pas `idle`

### Requirement: La recherche courte est mesurée sous budget
Le benchmark d'échelle SHALL mesurer la recherche de symboles d'une requête de moins de trois points de code à chaque palier de symboles, et `scripts/verify-scale-budgets.py` SHALL refuser un rapport dont le p95 de cette mesure dépasse le budget du palier.

#### Scenario: Rapport du benchmark
- **WHEN** le benchmark d'échelle s'exécute avec le profil `ci` ou `full`
- **THEN** le rapport JSON contient, pour chaque palier de symboles, le p95 de la recherche de la requête courte

#### Scenario: Budget dépassé
- **GIVEN** un rapport dont le p95 de la requête courte dépasse le budget d'un palier
- **WHEN** `python scripts/verify-scale-budgets.py` vérifie le rapport
- **THEN** la vérification échoue par une `AssertionError` qui nomme le palier, la mesure et sa valeur
- **AND** aucun rapport n'est modifié

#### Scenario: Mesure absente
- **GIVEN** un rapport dont un palier n'a pas de mesure de la requête courte
- **WHEN** `python scripts/verify-scale-budgets.py` vérifie le rapport
- **THEN** la vérification échoue
