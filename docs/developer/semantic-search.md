# Recherche sémantique optionnelle — Itération 17

## Objectif

Mesurer si une stratégie de recherche sémantique améliore réellement la qualité de recherche et du contexte NEXUS par rapport au socle lexical + symbolique + graphe, sans rendre les embeddings obligatoires.

Cette itération applique l'ADR-0014 : la recherche sémantique reste **désactivée par défaut**, local-first lorsque possible, et n'est conservée que si les métriques montrent un gain utile.

## Invariants

- `NexusApplication.create(paths)` continue de fonctionner sans modèle d'embeddings, sans réseau et sans stockage vectoriel supplémentaire obligatoire.
- La recherche lexicale, symbolique et graphe reste le chemin complet de repli.
- Aucun contenu de repository n'est envoyé à un fournisseur externe sans activation explicite.
- L'identité et la version du modèle d'embeddings doivent être observables dans les mesures.
- Le ranking reste déterministe pour une configuration, un corpus et un jeu de vecteurs donnés.
- L'activation sémantique doit pouvoir être comparée A/B à la baseline non sémantique sur le même corpus.
- Aucune base vectorielle dédiée n'est introduite tant que Lucene ou une abstraction locale simple suffit.

## Architecture

```text
SearchService
├── LuceneFileSearchStrategy
├── SymbolSearchStrategy
└── SemanticSearchStrategy             optionnelle
        │
        ├── EmbeddingProvider          port
        │     ├── provider local       optionnel
        │     └── provider externe     opt-in uniquement
        │
        └── SemanticSearchIndex        port
              └── LuceneSemanticSearchIndex

Ranking
├── DeterministicContextRanker         chemin historique
└── SemanticHybridContextRanker        opt-in uniquement
      └── Weighted Reciprocal Rank Fusion
```

Le résultat de la stratégie sémantique rejoint les `SearchCandidate` existants au moyen d'un signal `semanticScore`.

En mode sémantique, les scores bruts BM25/symboliques/graphe et cosine ne sont pas additionnés directement. Le ranking historique et le ranking kNN sont ordonnés séparément puis fusionnés par RRF pondérée.

L'index vectoriel est stocké séparément sous `indexes/{projectId}/semantic-lucene`. Il reste entièrement dérivé et reconstructible ; SQLite conserve son rôle canonique.

## Incrément 1 — contrats et stockage vectoriel

Implémenté :

- `EmbeddingProvider` ;
- `SemanticSearchIndex` ;
- `SemanticSearchStrategy` ;
- signal `semanticScore` ;
- `SemanticIndexingService` ;
- `LuceneSemanticSearchIndex` basé sur le kNN natif Lucene et la similarité cosinus ;
- reconstruction complète, delta incrémental et suppression par chemin ;
- `OllamaEmbeddingProvider` explicitement opt-in ;
- `SemanticSearchConfiguration` désactivée par défaut.

### Validation locale du 21 juillet 2026

```text
mvn clean install
63 tests exécutés
0 échec
0 erreur
2 harness opt-in ignorés
BUILD SUCCESS
```

Self-smoke historique : **SUCCESS**.

La baseline historique reste inchangée :

```text
precision@3 = 0,4444
recall@3    = 1,0000
```

## Incrément 2 — composition applicative explicitement opt-in

`NexusApplication.create(paths)` délègue vers `SemanticSearchConfiguration.disabled()` et conserve le comportement historique.

Activation explicite :

```java
NexusApplication.create(
        paths,
        SemanticSearchConfiguration.enabled(embeddingProvider));
```

Lorsque cette configuration est activée :

- le même `EmbeddingProvider` alimente l'indexation et les requêtes ;
- un `LuceneSemanticSearchIndex` est créé avec la dimension du provider ;
- `SemanticIndexingService` rejoint le cycle d'indexation ;
- `SemanticSearchStrategy` rejoint les stratégies du `SearchService` ;
- `SemanticHybridContextRanker` remplace le ranker historique uniquement pour cette composition.

## Incrément 3 — fusion RRF pondérée

Le diagnostic réel a montré que le kNN retrouvait les six cibles dans le top 17 mais que l'ancienne fusion additive détruisait ce signal.

Une RRF déterministe est donc utilisée avec :

```text
RRF k                = 60
poids baseline       = 1,0
poids sémantique     = 8,0
```

Le poids `8,0` n'est pas arbitraire. Il est retenu après un sweep sur le corpus NEXUS figé :

```text
1,00  1,25  1,50  2,00  3,00  4,00  6,00  8,00
```

Résultat :

```text
smallestWeightMatchingRawRecallAndHit = 4.0
bestObservedWeightByRecallHitMrr      = 8.0
```

Avec x8, la fusion rejoint le kNN brut sur les quatre métriques top-3 du corpus mesuré :

```text
precision@3 = 0,1667
recall@3    = 0,4167
hit@3       = 0,5000
MRR@3       = 0,3056
```

La valeur est exposée par `SemanticSearchConfiguration.DEFAULT_SEMANTIC_RRF_WEIGHT` et peut être explicitement remplacée :

```java
SemanticSearchConfiguration.enabled(embeddingProvider, customWeight)
```

La capacité reste néanmoins **désactivée par défaut** : le poids x8 ne s'applique jamais à `NexusApplication.create(paths)`.

## Provider local de baseline

Le provider réel de référence est `OllamaEmbeddingProvider` :

```text
endpoint   = http://localhost:11434
model      = qwen3-embedding:0.6b
dimensions = 1024
```

Aucune requête Ollama n'est exécutée à la construction d'un provider. Le trafic n'existe que lorsque la composition sémantique est explicitement utilisée pour indexer ou rechercher.

## Corpus réel figé

Les benchmarks réels utilisent par défaut le merge final de l'Itération 16 :

```text
CorpusRef = a5d23386fede9b4a4eccf4d5c52308fcd5cae4b1
```

Le code benchmarké reste celui de la branche courante, mais le corpus indexé ne change pas entre les variantes de ranking.

## Benchmarks opt-in

Corpus contrôlé :

```powershell
.\scripts\measure-iteration-17-semantic.ps1
```

Benchmark réel hermétique :

```powershell
.\scripts\measure-iteration-17-real-semantic.ps1
```

Diagnostic kNN / fusion et sweep :

```powershell
.\scripts\measure-iteration-17-real-semantic-diagnostic.ps1
```

Ces harness sont opt-in et ne sont pas exécutés par `mvn test` sans leurs propriétés d'activation.

## Critère d'adoption

La recherche sémantique n'est pas activée globalement simplement parce qu'elle fonctionne techniquement.

La décision finale doit considérer ensemble :

- gain de qualité sur les requêtes réellement sémantiques ;
- latence de recherche ;
- durée d'indexation ;
- taille de l'index vectoriel ;
- coût financier éventuel ;
- volume de données envoyées à l'extérieur ;
- qualité du `ContextBundle` sous budget.

Le poids RRF x8 est désormais le **défaut de la capacité opt-in**, pas le défaut global de NEXUS. Le dernier benchmark A/B réel doit confirmer le compromis qualité/coût avant clôture de l'Itération 17.
