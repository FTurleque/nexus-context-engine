# Recherche sémantique optionnelle — Itération 17

## Statut

La recherche sémantique est **conservée comme capacité locale opt-in validée**.

Elle reste désactivée par défaut :

```java
NexusApplication.create(paths);
```

ne charge aucun provider d'embeddings et ne crée aucun index vectoriel.

L'activation est explicite :

```java
NexusApplication.create(
        paths,
        SemanticSearchConfiguration.enabled(embeddingProvider));
```

La configuration opt-in utilise par défaut une RRF avec poids sémantique `8.0`, valeur issue du benchmark réel de l'Itération 17. Le caller peut la surcharger explicitement.

## Invariants

- `NexusApplication.create(paths)` reste complet sans embeddings, réseau ou stockage vectoriel obligatoire.
- SQLite reste la source de vérité canonique.
- L'index vectoriel Lucene est dérivé et reconstructible.
- Aucun repository n'est envoyé à un fournisseur externe sans activation explicite.
- Aucun provider d'embeddings n'est obligatoire.
- Le ranking reste déterministe pour une configuration, un corpus et un jeu de vecteurs donnés.
- Aucun moteur vectoriel externe n'est requis par le périmètre validé.

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
├── ranking historique sans signal sémantique
├── ranking kNN/cosine
└── SemanticHybridContextRanker
      └── Reciprocal Rank Fusion
          k = 60
          poids baseline = 1.0
          poids sémantique = 8.0
```

## Contrats

### `EmbeddingProvider`

Fournit l'identité du modèle, la dimension des vecteurs et la production d'un embedding pour un texte.

### `SemanticSearchIndex`

Abstrait la reconstruction complète, la mise à jour incrémentale, la suppression par chemin et la recherche kNN par projet.

### `SemanticIndexingService`

Transforme les `SearchDocument` en documents vectoriels et suit le cycle d'indexation existant uniquement lorsque la capacité est activée.

### `SemanticSearchStrategy`

Produit des `SearchCandidate` compatibles avec le pipeline historique et ajoute le signal `semanticScore`.

## Stockage vectoriel

`LuceneSemanticSearchIndex` utilise le kNN natif Lucene avec similarité cosine.

Chemin :

```text
indexes/{projectId}/semantic-lucene
```

Ce stockage reste entièrement reconstructible à partir des données canoniques et du provider configuré.

## Provider de référence

Le provider réellement mesuré est `OllamaEmbeddingProvider` :

```text
endpoint   = http://localhost:11434
model      = qwen3-embedding:0.6b
dimensions = 1024
```

Aucune requête Ollama n'est exécutée à la construction du provider. Les appels apparaissent seulement pendant l'indexation sémantique ou une recherche sémantique explicitement activée.

Cette baseline locale ne rend ni Ollama ni ce modèle obligatoires pour NEXUS.

## Fusion des résultats

La première approche additive mélangeait directement BM25, symboles, graphe et cosine. Le benchmark réel a démontré qu'elle supprimait un signal kNN pertinent.

La solution retenue est une **Reciprocal Rank Fusion** :

1. calculer le ranking historique sans contribution sémantique ;
2. calculer séparément le ranking vectoriel ;
3. fusionner les rangs avec `k = 60` ;
4. appliquer un poids `1.0` au canal historique et `8.0` au canal sémantique ;
5. conserver des composantes explicables `baselineRrfScore` et `semanticRrfScore`.

En l'absence de signal sémantique, `SemanticHybridContextRanker` délègue exactement au ranker historique.

## Pourquoi le poids 8.0

Un sweep reproductible a testé :

```text
1.00  1.25  1.50  2.00  3.00  4.00  6.00  8.00
```

Sur le corpus NEXUS figé :

```text
smallestWeightMatchingRawRecallAndHit = 4.0
bestObservedWeightByRecallHitMrr      = 8.0
```

Le poids `8.0` est le seul poids testé qui rejoint simultanément le kNN brut sur :

```text
precision@3 = 0.1667
recall@3    = 0.4167
hit@3       = 0.5000
MRR@3       = 0.3056
```

Il devient donc la valeur par défaut **du mode sémantique opt-in uniquement**.

La valeur peut être remplacée explicitement :

```java
SemanticSearchConfiguration.enabled(embeddingProvider, customWeight);
```

## Benchmark A/B réel final

Corpus hermétique :

```text
commit     = a5d23386fede9b4a4eccf4d5c52308fcd5cae4b1
fichiers   = 236
symboles   = 946
relations  = 1 539
requêtes   = 6
```

Qualité :

| Métrique | Baseline | RRF x8 |
|---|---:|---:|
| `precision@3` | 0,0000 | 0,1667 |
| `recall@3` | 0,0000 | 0,4167 |
| `hit@3` | 0,0000 | 0,5000 |
| `MRR@3` | 0,0000 | 0,3056 |

Coût :

| Métrique | Baseline | RRF x8 |
|---|---:|---:|
| indexation complète | 1 943 ms | 64 332 ms |
| recherche moyenne | 208,8 ms | 298,7 ms |
| index sémantique | 0 | 1 001 537 octets |

Le gain de qualité est réel, mais l'indexation est environ `33,11×` plus lente avec le provider Ollama de référence. La recherche est environ `1,43×` plus lente.

## Quand activer la capacité

Le mode sémantique est pertinent lorsqu'un besoin de recherche conceptuelle ou une forte divergence de vocabulaire rend insuffisante la recherche lexicale/symbolique.

Il ne doit pas être activé automatiquement pour tous les projets : le coût de génération des embeddings doit être accepté explicitement.

## Décision d'adoption

L'Itération 17 conclut :

- capacité sémantique **conservée** ;
- activation **strictement opt-in** ;
- RRF `k=60`, poids `8.0` comme défaut du mode opt-in ;
- Ollama/qwen3-embedding comme baseline locale mesurée, non obligatoire ;
- aucune vector DB externe ;
- aucun changement du chemin historique par défaut ;
- optimisation future du coût d'embedding possible derrière les abstractions existantes, sans remettre en cause SQLite canonique ni Lucene dérivé.

Les mesures détaillées sont conservées dans `docs/developer/iteration-17-semantic-results.md`.

## Scripts

Validation de l'itération :

```powershell
.\scripts\validate-iteration-17.ps1
```

Diagnostic kNN / RRF et sweep :

```powershell
.\scripts\measure-iteration-17-real-semantic-diagnostic.ps1
```

Benchmark A/B réel :

```powershell
.\scripts\measure-iteration-17-real-semantic.ps1
```

Ces benchmarks sont opt-in et ne déclenchent jamais Ollama pendant un `mvn test` standard.
