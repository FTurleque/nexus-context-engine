# Spec Delta

## Purpose

Définit les coûts admis sur les chemins de requête et d'import : troncature de fragment, pagination du voisinage graphe, compilation des motifs. Les sorties restent identiques.

## ADDED Requirements

### Requirement: La troncature de fragment ne reconstruit pas le préfixe à chaque ligne
`BudgetedContextSelector` SHALL déterminer le nombre de lignes conservées d'un fragment tronqué en un nombre d'estimations logarithmique en nombre de lignes, et SHALL produire le même contenu tronqué que la sélection ligne à ligne.

#### Scenario: Équivalence de sortie
- **WHEN** un fragment multiligne, y compris avec des caractères hors BMP, est tronqué sous un budget donné
- **THEN** le contenu et le marqueur de troncature sont identiques à ceux de la sélection ligne à ligne de référence

#### Scenario: Gros fragment
- **WHEN** un fragment de n lignes dépasse le budget par item
- **THEN** l'estimateur est appelé au plus de l'ordre de log2(n) + 2 fois

### Requirement: Le voisinage sortant est paginé par clé
La requête de voisinage sortant de `SqliteGraphQueries` SHALL reprendre chaque page après le dernier couple `(source_ref, target_ref)` lu, sans `OFFSET`.

#### Scenario: Nombreux imports non résolus
- **WHEN** les graines ont plus d'une page d'imports qui ne se résolvent pas en arêtes
- **THEN** le voisinage retourné est identique à celui de la pagination par décalage, sans couple répété ni omis

#### Scenario: Plan de requête
- **WHEN** le plan de la requête paginée est lu par `EXPLAIN QUERY PLAN`
- **THEN** il utilise l'index des relations et aucun parcours complet de `symbol_relations`

### Requirement: Les motifs ne sont pas compilés à chaque appel
`RepositoryPath.fromProvider` SHALL refuser une lettre de lecteur sans expression régulière, et `RepositoryGlobMatcher` SHALL réutiliser un motif déjà compilé, dans un cache de taille bornée.

#### Scenario: Chemin avec lettre de lecteur
- **WHEN** un fournisseur transmet `C:/src/App.java`
- **THEN** le chemin est refusé comme aujourd'hui

#### Scenario: Même glob évalué deux fois
- **WHEN** le même glob d'instruction est évalué pour deux requêtes de contexte
- **THEN** il n'est compilé qu'une fois, et le cache ne dépasse jamais sa taille maximale
