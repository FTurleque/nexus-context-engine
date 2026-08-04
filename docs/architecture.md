# Architecture de NEXUS

Ce document décrit l'architecture **Phase 6** de NEXUS sur `phase-6-consolidation-hardening`. Les ADR de `docs/adr/` restent historiques et ne sont pas réécrits. La Phase 6 est implémentée mais sa qualification exact-head est encore requise.

## Mission

NEXUS transforme :

```text
repositories + projets enregistrés + requête + budget
```

en recherche classée ou en contexte minimal, pertinent, explicable et borné.

## Invariants

1. JVM d'exécution 21 ou supérieure, bytecode/API ciblés sur Java 21.
2. `NexusApplication` est le composition root applicatif partagé par CLI, REST et MCP.
3. SQLite est canonique.
4. Lucene lexical et sémantique sont dérivés/reconstructibles.
5. Toute lecture d'index interactive exige un projet `READY`.
6. Les providers externes sont optionnels et bornés.
7. Le sémantique est désactivé par défaut.
8. Le ranking et les budgets restent déterministes/explicables.
9. Le contexte fédéré conserve la provenance et n'étend pas implicitement les instructions/skills/Git d'un projet à un autre.

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

La CLI ne reconstruit plus manuellement SQLite, Lucene, providers et ranking.

## Indexation

```text
ProjectScanner
  │  limite taille avant hash/lecture
  ↓
analyses embarquées Java/Markdown
  ↓
SQLite canonique
  │  génération monotone V002
  ├─ import SCIP opportuniste
  ├─ JDT LS opt-in sous timeout
  └─ MINOS explicite via payload
  ↓
Lucene lexical dérivé
  └─ Lucene sémantique dérivé si opt-in
```

Un verrou single-flight empêche deux indexations actives du même projet dans le même processus. Un état persistant non-`READY`, y compris `INDEXING` après crash, impose un rebuild complet lors de la reprise.

## Recherche

Pipeline mono-projet :

```text
LuceneFileSearchStrategy
SymbolSearchStrategy (pool SQLite borné)
       ↓
CandidateMerger
       ↓
GraphCandidateEnricher (graphe en cache par génération)
GitRecencyCandidateEnricher
       ↓
ContextRanker
       ↓
top-K
```

Le mode sémantique ajoute `SemanticSearchStrategy` et `SemanticHybridContextRanker`. Les embeddings sont batchables ; Ollama utilise `/api/embed` en lots.

Recherche fédérée : chaque projet READY est recherché séparément, avec sur-récupération locale bornée avant tri global et diversification `(projectId,path)`.

## Contexte

Le contexte mono-projet combine recherche classée, instructions natives, skills et Git sous budget strict.

Le contexte fédéré :

- reçoit une portée explicite ;
- partage un budget global ;
- construit d'abord chaque contexte dans son projet ;
- entrelace les items round-robin ;
- déduplique les contenus identiques ;
- conserve `ProjectDescriptor` comme provenance ;
- expose allocation, sélection, starvation et déduplication dans les metadata.

## Skills

`SkillDiscoveryService` agrège des `SkillSourceProvider` indépendants. Phase 6 compose séparément :

- `LocalAgentSkillsProvider` ;
- `AiSkillsRegistryProvider`.

Aucun provider n'instancie un autre provider.

## Surfaces

CLI : mono-projet + `search-federated` + `context-federated`.

REST : ressources projet/index/search/context et `/api/v1/federated/search|context`, health/readiness et métriques Micrometer.

MCP STDIO : tools mono-projet + `search_across_projects` + `build_context_across_projects` + `explain_context_across_projects`.

## Opérabilité

- `NEXUS_MAX_FILE_SIZE_BYTES` : 8 MiB par défaut ;
- `NEXUS_CODE_INTELLIGENCE_TIMEOUT_SECONDS` : 180 s par défaut ;
- `NEXUS_SEMANTIC_PROVIDER=ollama` : activation explicite du sémantique ;
- readiness : compte les projets par état ;
- métriques : durée opérations et providers, sans contenu privé comme label.

## Distribution

Version 0.2.0 : Maven Wrapper, fat JAR CLI, ZIP autonome, SHA-256 et SBOM CycloneDX. Voir `docs/developer/release-and-recovery.md`.

## Choix volontairement non adoptés

Sans benchmark justifiant le coût : pas de Zoekt/OpenGrok/OpenSearch, index distribué, vector DB, cache Git persistant ni lifecycle Lucene partagé plus complexe.
