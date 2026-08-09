# Architecture d'implémentation — NEXUS 0.2.0

Ce chapitre décrit l'organisation concrète du code **courant sur `main`** après Phase 6, hardening, provenance, supply-chain, Windows/Docker et consolidation post-audit.

## Repository

```text
nexus-context-engine/
├── pom.xml                         parent/reactor 0.2.0
├── core/pom.xml                    module core, sources historiques dans ../src
├── src/main/java/com/nexus/        domaine + application
├── src/main/resources/             migrations SQLite
├── src/test/java/com/nexus/
├── adapters/
│   ├── rest-quarkus/
│   ├── mcp-java/
│   └── assistant-clients/
├── distribution/
├── packaging/windows/
├── scripts/
├── .github/workflows/
└── docs/
```

Le parent centralise Java 21, versions/BOM, plugins, Enforcer, licence, JaCoCo et SBOM. Les adaptateurs restent séparés du cœur tout en partageant la gouvernance Maven.

## Composition root

`NexusApplication` compose les ports partagés par toutes les surfaces :

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

CLI, REST et MCP délèguent à cette façade ; leurs politiques de résultat s'appuient sur la même `ResultLimitPolicy`.

## Indexation et cohérence

`ProjectIndexingService` orchestre :

1. acquisition du verrou de mutation — mutex JVM puis `FileLock` OS ;
2. passage à `INDEXING` ;
3. scan sécurisé et borné ;
4. construction du snapshot/fingerprint canonique ;
5. analyses embarquées et mise à jour SQLite ;
6. invalidation/refresh des données externes dérivées ;
7. mise à jour Lucene lexical ;
8. mise à jour/rebuild sémantique selon provenance ;
9. **revalidation du snapshot canonique avant publication** ;
10. passage à `READY`, ou `FAILED` si une mutation concurrente ou une autre erreur rend le résultat incohérent.

Un état persistant non-`READY` force le prochain rebuild complet.

### Single-flight

- mutex JVM par `projectId` ;
- `FileLock` OS sous `NEXUS_HOME/locks/{projectId}.lock`.

Cette garantie vise un `NEXUS_HOME` local. La présence du fichier de lock n'est pas un lease ; seul le `FileLock` actif compte.

## Génération et fingerprint

`project_index_generations` sert à invalider des caches dérivés. La génération ne progresse pas lorsqu'une opération est un no-op effectif.

`CanonicalIndexFingerprint` représente de façon déterministe l'état canonique pertinent. Il sert à la provenance sémantique et à la revalidation du snapshot avant publication.

## Intelligence de code externe

Les snapshots externes sont des enrichissements dérivés.

- changement SOURCE/TEST ⇒ invalidation des snapshots persistés concernés ;
- providers/importers configurés peuvent republier un snapshot courant ;
- persistance SQL des providers externes dédupliquée ;
- exécution externe bornée par `ExternalTaskRunner` ;
- SCIP possède une politique de taille dédiée et une borne du message Protobuf avant allocation.

## Repository et graphe à grande échelle

`IndexRepository` expose des opérations bornées pour éviter la matérialisation globale :

```java
findFiles(projectId, relativePaths)
searchSymbols(projectId, query, limit)
searchRelations(projectId, symbol, limit)
generation(projectId)
```

Les besoins de graphe utilisent des projections/voisinages SQL bornés avec budgets de nœuds/arêtes. SQLite filtre avant matérialisation.

## Ranking et recherche

- Lucene lexical : candidats fichiers ;
- SQLite borné : candidats symboles ;
- graphe : enrichissement sur projections bornées ;
- Git : signal local ;
- sémantique : stratégie optionnelle avec garde de provenance ;
- ranker déterministe ou hybrid RRF si sémantique activée.

La fédération sur-récupère localement puis trie/diversifie globalement sous bornes explicites.

## ContextBuilder

`DefaultContextBuilder` reste projet-local. `FederatedContextService` orchestre plusieurs bundles sous :

- budget global final ;
- fair floor ;
- déduplication ;
- refill ;
- provenance projet ;
- **budget de travail** distinct, afin de borner le coût préparatoire même si le budget final est petit.

## Sémantique

`SemanticSearchConfiguration.fromEnvironment()` est la résolution commune. Sans `NEXUS_SEMANTIC_PROVIDER`, aucun provider d'embeddings n'est créé.

`SemanticIndexProvenance` persiste :

- fingerprint canonique ;
- provider ID ;
- model ID ;
- dimensions ;
- content profile ;
- semantic schema version.

Mismatch/absence ⇒ rebuild. `SemanticSearchStrategy` refuse un index incompatible avant `EmbeddingProvider.embed(...)` pour la requête.

## Filesystem

`ProjectPathGuard` + `SafeFileIO` imposent :

- racine canonique ;
- refus des symlinks pour les lectures sensibles ;
- `NOFOLLOW_LINKS` sur le composant final ;
- lecture réellement bornée.

La protection portable n'est pas un sandbox absolu contre un acteur local hostile ; `NEXUS_HOME` réseau n'est pas qualifié pour la garantie `FileLock`.

## REST

La configuration locale par défaut reste loopback. Une exposition hors loopback est validée par les gardes REST :

- token robuste ;
- allowlist `NEXUS_REST_ALLOWED_PROJECT_ROOTS` ;
- mode `NEXUS_REST_EXPOSURE_MODE` explicite ;
- `reverse-proxy-https` ou `direct-https` ;
- `loopback-forward` réservé à `NEXUS_RUNTIME=docker` avec publication hôte loopback.

## Packaging Windows et Docker

Le build produit CLI/ZIP multiplateforme, distribution Windows autonome et setup EXE. Le profil Windows recommandé ne rend pas REST obligatoire.

Le runtime Docker conserve MCP en STDIO via `docker exec -i`. La qualification image couvre round-trip dotenv, Trivy, SBOM CycloneDX et gate des vulnérabilités HIGH/CRITICAL corrigibles. Les publications `main` portent des attestations de provenance et de SBOM liées au digest publié.

## Qualification récente

PR #49 :

```text
QUALIFIED_HEAD=4f04c1ad3ff5b41aa9d1892ade57ad62b90a43f9
MERGE_SHA=c1ff9ef03ef33097c0d51154e02c30109b0a46f1
```

NEXUS CI, Scale Benchmark, Windows Installer, Docker Distribution, CodeQL et OSV-Scanner : PASS.

PR #61 :

```text
QUALIFIED_HEAD=ba91be044a600d2396e0939fc154848dc47f6310
MERGE_SHA=660ca9f07a23950d2a5284605531524372331bc5
```

NEXUS CI, CodeQL et OSV-Scanner : PASS.
