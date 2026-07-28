# Recherche sémantique optionnelle

## Statut

Capacité **livrée et validée** depuis l'Itération 17, strictement **opt-in**.

Le chemin par défaut :

```java
NexusApplication.create(paths);
```

ne charge aucun provider d'embeddings et ne crée aucun index vectoriel.

L'activation programmable est explicite :

```java
NexusApplication.create(
        paths,
        SemanticSearchConfiguration.enabled(embeddingProvider));
```

## Architecture

```text
SearchService
├── LuceneFileSearchStrategy
├── SymbolSearchStrategy
└── SemanticSearchStrategy                  opt-in
        │
        ├── EmbeddingProvider
        │     └── OllamaEmbeddingProvider   baseline locale
        │
        └── SemanticSearchIndex
              └── LuceneSemanticSearchIndex

Ranking opt-in
├── ranking historique
├── ranking kNN/cosine
└── SemanticHybridContextRanker
      └── Reciprocal Rank Fusion
          k = 60
          poids historique = 1.0
          poids sémantique = 8.0
```

SQLite reste canonique. L'index vectoriel Lucene reste dérivé et reconstructible.

## Contrats

### `EmbeddingProvider`

Fournit l'identité du modèle, sa dimension et la production d'un embedding.

### `SemanticSearchIndex`

Abstrait rebuild, delta, suppression et recherche kNN.

### `SemanticIndexingService`

Vectorise les `SearchDocument` uniquement lorsque la capacité sémantique a été composée.

L'implémentation actuelle appelle le provider document par document ; batching/cache éventuels restent des optimisations à mesurer.

### `SemanticSearchStrategy`

Produit des `SearchCandidate` avec un signal sémantique compatible avec le pipeline historique.

## Provider de référence

La baseline mesurée utilise :

```text
endpoint    http://localhost:11434
model       qwen3-embedding:0.6b
dimensions  1024
```

Aucune requête Ollama n'est déclenchée par le chemin standard ou par la simple création du provider. Les embeddings ne sont produits que pendant une indexation/recherche sémantique explicitement configurée.

Ollama et ce modèle ne sont pas des dépendances obligatoires de NEXUS.

## Fusion RRF

La première fusion additive a été rejetée car elle écrasait le signal kNN pertinent sur le corpus réel.

La solution retenue sépare les deux rankings :

1. ranking historique ;
2. ranking vectoriel ;
3. Reciprocal Rank Fusion avec `k = 60` ;
4. poids historique `1.0` ;
5. poids sémantique `8.0`.

Le sweep mesuré a montré :

```text
smallestWeightMatchingRawRecallAndHit = 4.0
bestObservedWeightByRecallHitMrr      = 8.0
```

Le poids `8.0` reste donc la valeur par défaut **du mode opt-in uniquement**.

## Baseline A/B réelle

Corpus hermétique :

```text
fichiers    236
symboles    946
relations   1 539
requêtes    6
```

| Métrique | Baseline | RRF x8 |
|---|---:|---:|
| `precision@3` | 0,0000 | 0,1667 |
| `recall@3` | 0,0000 | 0,4167 |
| `hit@3` | 0,0000 | 0,5000 |
| `MRR@3` | 0,0000 | 0,3056 |

Coût :

| Métrique | Baseline | Sémantique |
|---|---:|---:|
| indexation complète | 1 943 ms | 64 332 ms |
| recherche moyenne | 208,8 ms | 298,7 ms |
| index sémantique | 0 | 1 001 537 octets |

Le gain de qualité est réel sur les requêtes à forte divergence lexicale, mais le coût d'indexation est environ `33,11×` et la recherche environ `1,43×` la baseline.

## Surface opérationnelle actuelle

La capacité est aujourd'hui stable dans le **cœur Java** via `SemanticSearchConfiguration` et couverte par les tests/benchmarks.

En revanche, la CLI, l'adaptateur REST et l'adaptateur MCP n'exposent pas encore une politique de configuration sémantique homogène.

Cette distinction est importante :

```text
capacité moteur          ✅ livrée
activation par défaut    ❌ volontairement non
configuration Java       ✅ livrée
configuration CLI/REST/MCP homogène  ⏳ Phase 6 / I22
```

I22 doit rendre l'activation explicite sur les surfaces retenues **sans** transformer Ollama ou un provider d'embeddings en prérequis.

## Optimisations différées

Avant toute extension de l'usage, mesurer :

- cache de vecteurs par hash de document/configuration ;
- batching lorsque le provider le supporte ;
- reprise incrémentale ;
- coût mémoire/disque ;
- latence sous runtime persistant.

Aucune vector DB externe n'est justifiée par les mesures actuelles.

## Quand activer le mode

Le mode est pertinent pour :

- recherche conceptuelle ;
- paraphrases ;
- forte divergence vocabulaire requête/code/documentation.

Il ne doit pas être activé automatiquement pour tous les projets.

## Validation

Gate spécialisé :

```powershell
.\scripts\validate-iteration-17.ps1
```

Diagnostic/sweep :

```powershell
.\scripts\measure-iteration-17-real-semantic-diagnostic.ps1
```

Benchmark A/B réel :

```powershell
.\scripts\measure-iteration-17-real-semantic.ps1
```

Les benchmarks sont opt-in et ne déclenchent pas Ollama pendant un `mvn test` standard.

Résultats historiques détaillés : [`iteration-17-semantic-results.md`](iteration-17-semantic-results.md).

Dette/opérationnalisation : [`current-limitations.md`](current-limitations.md), F14/F16 ; [`../roadmap.md`](../roadmap.md), I22.
