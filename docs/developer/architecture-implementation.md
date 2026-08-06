# Architecture d'implémentation — NEXUS 0.2.0

Ce chapitre décrit l'organisation concrète du code **courant sur `main`** après Phase 6, hardening post-Phase 6 et PR #24. Les anciennes branches `phase-6-consolidation-hardening` et `hardening/post-phase6-audit` sont historiques.

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

Le parent centralise Java 21, versions de dépendances/BOM, plugins, Enforcer, licence et SBOM. Les adaptateurs restent optionnels mais partagent la même gouvernance Maven.

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

La CLI fait parsing/rendu et délègue à cette façade. REST et MCP utilisent la même composition.

## Indexation et cohérence

`ProjectIndexingService` orchestre :

1. acquisition du verrou de mutation par projet — mutex JVM puis `FileLock` OS ;
2. passage du projet à `INDEXING` ;
3. scan sécurisé et borné ;
4. calcul du fingerprint canonique ;
5. analyse embarquée et mise à jour SQLite ;
6. invalidation des snapshots externes persistés si `SOURCE`/`TEST` a changé ;
7. refresh des importers/providers configurés ;
8. mise à jour Lucene lexical ;
9. mise à jour/rebuild sémantique selon compatibilité de provenance ;
10. passage à `READY`, ou `FAILED` en cas d'échec.

Un état persistant non-`READY` force le prochain rebuild complet. Un `INDEXING` laissé par crash est donc récupérable.

### Single-flight

La façade de production protège une mutation avec :

- un mutex JVM par `projectId` ;
- un `FileLock` OS sous `NEXUS_HOME/locks/{projectId}.lock`.

Cette garantie inter-processus vise un `NEXUS_HOME` local. Le fichier de lock peut rester présent après libération ; seule la possession du `FileLock` est significative.

## État canonique, génération et fingerprint

`project_index_generations` fournit une génération monotone utilisée notamment pour invalider des caches dérivés. Cette génération est un signal de cache, pas la preuve sémantique de compatibilité d'un index externe.

`CanonicalIndexFingerprint` calcule un SHA-256 déterministe à partir des métadonnées canoniques pertinentes des fichiers, notamment chemin relatif normalisé, hash de contenu, langage et catégorie.

Le fingerprint est la preuve de compatibilité utilisée pour la provenance sémantique.

## Intelligence de code externe

Les symboles/relations embarqués restent identifiés par le provider canonique JavaParser. Les snapshots non embarqués sont traités comme des enrichissements dérivés.

Si un fichier canonique `SOURCE`/`TEST` est ajouté, modifié ou supprimé :

1. tous les providers externes actuellement persistés sont collectés ;
2. leurs snapshots sont remplacés par un snapshot vide/invalide ;
3. les importers/providers configurés dans le runtime courant peuvent ensuite republier un snapshot cohérent.

Cette invalidation couvre notamment un snapshot JDT créé dans un runtime précédent puis absent lors du re-index suivant, ainsi qu'un import MINOS devenu obsolète.

## Repositories à grande échelle

`IndexRepository` conserve les APIs exhaustives nécessaires aux rebuilds et expose des chemins bornés :

```java
findFiles(projectId, relativePaths)
searchSymbols(projectId, query, limit)
searchRelations(projectId, symbol, limit)
generation(projectId)
```

SQLite filtre avant matérialisation. Les implémentations alternatives restent compatibles via les méthodes par défaut.

## Ranking

- Lucene lexical : candidats fichiers ;
- SQLite borné : candidats symboles ;
- graphe : cache dérivé par génération ;
- Git : signal local ;
- sémantique : stratégie optionnelle avec garde de provenance ;
- ranker déterministe ou hybrid RRF lorsque le sémantique est activé.

La fédération sur-récupère localement puis trie/diversifie globalement.

## ContextBuilder

`DefaultContextBuilder` reste projet-local. `FederatedContextService` orchestre plusieurs bundles locaux sous un budget global avec provenance, fair floor, déduplication et refill.

## Providers

Code intelligence :

- JavaParser embarqué ;
- SCIP opportuniste ;
- JDT LS explicite ;
- MINOS import explicite et hors orchestration NEXUS.

Tous les providers/importers externes passent par l'enveloppe wall-clock commune lorsque leur intégration le requiert.

Skills :

- `LocalAgentSkillsProvider` ;
- `AiSkillsRegistryProvider` ;
- composition indépendante via `SkillDiscoveryService`.

## Sémantique

`SemanticSearchConfiguration.fromEnvironment()` est la résolution opérationnelle commune. Sans `NEXUS_SEMANTIC_PROVIDER`, aucun provider d'embeddings n'est créé.

`EmbeddingProvider` expose `providerId()`, `modelId()` et `dimensions()`.

`SemanticIndexProvenance` persiste dans les commit data Lucene :

- fingerprint canonique ;
- provider ID ;
- model ID ;
- dimensions ;
- content profile ;
- semantic schema version.

`SemanticIndexingService` :

- vérifie la compatibilité du manifeste ;
- force un rebuild si l'index est absent, legacy ou incompatible ;
- applique les changements incrémentaux uniquement lorsque la provenance correspond ;
- encode le profil de préparation du contenu pour invalider les vecteurs si les règles de préparation changent.

`SemanticSearchStrategy`, dans la composition de production, recalcule/cache le fingerprint depuis SQLite et refuse un index incompatible **avant** l'appel à `EmbeddingProvider.embed(...)` pour la requête.

`OllamaEmbeddingProvider` peut batcher via `/api/embed`.

## Filesystem

`ProjectPathGuard` + `SafeFileIO` imposent la frontière :

- racine canonique ;
- refus des symlinks sous le repository ;
- `NOFOLLOW_LINKS` sur le composant final ;
- lecture réellement bornée.

Cette politique couvre scanner, ignore files, instructions/références, Agent Skills, JDT LS, `ContextFragmentFactory` et SCIP.

## Packaging

Le module core produit dans `core/target`, puis copie les JAR attendus dans `target/` pour compatibilité avec les scripts historiques. Le parent conserve `target/sbom`.

Le ZIP standalone est assemblé dans `target/distribution`, accompagné d'un SHA-256 et inclut `LICENSE`.

## Qualification récente

PR #24, head exact `25c12b100b774a4ec3d69d221675bf31d8ebaa0c` :

- Windows Java 24 / `scripts/validate-phase-6.ps1` : PASS ;
- Linux Java 21 reactor : PASS ;
- distribution Linux smoke : PASS.

Merge dans `main` : `c7a03479a78713b78ec2ddc477e1d07d400d8aba`.
