# Architecture d'implémentation — Phase 6

Ce chapitre décrit l'organisation concrète du code sur `phase-6-consolidation-hardening`. La qualification locale exact-head reste nécessaire avant promotion.

## Repository

```text
nexus-context-engine/
├── pom.xml                         parent/reactor 0.2.0
├── core/pom.xml                    module core, sources historiques dans ../src
├── src/main/java/com/nexus/        domaine + application + adapters locaux
├── src/main/resources/             migrations SQLite
├── src/test/java/com/nexus/
├── adapters/
│   ├── rest-quarkus/
│   ├── mcp-java/
│   └── assistant-clients/
├── distribution/                   launchers + assembly
├── scripts/
│   ├── self-smoke.ps1
│   └── validate-phase-6.ps1
├── mvnw.cmd / mvnw
└── docs/
```

Le parent centralise Java 21, versions de dépendances/BOM, plugins, Enforcer et SBOM. Les adaptateurs restent des modules optionnels mais ne dérivent plus chacun leur propre gouvernance Maven.

## Composition root

`NexusApplication` compose les ports et adapters partagés par toutes les surfaces :

```text
NexusPaths
  ↓
SqliteDatabase
  ├─ SqliteProjectRepository
  └─ SqliteIndexRepository
  ↓
ProjectRegistry
ProjectIndexingService
SearchService
FederatedSearchService
DefaultContextBuilder
FederatedContextService
```

La CLI fait uniquement parsing/rendu et délègue les opérations à cette façade. REST et MCP font de même.

## Indexation et cohérence

`ProjectIndexingService` :

1. acquiert un verrou single-flight par projet ;
2. marque le projet `INDEXING` ;
3. scanne les fichiers supportés sous plafond de taille ;
4. analyse les fichiers modifiés ;
5. met à jour SQLite ;
6. rafraîchit SCIP/JDT selon configuration ;
7. met à jour les index dérivés ;
8. marque `READY` ;
9. en cas d'échec, marque `FAILED`.

Un état persistant non-`READY` force le prochain rebuild complet. Un `INDEXING` laissé par un crash est donc récupérable.

V002 ajoute `project_index_generations`. Les mises à jour canoniques font avancer cette génération, utilisée pour invalider le cache de graphe.

## Repositories à grande échelle

`IndexRepository` garde les APIs exhaustives nécessaires aux rebuilds, mais ajoute des chemins bornés :

```java
findFiles(projectId, relativePaths)
searchSymbols(projectId, query, limit)
searchRelations(projectId, symbol, limit)
generation(projectId)
```

SQLite surcharge ces méthodes pour filtrer avant matérialisation. Les doubles de tests restent compatibles via les implémentations par défaut.

## Ranking

- Lucene : candidats fichiers ;
- SQLite borné : candidats symboles ;
- graphe : cache dérivé par génération ;
- Git : signal local ;
- sémantique : stratégie optionnelle ;
- ranker déterministe, ou hybrid RRF lorsque le sémantique est activé.

La fédération sur-récupère localement puis trie/diversifie globalement.

## ContextBuilder

`DefaultContextBuilder` reste projet-local. `FederatedContextService` orchestre plusieurs ContextBundle locaux sous un budget global et ajoute provenance/fairness/déduplication sans changer les règles natives de chaque projet.

## Providers

Code intelligence :

- JavaParser embarqué ;
- SCIP import opportuniste ;
- JDT LS explicite ;
- MINOS import explicite et hors orchestration NEXUS.

Skills :

- `LocalAgentSkillsProvider` ;
- `AiSkillsRegistryProvider` ;
- composition indépendante via `SkillDiscoveryService`.

## Sémantique

`SemanticSearchConfiguration.fromEnvironment()` est la seule résolution opérationnelle commune. Sans `NEXUS_SEMANTIC_PROVIDER`, aucun provider d'embeddings n'est créé.

`SemanticIndexingService` batch les documents. `OllamaEmbeddingProvider` surcharge `embedAll` et utilise l'entrée tableau de `/api/embed`.

## Packaging

Le module core produit dans `core/target`, puis copie les deux JAR attendus dans `target/` pour conserver la compatibilité avec les scripts historiques. Le parent conserve son propre `target/sbom`, évitant toute collision de `clean` entre modules.

Le ZIP standalone est assemblé dans `target/distribution` et accompagné d'un SHA-256.
