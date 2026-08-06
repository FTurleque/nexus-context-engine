# Guide développeur NEXUS

Ce répertoire distingue :

1. **documentation courante** — doit suivre l'exact head ;
2. **documents d'itération/benchmark** — conservent les résultats historiques ;
3. **ADR** — conservent les décisions et ne sont pas réécrits rétroactivement.

État courant : NEXUS 0.2.0, Phase 6 intégrée via PR #15, hardening post-Phase 6 via PR #18, provenance des index via PR #24 et licence propriétaire publique via PR #25.

## Parcours recommandé

| Sujet | Document |
|---|---|
| architecture globale | [Architecture](../architecture.md) |
| Arc42 | [Documentation d'architecture](../architecture/README.md) |
| architecture concrète / reactor / composition | [Architecture d'implémentation](architecture-implementation.md) |
| limites et watch items | [Limites actuelles](current-limitations.md) |
| roadmap | [Roadmap](../roadmap.md) |
| release / migration / recovery | [Release et recovery](release-and-recovery.md) |
| provenance/fraîcheur des index | [Index provenance](../index-provenance.md) |
| indexation | [Pipeline d'indexation](indexing-pipeline.md) |
| recherche et ranking | [Recherche et ranking](search-ranking.md) |
| construction du contexte | [Construction du contexte](context-building.md) |
| CLI | [CLI](cli.md) |
| contexte natif | [Contexte natif](native-context-sources.md) |
| Agent Skills | [Agent Skills](agent-skills.md) |
| AI Skills Registry | [AI Skills Registry](ai-skills-registry.md) |
| Git | [Contexte Git](git-context.md) |
| Code Intelligence | [Code Intelligence](code-intelligence.md) |
| JDT LS | [JDT Language Server](jdt-language-server.md) |
| multi-langage | [Support multi-langage](multi-language.md) |
| MINOS | [MINOS Code Intelligence](minos-code-intelligence.md) |
| REST | [API REST](rest-api.md) |
| MCP | [MCP](mcp.md) |
| recherche multi-repository | [Recherche à grande échelle](large-scale-search.md) |
| baseline I16 | [Runbook](large-scale-baseline-runbook.md) / [résultats](iteration-16-baseline-results.md) |
| sémantique | [Recherche sémantique](semantic-search.md) |
| reproduction / diagnostic | [Reproduire et déboguer](reproduce-and-debug.md) |

## Vue d'ensemble

```text
                      CLI / REST / MCP
                            │
                            ▼
                     NexusApplication
                            │
         ┌──────────────────┼───────────────────┐
         ▼                  ▼                   ▼
 ProjectRegistry   ProjectIndexingService   Search / Context
         │                  │                   │
         │          SQLite canonique            │
         │       + génération/fingerprint       │
         │                  │                   │
         │          Lucene dérivé               │
         │                  │                   │
         └──── providers/importers bornés ──────┘
                            │
              ranking / sources natives
                            │
          ┌─────────────────┴────────────────┐
          ▼                                  ▼
    ContextBundle                  FederatedContextBundle
```

## Reactor Maven

```text
pom.xml                         nexus-context-engine-parent:0.2.0
├── core/                       nexus-context-engine:0.2.0
├── adapters/rest-quarkus/
├── adapters/mcp-java/
└── adapters/assistant-clients/
```

Le core reste physiquement dans `src/`; `core/pom.xml` référence ces sources. Build complet :

```powershell
.\mvnw.cmd clean install
```

## Capacités courantes

### Cœur

- Java 21 ;
- SQLite canonique ;
- Lucene lexical/sémantique dérivé ;
- indexation incrémentale ;
- single-flight par projet dans la JVM **et entre processus** via `FileLock` OS ;
- frontière filesystem durcie (`ProjectPathGuard`, `SafeFileIO`, `NOFOLLOW_LINKS`) ;
- taille de fichier bornée ;
- ranking déterministe/explicable ;
- recherche symbole/usages bornée ;
- graphe cache par génération ;
- recherche fédérée ;
- `ContextBundle` projet-local et fédéré ;
- instructions natives, Agent Skills, registry local et Git ;
- sémantique opt-in avec contrôle de provenance.

### Code Intelligence

- JavaParser ;
- SCIP opportuniste ;
- JDT LS opt-in ;
- MINOS via JSON local explicite ;
- snapshots externes invalidés lorsque l'état canonique SOURCE/TEST change ;
- support lexical Kotlin, TypeScript, JavaScript, Python et SQL.

### Adaptateurs

- CLI autonome ;
- REST Quarkus ;
- MCP Java STDIO ;
- générateur de configuration Copilot/Claude.

## Cohérence et recovery

États projet :

```text
NOT_INDEXED
INDEXING
READY
FAILED
```

Les lectures interactives dépendant de l'index exigent `READY`. Toute reprise depuis un état persistant non-READY force un rebuild complet.

La concurrence active est protégée à deux niveaux :

1. mutex JVM par projet ;
2. `FileLock` OS par projet sous `NEXUS_HOME/locks`.

La garantie inter-processus vise un filesystem local. Un `NEXUS_HOME` réseau n'est pas déclaré supporté sans qualification spécifique.

Les index dérivés sont réutilisés seulement lorsqu'ils sont cohérents avec l'état canonique. L'index sémantique porte un manifeste de provenance ; les snapshots externes obsolètes sont invalidés.

## Scale

NEXUS applique d'abord des optimisations locales :

- requêtes SQL bornées pour symboles/usages ;
- fuzzy sur pool préfiltré ;
- graphe réutilisé par génération ;
- chargement ciblé des fichiers voisins ;
- sur-récupération avant diversification fédérée ;
- fair floor + refill pour le contexte fédéré.

Les baselines actuelles ne justifient pas Zoekt/OpenGrok/OpenSearch, vector DB, FTS supplémentaire ou index distribué. Les benchmarks complémentaires sont suivis par #23.

## Distribution 0.2.0

Le reactor produit :

```text
target/nexus-context-engine-0.2.0-cli.jar
target/nexus-context-engine-0.2.0-cli.jar.sha256
target/distribution/nexus-context-engine-0.2.0.zip
target/distribution/nexus-context-engine-0.2.0.zip.sha256
target/sbom/bom.json
```

Le ZIP autonome inclut `LICENSE`. Voir [Release et recovery](release-and-recovery.md).

## Qualification

Gate Windows :

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\validate-phase-6.ps1
```

Preuve récente : PR #24, head exact `25c12b100b774a4ec3d69d221675bf31d8ebaa0c`, NEXUS CI run #15 : Windows Java 24 PASS, Linux Java 21 reactor PASS et distribution smoke PASS.

Une itération ne doit pas être déclarée validée/livrée sans preuve exécutable sur le commit concerné.

## Principes de contribution

1. SQLite reste canonique ; les index dérivés restent reconstructibles.
2. Le cœur ne dépend pas de Quarkus, MCP, Copilot, Claude, JARVIS ou MINOS.
3. Tout provider externe reste optionnel et borné.
4. Scores, budgets et sélections restent déterministes et explicables.
5. NEXUS ne lance pas MINOS et n'exécute pas les skills.
6. Git reste local/read-only.
7. Une décision durable structurante implique un ADR.
8. Une optimisation de scale doit être justifiée par une mesure.
9. La documentation courante doit être réconciliée avec l'exact head avant clôture.
10. Le dépôt étant propriétaire source-available, les contributions externes suivent [`../../CONTRIBUTING.md`](../../CONTRIBUTING.md).
