# Recherche multi-repository et passage à l'échelle

Ce document conserve la baseline validée de l'Itération 16 et décrit les améliorations Phase 6 implémentées au-dessus de cette base.

## Baseline historique validée

```text
repositories               7
fichiers                    2 104
symboles                    10 878
relations                   10 087
index Lucene cumulé         5 121 497 octets
indexation complète         8 818 ms
incrémental sans changement 762 ms
recherche fédérée p50       133 ms
recherche fédérée p95       304 ms
contexte p50                48 ms
contexte p95                206 ms
precision@3                 0,4583
recall@3                    0,8958
hit@3                       1,0000
MRR@3                       1,0000
```

Résultats détaillés :

- [`iteration-16-baseline-results.md`](iteration-16-baseline-results.md) ;
- [`iteration-16-extended-portfolio-results.md`](iteration-16-extended-portfolio-results.md) ;
- [`large-scale-baseline-runbook.md`](large-scale-baseline-runbook.md).

Cette baseline ne doit pas être réécrite avec des chiffres Phase 6 tant qu'une nouvelle campagne mesurée n'a pas été exécutée.

## Architecture fédérée Phase 6

```text
projectIds explicites et READY
       ↓
NexusApplication.searchAcrossProjects
       ↓
FederatedSearchService
       ├─ SearchService(A, localOverfetch)
       ├─ SearchService(B, localOverfetch)
       └─ SearchService(C, localOverfetch)
       ↓
tri global déterministe
       ↓
diversification (projectId,path)
       ↓
top-K global
```

Le pool local est désormais supérieur au top-K final, borné de 20 à 500 avec facteur 4. Le défaut F01 où FILE/SYMBOL d'un même chemin pouvaient vider le résultat après diversification est couvert par un test de régression.

## Scale symboles/usages

Phase 6 remplace les scans applicatifs par des opérations repository bornées :

```text
searchSymbols(projectId, query, limit)
searchRelations(projectId, symbol, limit)
```

SQLite préfiltre avant matérialisation. Le fuzzy Levenshtein reste en Java, mais uniquement sur un pool borné.

## Graphe

V002 introduit une génération monotone de l'index canonique par projet. `ProjectGraphBuilder` conserve une vue dérivée en mémoire tant que cette génération ne change pas.

Conséquence : le graphe n'est plus reconstruit à chaque recherche et l'enrichisseur charge seulement les fichiers voisins nécessaires.

## Surfaces publiques

La fédération n'est plus limitée à la façade interne :

```text
CLI  search-federated
REST POST /api/v1/federated/search
MCP  search_across_projects
```

Les trois surfaces délèguent à `NexusApplication` et appliquent le même gate READY.

## Contexte multi-projet

Phase 6 livre également `FederatedContextService` :

- portée explicite ;
- budget global ;
- provenance ;
- fairness round-robin ;
- déduplication de contenu ;
- métriques de starvation ;
- instructions/skills/Git projet-locaux.

Surfaces : CLI `context-federated`, REST `/api/v1/federated/context`, MCP `build_context_across_projects` et `explain_context_across_projects`.

## Décision moteurs externes

Les optimisations Phase 6 suivent le principe « améliorer le moteur local avant d'ajouter une infrastructure ». Aucun Zoekt, OpenGrok, OpenSearch, index distant, index distribué ou parallélisme fédéré n'est ajouté automatiquement.

Réexaminer un moteur externe seulement si une campagne reproductible démontre malgré les requêtes bornées et le cache graphe :

- p95 non acceptable ;
- mémoire/disque non acceptable ;
- reconstruction trop coûteuse ;
- volume de symboles au-delà des capacités locales ;
- besoin réel d'un index partagé/distant ;
- gain de pertinence mesuré impossible avec Lucene/SQLite.

Le lifecycle Lucene partagé reste lui aussi un watch item jusqu'à preuve de gain.

## Qualification Phase 6

Le runner historique I16 reste utile pour comparer la baseline. Le gate d'intégration Phase 6 est :

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\validate-phase-6.ps1
```

Une campagne de performance post-Phase 6 peut ensuite rejouer les corpus I16 pour mesurer le gain réel des requêtes ciblées et du cache de graphe sans falsifier la baseline historique.
