# Recherche sémantique optionnelle — Itération 17

## Objectif

Mesurer si une stratégie de recherche sémantique améliore réellement la qualité de recherche et du contexte NEXUS par rapport au socle lexical + symbolique + graphe, sans rendre les embeddings obligatoires.

Cette itération applique l'ADR-0014 : la recherche sémantique reste **désactivée par défaut**, local-first lorsque possible, et ne sera conservée durablement que si les métriques montrent un gain utile.

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
```

Le résultat de la stratégie sémantique rejoint les `SearchCandidate` existants au moyen d'un signal `semanticScore`. Le ranking décide de sa contribution avec un poids explicite et explicable. Sans stratégie sémantique configurée, ce signal est absent et le comportement historique est inchangé.

L'index vectoriel est stocké séparément sous `indexes/{projectId}/semantic-lucene`. Il reste entièrement dérivé et reconstructible ; SQLite conserve son rôle canonique.

## Incrément 1 — contrats, ranking et stockage vectoriel

Implémenté :

- `EmbeddingProvider` ;
- `SemanticSearchIndex` ;
- `SemanticSearchStrategy` ;
- signal `semanticScore` ;
- contribution sémantique explicable dans `DeterministicContextRanker` ;
- `SemanticIndexingService` ;
- `LuceneSemanticSearchIndex` basé sur le kNN natif Lucene et la similarité cosinus ;
- reconstruction complète, delta incrémental et suppression par chemin ;
- `OllamaEmbeddingProvider` explicitement opt-in ;
- `SemanticSearchConfiguration` désactivée par défaut.

### Validation locale du 21 juillet 2026

Validation complète fournie depuis Windows / PowerShell :

```text
mvn clean install
63 tests exécutés
0 échec
0 erreur
2 harness opt-in ignorés
BUILD SUCCESS
16,687 s
```

Self-smoke historique : **SUCCESS**.

Mesures du self-smoke sans embeddings actifs :

```text
250 fichiers
1 324 symboles
9 845 relations
indexation complète : 3 017 ms
indexation incrémentale : 817 ms
recherche : 807 ms
contexte strict : 898 ms
contexte multi-source : 1 120 ms
contexte avec skill : 1 107 ms
contexte Git : 1 134 ms
```

Validation ciblée sémantique :

```text
10 tests exécutés
0 échec
0 erreur
0 ignoré
BUILD SUCCESS
3,461 s
```

La baseline historique reste inchangée :

```text
precision@3 = 0,4444
recall@3    = 1,0000
```

Conclusion de l'incrément 1 : contrats embeddings, stratégie sémantique, index vectoriel Lucene, cycle rebuild/delta et ranking `semanticScore` sont validés, tandis que l'activation par défaut reste désactivée.

## Incrément 2 — composition applicative explicitement opt-in

`NexusApplication.create(paths)` délègue explicitement vers `SemanticSearchConfiguration.disabled()` et conserve donc le comportement historique.

Une seconde composition est disponible :

```java
NexusApplication.create(
        paths,
        SemanticSearchConfiguration.enabled(embeddingProvider));
```

Lorsque cette configuration est activée :

- le même `EmbeddingProvider` alimente l'indexation et les requêtes ;
- un `LuceneSemanticSearchIndex` est créé avec la dimension du provider ;
- `SemanticIndexingService` rejoint le cycle d'indexation existant ;
- `SemanticSearchStrategy` rejoint les stratégies du `SearchService` ;
- aucun autre adaptateur n'est obligé d'activer la capacité.

`NexusApplicationSemanticConfigurationTest` vérifie à la fois l'absence totale de `semanticScore` dans la composition historique et la présence effective d'un résultat sémantique dans la composition explicitement activée.

## Provider local de baseline

Le premier provider réel est `OllamaEmbeddingProvider`.

Configuration de référence du harness :

```text
endpoint   = http://localhost:11434
model      = qwen3-embedding:0.6b
dimensions = 1024
```

Aucune requête Ollama n'est exécutée à la construction d'un provider. Le trafic n'existe que lorsque la composition sémantique est explicitement utilisée pour indexer ou rechercher.

## Benchmark A/B opt-in

`SemanticSearchBenchmarkTest` crée un corpus contrôlé de huit documents et cinq requêtes où le vocabulaire de la requête diverge volontairement du document pertinent.

Le même corpus est indexé deux fois :

1. baseline lexical + symbolique + graphe ;
2. même pipeline avec la stratégie sémantique activée.

Le rapport JSON contient :

- `precision@3` ;
- `recall@3` ;
- `hit@3` ;
- `MRR@3` ;
- rang du document pertinent pour chaque requête ;
- latence moyenne de recherche ;
- durée d'indexation ;
- taille de l'index sémantique ;
- identité du modèle, dimension et endpoint ;
- indication explicite permettant de distinguer un endpoint local d'un endpoint distant.

Exécution :

```powershell
.\scripts\measure-iteration-17-semantic.ps1
```

Le modèle peut être préparé explicitement au moment de la mesure avec :

```powershell
.\scripts\measure-iteration-17-semantic.ps1 -PullModel
```

Le benchmark est volontairement opt-in et n'est jamais exécuté par `mvn test` sans la propriété `nexus.semantic.benchmark.enabled=true`.

## Critère d'adoption

La recherche sémantique n'est pas adoptée par défaut simplement parce qu'elle fonctionne techniquement.

Elle ne sera conservée comme capacité recommandée que si le benchmark montre un gain mesurable sur des requêtes réellement sémantiques sans dégradation disproportionnée de la précision, de la latence, du stockage ou de la confidentialité.

Dans le cas contraire, l'itération pourra conclure rationnellement que le socle lexical + symbolique + graphe reste préférable et la capacité sémantique restera expérimentale ou sera retirée.
