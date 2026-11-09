# Recherche, graphe et ranking explicable

Ce chapitre décrit le pipeline Phase 6. Les baselines historiques restent dans les documents d'itération et de benchmark.

## Pipeline mono-projet

```text
query
 ├─ LuceneFileSearchStrategy
 ├─ SymbolSearchStrategy          pool SQLite borné
 └─ SemanticSearchStrategy        opt-in
          ↓
CandidateMerger
          ↓
GraphCandidateEnricher            voisinage graphe borné
GitRecencyCandidateEnricher
          ↓
ContextRanker
 ├─ DeterministicContextRanker    défaut
 └─ SemanticHybridContextRanker   opt-in
          ↓
RankedCandidate[]
```

`SearchService` sur-récupère avant le ranking final :

```text
retrievalLimit = min(500, max(20, limit * 3))
```

## Gate de cohérence

Les surfaces applicatives passent par `NexusApplication` et exigent désormais `IndexStatus.READY` avant recherche, symboles/usages, contexte ou fédération. Un index dérivé partiellement mis à jour n'est donc pas servi pendant `INDEXING`/`FAILED`.

## Recherche Lucene

`LuceneFileSearchStrategy` cherche les champs `symbol_name`, `qualified_name`, `path_text`, `code_terms` et `content` avec les boosts historiques. Les catégories `INSTRUCTION`, `AGENT_PROFILE` et `SKILL` restent hors recherche générique.

Le lifecycle Lucene par opération est conservé tant qu'un benchmark de runtime persistant ne démontre pas qu'un `SearcherManager`/writer partagé améliore matériellement p95/heap.

## Recherche symbole bornée

`SymbolSearchStrategy` ne charge plus tous les symboles avant filtrage. Elle demande à `IndexRepository.searchSymbols(projectId, query, limit)` un pool borné et préfiltré côté SQLite, puis conserve le fuzzy Java :

- exact : 1.0 ;
- contains : 0.9 ;
- Levenshtein normalisé ;
- seuil fuzzy : 0.62 ;
- pool candidat : min 100, max 2 000.

Depuis V008, les recherches substring de **trois points de code Unicode ou plus** passent par `symbol_search_fts`, un index FTS5 utilisant le tokenizer `trigram`. Le pool SQL est l'union de :

- candidats `name` / `qualified_name` issus du trigram index ;
- candidats fuzzy préfiltrés, par projet, par `idx_symbols_file_fuzzy_prefilter` (V015) sur première lettre normalisée et longueur.

Le `LIKE` de classement (`prefix`) ne s'applique plus qu'au petit ensemble candidat déjà sélectionné. Les requêtes de un ou deux points de code conservent le chemin `LIKE` historique pour compatibilité : elles restent bornées en résultats, mais constituent volontairement le seul cas de substring pouvant encore nécessiter un scan. Le tokenizer trigram ne peut pas indexer une sous-chaîne plus courte sans ajouter un second index n-gram beaucoup plus coûteux.

`symbol_search_fts` est un index **dérivé**. V008 backfill les symboles existants et des triggers `INSERT` / `UPDATE` / `DELETE` le gardent synchronisé avec la table canonique `symbols`. Sa reconstruction reste donc déterministe à partir de SQLite canonique.

Une recherche de symboles réutilise la même connexion SQLite pour le pool trigram
et le pool fuzzy. La connexion est fermée à la fin de l'opération ; cela évite une
seconde ouverture et un second chargement du schéma pour chaque projet d'une
recherche ou d'un contexte fédéré. Les filtres, limites et règles de classement
restent identiques.

Le runtime SQLite embarqué est qualifié par `SqliteIndexedSubstringSearchTest` : SQLite >= 3.34.0, `ENABLE_FTS5`, création effective d'un tokenizer `trigram`, plan `VIRTUAL TABLE INDEX` et synchronisation des triggers. La dépendance Xerial courante est définie au POM parent ; toute évolution de cette baseline doit conserver ce test vert.

`NexusApplication.findSymbols` utilise directement cette API bornée.

## Usages bornés

`findUsages` délègue à `IndexRepository.searchRelations(projectId, symbol, limit)`. Depuis V008, les recherches source/cible de trois points de code ou plus passent par `relation_search_fts`, également en FTS5 trigram, au lieu de `LOWER(...) LIKE '%...%'` sur toute la table. L'index dérivé est backfillé à la migration et maintenu par triggers. Les requêtes de un ou deux points de code gardent le fallback de compatibilité décrit ci-dessus.

Les index B-tree de V002/V003 restent utilisés pour les parcours exacts du graphe et les projections `project/kind/source/target`; FTS5 ne les remplace pas.

## Graphe d'imports projeté à la demande

Le graphe relie des fichiers par les relations `IMPORTS` résolues vers un type défini dans le projet ; un import externe ne crée aucune arête. Il n'est ni matérialisé en entier ni mis en cache, par génération d'index ou autrement ([BE-0087](../architecture/adr/BE-0087-projeter-le-voisinage-du-graphe-d-imports-a-la-demande.md)) : à chaque expansion, `GraphNeighborhoodProjection` demande à `IndexRepository.findGraphNeighbors` le seul voisinage des chemins courants, borné à `GraphNeighborhoodProjection.MAX_NEIGHBOR_EDGES` arêtes (`ResultLimitPolicy.MAX_INTERNAL_RETRIEVAL_LIMIT`) quelle que soit la taille de l'index. Un ensemble de chemins vide ne déclenche aucune requête.

`SqliteGraphQueries` projette ce voisinage dans les deux sens, sous une seule borne :

- sortant : imports du fichier, résolus vers le fichier qui définit le type importé. Les imports sont lus par pages triées sur `(source_ref, target_ref)` ; chaque page reprend strictement après le dernier couple lu (pagination par clé, sans `OFFSET`). L'index `(project_id, kind, source_ref)` ne contenant pas `target_ref`, chaque page relit les entrées d'index des chemins demandés et écarte les couples déjà lus avant tri. Seules les arêtes résolues comptent dans la borne : un import externe est parcouru puis écarté ;
- entrant : fichiers qui importent un type défini dans le fichier (nom qualifié exact ou membre imbriqué du type), dans la part de la borne laissée par le sens sortant.

`SqliteGraphQueryPlanTest` vérifie que ces requêtes cherchent par les index `(project_id, kind, source_ref)` et `(project_id, kind, target_ref)` de `symbol_relations`.

`GraphCandidateEnricher` part des candidats directs (meilleur signal parmi lexical, symbole exact, symbole approché et chemin), charge uniquement les `IndexedFile` des voisins et ajoute `graphScore` sur deux sauts :

```text
premier saut = seedScore × 0.65
second saut  = seedScore × 0.35
```

Les fichiers `INSTRUCTION`, `AGENT_PROFILE` et `SKILL` ne reçoivent pas de signal de graphe.

## Récence Git

`GitRecencyCandidateEnricher` reste local, read-only et faiblement pondéré. Aucun cache persistant Git n'est introduit sans mesure justifiant sa complexité.

## Ranking déterministe

Poids historiques principaux :

| Signal | Poids |
|---|---:|
| lexical | 0.40 |
| symbol exact | 0.30 |
| symbol fuzzy | 0.10 |
| path | 0.10 |
| graph | 0.10 |

Chaque signal est borné dans `[0,1]` avant pondération ; un candidat de score nul est écarté. Les signaux optionnels s'ajoutent par la même formule : `semanticScore` (poids par défaut 0.15, entre 0 et 1) et `gitRecencyScore` (poids par défaut 0.05, au plus 0.20).

À score égal, l'ordre est stable : type (`SYMBOL`, `TEST`, `FILE`, `DOCUMENTATION`, `INSTRUCTION`, `SKILL`, `GIT`), puis chemin, puis identifiant. Avec `explain=true`, chaque composante non nulle produit une raison de la forme `libellé: valeur normalisée -> +contribution`.

Décision : [BE-0071](../architecture/adr/BE-0071-rechercher-et-classer-les-candidats-par-un-score-compose-explicable.md).

## Sémantique opt-in

Lorsque `NEXUS_SEMANTIC_PROVIDER=ollama` ou une configuration explicite active la capacité :

```text
EmbeddingProvider
  ↓
LuceneSemanticSearchIndex
  ↓
SemanticSearchStrategy
  ↓
SemanticHybridContextRanker
```

RRF historique : `k=60`, poids sémantique par défaut `8.0`, limité à `10`. Voir [`semantic-search.md`](semantic-search.md).

## Recherche fédérée

`FederatedSearchService` :

1. déduplique la portée par UUID ;
2. demande à chaque projet un pool local **supérieur au top-K final** ;
3. fusionne globalement par score ;
4. stabilise les égalités ;
5. diversifie par `(projectId,path)` ;
6. tronque au top-K global.

La sur-récupération fédérée est bornée entre 20 et 500 candidats par projet, avec facteur 4 sur le `limit`. Cela corrige le cas où FILE et SYMBOL d'un même chemin consommaient le cut-off local et sous-remplissaient ensuite le top-K diversifié.

La capacité est exposée via :

```text
CLI  search-federated
REST POST /api/v1/federated/search
MCP  search_across_projects
```

## Explicabilité et qualité

`explain=true` conserve composantes et raisons calculées, jamais générées par LLM.

Métriques de comparaison :

```text
precision@K
recall@K
hit@K
MRR@K
p50 / p95
```

La Phase 6 ne remplace pas Lucene par Zoekt/OpenGrok/OpenSearch : les optimisations locales sont appliquées d'abord et doivent être re-mesurées avant toute décision d'infrastructure.

Voir [`large-scale-search.md`](large-scale-search.md), [`current-limitations.md`](current-limitations.md) et la [`roadmap`](../project/governance/ROADMAP.md).
