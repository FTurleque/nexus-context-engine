# Architecture de NEXUS

Ce document décrit l'architecture **courante** de NEXUS 0.2.0 sur `main`. Les ADR de `docs/adr/` conservent l'historique des décisions ; les anciennes branches de Phase 6/hardening ne représentent plus l'état actif.

## Mission

NEXUS transforme :

```text
repositories + projets enregistrés + requête + budget
```

en recherche classée ou en contexte minimal, pertinent, explicable et borné.

## Invariants

1. JVM d'exécution 21 ou supérieure, bytecode/API ciblés sur Java 21.
2. `NexusApplication` est le composition root partagé par CLI, REST et MCP.
3. SQLite est canonique.
4. Lucene lexical/sémantique et intelligence de code externe sont dérivés/reconstructibles.
5. Toute lecture interactive dépendant d'un index exige un projet `READY`.
6. Les providers/importers externes sont optionnels et bornés par une enveloppe wall-clock.
7. Le sémantique est désactivé par défaut.
8. Le ranking et les budgets restent déterministes/explicables.
9. Le contexte fédéré conserve la provenance et n'étend pas implicitement instructions/skills/Git d'un projet à un autre.
10. Une donnée dérivée n'est réutilisée que si sa compatibilité avec l'état canonique courant est démontrée.
11. Une seule mutation d'index par projet est active à la fois sur un `NEXUS_HOME` local, y compris entre processus.

## Reactor Maven

```text
pom.xml                         parent/reactor 0.2.0
├── core/                       io.github.fturleque:nexus-context-engine
├── adapters/rest-quarkus/
├── adapters/mcp-java/
└── adapters/assistant-clients/
```

Les sources historiques du core restent dans `src/`; `core/pom.xml` les référence sans déplacement massif. Les dépendances/BOM/plugins sont gouvernés depuis le parent.

## Composition applicative

```text
CLI ───────┐
REST ──────┼──> NexusApplication
MCP ───────┘        │
                    ├─ ProjectRepository / IndexRepository (SQLite)
                    ├─ ProjectIndexingService
                    ├─ SearchService
                    ├─ FederatedSearchService
                    ├─ DefaultContextBuilder
                    └─ FederatedContextService
```

La CLI ne reconstruit pas manuellement SQLite, Lucene, providers et ranking.

## Indexation et autorité

```text
ProjectScanner
  │  ProjectPathGuard + limites de taille + SafeFileIO
  ↓
analyses embarquées Java/Markdown
  ↓
SQLite canonique + génération/fingerprint
  │
  ├─ SCIP opportuniste
  ├─ JDT LS opt-in sous timeout
  └─ MINOS explicite via payload
  ↓
Lucene lexical dérivé
  └─ Lucene sémantique dérivé si opt-in
```

### Concurrence

Une mutation par projet est protégée par :

1. un mutex JVM par `projectId` ;
2. un `FileLock` OS par projet sous `NEXUS_HOME/locks`.

Ce mécanisme couvre les mutations applicatives concernées, notamment index/rebuild/deep-Java et import MINOS. La présence du fichier de lock n'est pas le verrou : seul le `FileLock` actif exprime la propriété exclusive.

Le support cible est un `NEXUS_HOME` sur filesystem local. Les sémantiques de lock sur filesystem réseau ne sont pas revendiquées.

### Recovery d'état

Un état persistant non-`READY`, y compris `INDEXING` après crash, impose un rebuild complet lors de la reprise. SQLite reste l'autorité de recovery ; les index Lucene sont supprimables/reconstructibles.

## Provenance des index dérivés

### Intelligence externe

Quand l'état canonique des fichiers `SOURCE`/`TEST` change, les snapshots persistés des providers externes non embarqués sont invalidés, même si le provider n'est plus actif dans le runtime courant. Un provider/importer configuré peut ensuite republier un snapshot courant.

### Sémantique

Le commit Lucene sémantique persiste un manifeste contenant :

- fingerprint canonique ;
- identité du provider ;
- modèle ;
- dimensions vectorielles ;
- profil de préparation du contenu ;
- version de schéma.

Une provenance absente ou incompatible force un rebuild. `SemanticSearchStrategy` vérifie la compatibilité avant de générer l'embedding de requête, afin de ne pas servir un espace vectoriel obsolète.

Voir [`index-provenance.md`](index-provenance.md).

## Frontière filesystem

`ProjectPathGuard` et `SafeFileIO` imposent la frontière de confiance :

- racine canonique ;
- refus des symlinks sous le repository ;
- composant final ouvert avec `NOFOLLOW_LINKS` ;
- revalidation et lecture bornée par `NEXUS_MAX_FILE_SIZE_BYTES`.

Cette politique couvre notamment scanner, fichiers d'ignore, instructions/références, Agent Skills, JDT LS, `ContextFragmentFactory` et SCIP.

Le modèle Java portable ne constitue pas un sandbox absolu contre un acteur local qui modifie agressivement des répertoires ancêtres/hard-links pendant le traitement.

## Recherche

Pipeline mono-projet :

```text
LuceneFileSearchStrategy
SymbolSearchStrategy (pool SQLite borné)
SemanticSearchStrategy (opt-in + provenance guard)
       ↓
CandidateMerger
       ↓
GraphCandidateEnricher (graphe en cache par génération)
GitRecencyCandidateEnricher
       ↓
ContextRanker / SemanticHybridContextRanker
       ↓
top-K
```

Les embeddings sont batchables ; Ollama utilise `/api/embed` en lots.

Recherche fédérée : chaque projet `READY` est recherché séparément, avec sur-récupération locale bornée avant tri global et diversification `(projectId,path)`.

## Contexte

Le contexte mono-projet combine recherche classée, instructions natives, skills et Git sous budget strict.

Le contexte fédéré :

- reçoit une portée explicite ;
- partage un budget global ;
- utilise un fair floor déterministe ;
- déduplique ;
- réutilise le budget libéré via refill ;
- conserve `ProjectDescriptor` comme provenance ;
- expose allocation, sélection, starvation, déduplication et refill dans les metadata.

## Skills

`SkillDiscoveryService` agrège des `SkillSourceProvider` indépendants :

- `LocalAgentSkillsProvider` ;
- `AiSkillsRegistryProvider`.

Aucun provider n'instancie un autre provider.

## Surfaces

CLI : mono-projet + `search-federated` + `context-federated`.

REST : ressources projet/index/search/context et `/api/v1/federated/search|context`, health/readiness et métriques Micrometer.

MCP STDIO : tools mono-projet + `search_across_projects` + `build_context_across_projects` + `explain_context_across_projects`.

## Opérabilité et sécurité

- `NEXUS_MAX_FILE_SIZE_BYTES` : 8 MiB par défaut ;
- `NEXUS_CODE_INTELLIGENCE_TIMEOUT_SECONDS` : 180 s par défaut ;
- `NEXUS_SEMANTIC_PROVIDER=ollama` : activation explicite ;
- REST écoute `127.0.0.1` par défaut ;
- host non-loopback sans `NEXUS_REST_API_TOKEN` : démarrage refusé ;
- readiness et liveness sont séparées ;
- métriques sans contenu privé comme label.

## Distribution

Version 0.2.0 : Maven Wrapper, fat JAR CLI, ZIP autonome, SHA-256, SBOM CycloneDX et `LICENSE` propriétaire source-available. Voir [`developer/release-and-recovery.md`](developer/release-and-recovery.md).

## Qualification intégrée

La qualification récente de PR #24 sur le head exact `25c12b100b774a4ec3d69d221675bf31d8ebaa0c` a passé :

- Windows Java 24 et `scripts/validate-phase-6.ps1` ;
- Linux Java 21 Maven reactor ;
- smoke de la distribution Linux.

PR #24 est intégrée dans `main` via `c7a03479a78713b78ec2ddc477e1d07d400d8aba`.

## Choix volontairement non adoptés

Sans benchmark justifiant le coût : pas de Zoekt/OpenGrok/OpenSearch, index distribué, vector DB, cache Git persistant, FTS supplémentaire ni lifecycle Lucene partagé plus complexe.
