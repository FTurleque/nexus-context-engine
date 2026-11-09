---
id: BE-0087
title: "Projeter le voisinage du graphe d'imports à la demande depuis SQLite, borné, sans matérialisation ni cache"
domain: backend
rules: false
---

# Projeter le voisinage du graphe d'imports à la demande depuis SQLite, borné, sans matérialisation ni cache

- **Statut** : Proposed
- **Date** : 2026-11-09
- **Origine** : audit du 2026-10-17 (`AUD-QUA-02`, `AUD-PRF-16`), changement OpenSpec `sprint-11-finitions-et-micro-optimisations`
- **Complète** : [BE-0071](BE-0071-rechercher-et-classer-les-candidats-par-un-score-compose-explicable.md)
- **Remplacé par** : —

## Context

[BE-0071](BE-0071-rechercher-et-classer-les-candidats-par-un-score-compose-explicable.md) retient un graphe minimal orienté entre fichiers, construit à partir des imports résolus vers les types du repository, et un `graphScore` propagé sur un ou deux sauts. Elle ne dit pas comment ce graphe est obtenu à l'exécution : matérialisé en entier, mis en cache, ou interrogé au besoin.

Le code tranche déjà : `GraphCandidateEnricher` (`core/src/main/java/com/nexus/ranking/graph/`) ne demande, à chaque saut, que le voisinage des chemins courants à `IndexRepository.findGraphNeighbors(projectId, relativePaths, maxEdges)`, borné par `GraphNeighborhoodProjection.MAX_NEIGHBOR_EDGES` (égal à `ResultLimitPolicy.MAX_INTERNAL_RETRIEVAL_LIMIT`, 2 000 arêtes). `SqliteGraphQueries` projette ce voisinage dans les deux sens par des requêtes indexées (`SqliteGraphQueryPlanTest`). Aucun type ne contient le graphe complet du projet, et aucun cache n'est indexé par génération.

L'audit du 2026-10-17 relève que la documentation développeur décrivait un cache par génération et qu'un nom de type (`ProjectGraphBuilder`) laissait croire à une construction du graphe entier (`AUD-QUA-02`), et que le voisinage sortant était paginé par `OFFSET`, chaque page triant puis sautant les lignes des pages précédentes (`AUD-PRF-16`).

La question est : **comment le ranking obtient-il le voisinage du graphe d'imports, avec une mémoire bornée et un coût qui dépend des chemins demandés plutôt que de la taille de l'index ?**

## Options envisagées

- **Graphe complet matérialisé en mémoire par génération d'index** : lectures rapides après le premier calcul ; mémoire proportionnelle au nombre d'imports du projet, invalidation à chaque réindexation, état partagé à protéger dans les runtimes longue durée, second référentiel du graphe à côté de SQLite.
- **Graphe complet matérialisé dans une table SQLite dérivée** : requêtes simples ; migration de schéma, maintenance à chaque écriture, duplication des relations déjà stockées.
- **Voisinage projeté à la demande depuis les relations SQLite, borné** : mémoire bornée par une page et par la borne d'arêtes ; temps proportionnel au nombre d'imports des chemins demandés ; une ou plusieurs pages en sortant et une requête en entrant par saut, servies par les index existants ; aucun état à invalider.

## Decision

**Option retenue : le voisinage du graphe d'imports est projeté à la demande depuis les relations SQLite, pour les seuls chemins demandés, sous une borne globale d'arêtes ; il n'est ni matérialisé en entier ni mis en cache.**

- `GraphNeighborhoodProjection` (`ranking.graph`) est le seul point d'accès du ranking au graphe ; il délègue à `IndexRepository.findGraphNeighbors` avec la borne `MAX_NEIGHBOR_EDGES`. Un ensemble de chemins vide ne déclenche aucune requête.
- La borne vaut pour la somme des arêtes sortantes et entrantes d'un appel ; les arêtes sortantes sont projetées d'abord, les entrantes reçoivent le reste du budget.
- Seules les arêtes résolues comptent dans la borne : un import externe, qui ne se résout vers aucun type du repository, est parcouru puis écarté sans consommer le budget.
- Le parcours des imports sortants est paginé par clé sur l'ordre `(source_ref, target_ref)` : chaque page reprend strictement après le dernier couple lu, sans `OFFSET`. Les couples sont uniques (`DISTINCT`), donc aucune ligne n'est sautée ni répétée. Les couples déjà lus sont écartés par le filtre de clé avant déduplication et tri ; l'index `(project_id, kind, source_ref)` ne contenant pas `target_ref`, chaque page relit encore les entrées d'index des chemins demandés.
- Les requêtes du voisinage cherchent par les index `(project_id, kind, source_ref)` et `(project_id, kind, target_ref)` de `symbol_relations` ; elles ne parcourent pas toute la table.
- À entrée identique (index, chemins, borne), le voisinage projeté est identique : les requêtes trient par chemin et référence.

Cette décision ne change ni la définition du graphe ni le calcul de `graphScore` fixés par BE-0071. Elle ne décide pas de cache futur : un cache du voisinage ne s'ajoute que si une mesure en montre le besoin, et donne lieu à un nouvel ADR.

## Do's and Don'ts

### Do

- Obtenir le voisinage du graphe par `GraphNeighborhoodProjection`, pour les seuls chemins courants, sous la borne `MAX_NEIGHBOR_EDGES`.
- Compter dans la borne les seules arêtes résolues, sortantes puis entrantes.
- Paginer le parcours des imports sortants par clé `(source_ref, target_ref)`, en reprenant après le dernier couple lu.
- Servir les requêtes du voisinage par les index de `symbol_relations`, vérifiés par un test de plan.

### Don't

- Ne pas matérialiser le graphe complet d'un projet, en mémoire ou dans une table dérivée.
- Ne pas mettre en cache le graphe ou le voisinage par génération d'index sans mesure ni nouvel ADR.
- Ne pas paginer le voisinage par `OFFSET`.
- Ne pas laisser un import externe consommer le budget d'arêtes.

## Consequences

### Positives

- Mémoire d'une expansion bornée par une page et par la borne d'arêtes ; temps indépendant du reste de l'index, proportionnel aux imports des chemins demandés.
- Une page ne trie ni ne saute les couples des pages précédentes.
- Aucun état dérivé à invalider après une réindexation : le voisinage lu reflète la dernière écriture SQLite.
- Le nom du type et la documentation décrivent ce que fait le code.

### Négatives

- Chaque expansion interroge SQLite : deux sauts coûtent au moins deux appels, sans réutilisation entre requêtes de recherche successives.
- Un projet dont les chemins demandés ont plus d'arêtes que la borne voit son voisinage tronqué, dans l'ordre de tri.
- Pour des chemins aux nombreux imports externes, le parcours sortant reste quadratique en nombre de pages : chaque page relit les entrées d'index des chemins demandés. Une recherche par clé dans l'index demanderait un index couvrant `(project_id, kind, source_ref, target_ref)` et des requêtes par chemin, donc une migration.

### Risques

| Risque | Impact | Mesure de maîtrise |
|---|---|---|
| Régression de plan (parcours complet de `symbol_relations`) | Moyen | `SqliteGraphQueryPlanTest` vérifie la recherche par index, première page et pages suivantes |
| Budget consommé par des imports externes | Moyen | `SqliteGraphNeighborhoodProjectionTest` couvre des imports non résolus triés avant les imports locaux, sur plusieurs pages |
| Réintroduction d'un cache non mesuré | Faible | Le présent ADR et le contrat documentaire de `docs/developer/search-ranking.md` |

## Compliance and Enforcement

- Tests `GraphNeighborhoodProjectionTest` (`core/src/test/java/com/nexus/ranking/graph/`) : délégation à la seule projection bornée, avec `MAX_NEIGHBOR_EDGES` ; `SqliteGraphNeighborhoodProjectionTest` (borne d'arêtes, imports non résolus sur plusieurs pages, concaténation des pages égale au parcours complet, frontières de projet et de préfixe, déterminisme) et `SqliteGraphQueryPlanTest` (plans indexés) dans `core/src/test/java/com/nexus/persistence/sqlite/`, lancés par `mvnw verify`.
- Contrat documentaire `scripts/release/test-operational-doc-contracts.sh` : refuse « ProjectGraph cache » dans `docs/developer/search-ranking.md`.
- Revue : absence de cache du graphe et d'`OFFSET` dans `SqliteGraphQueries` ; aucune règle Archgate ne les fige.

## Conditions de réexamen

La décision est réévaluée si une mesure montre que les requêtes du voisinage dominent le temps de recherche ou de contexte (première piste : l'index couvrant et les requêtes par chemin décrits dans les conséquences négatives), si le graphe s'enrichit de relations dont le volume rend la borne insuffisante (appels, références SCIP ou JDT), ou si un PageRank personnalisé, qui demande le graphe entier, est retenu selon BE-0071.

## Mise en œuvre

- `core/src/main/java/com/nexus/ranking/graph/GraphNeighborhoodProjection.java` (borne `MAX_NEIGHBOR_EDGES`), `GraphCandidateEnricher.java` (deux sauts).
- `core/src/main/java/com/nexus/index/IndexRepository.java` (`findGraphNeighbors`) ; `core/src/main/java/com/nexus/persistence/sqlite/SqliteGraphQueries.java` (`OUTGOING_GRAPH_SQL` paginée par clé, `INCOMING_GRAPH_SQL`, `TARGET_TYPE_OWNERS_SQL`).
- Détail technique : [`docs/developer/search-ranking.md`](../../developer/search-ranking.md).

## References

- [BE-0071](BE-0071-rechercher-et-classer-les-candidats-par-un-score-compose-explicable.md) — graphe minimal d'imports et score composé.
- [DATA-0073](DATA-0073-tenir-sqlite-pour-source-de-verite-et-lucene-pour-index-derive.md) — SQLite source de vérité.
- Audit du 2026-10-17 : `docs/project/audits/2026-10-17/findings.md` (`AUD-QUA-02`, `AUD-PRF-16`).
