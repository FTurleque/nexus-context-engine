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
GraphCandidateEnricher            graphe cache/génération
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

`NexusApplication.findSymbols` utilise directement cette API bornée.

## Usages bornés

`findUsages` délègue à `IndexRepository.searchRelations(projectId, symbol, limit)`. SQLite filtre source ou cible avant matérialisation et V002 indexe les deux endpoints relationnels.

## Graphe dérivé par génération

`ProjectGraphBuilder` construit le graphe à partir des symboles de type et des relations `IMPORTS`. V002 ajoute une génération monotone par projet.

```text
SQLite canonical generation N
        ↓
ProjectGraph cache generation N
```

Tant que la génération ne change pas, le graphe est réutilisé. Lorsqu'elle change, il est reconstruit. `GraphCandidateEnricher` charge ensuite uniquement les `IndexedFile` correspondant aux chemins voisins calculés.

La propagation reste :

```text
premier saut = seedScore × 0.65
second saut  = seedScore × 0.35
```

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

À score égal, des tie-breakers stables conservent le déterminisme.

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

Voir [`large-scale-search.md`](large-scale-search.md), [`current-limitations.md`](current-limitations.md) et la [`roadmap`](../roadmap.md).
