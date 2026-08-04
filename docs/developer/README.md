# Guide développeur NEXUS

Ce répertoire distingue :

1. **documentation courante** — doit suivre l'exact head ;
2. **documents d'itération/benchmark** — conservent les résultats historiques ;
3. **ADR** — conservent les décisions et ne sont pas réécrits rétroactivement.

État de travail : Phase 6 qualifiée techniquement sur `phase-6-consolidation-hardening`, version `0.2.0`, intégration de la PR #15 en attente.

## Parcours recommandé

| Sujet | Document |
|---|---|
| architecture globale | [Architecture](../architecture.md) |
| architecture concrète / reactor / composition | [Architecture d'implémentation](architecture-implementation.md) |
| limites et watch items | [Limites actuelles](current-limitations.md) |
| roadmap | [Roadmap](../roadmap.md) |
| release / migration / recovery | [Release et recovery](release-and-recovery.md) |
| indexation | [Pipeline d'indexation](indexing-pipeline.md) |
| recherche et ranking | [Recherche et ranking](search-ranking.md) |
| construction du contexte | [Construction du contexte](context-building.md) |
| CLI | [CLI](cli.md) |
| CLI MVP historique | [CLI du MVP](cli-mvp.md) |
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
| résultats sémantiques historiques | [Itération 17](iteration-17-semantic-results.md) |
| reproduction / diagnostic | [Reproduire et déboguer](reproduce-and-debug.md) |

## Vue d'ensemble Phase 6

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
         │             + generation             │
         │                  │                   │
         │          Lucene dérivé               │
         │                  │                   │
         └──────────── providers ────────────────┘
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

Le core reste physiquement dans `src/`; `core/pom.xml` référence ces sources. Les outputs Maven sont isolés par module et les livrables du core nécessaires aux scripts historiques sont recopiés dans `target/`.

Build complet :

```powershell
.\mvnw.cmd clean install
```

## Capacités courantes

### Cœur

- Java 21 ;
- SQLite canonique ;
- Lucene lexical/sémantique dérivé ;
- indexation incrémentale ;
- single-flight par projet ;
- taille de fichier bornée ;
- ranking déterministe/explicable ;
- recherche symbole/usages bornée ;
- graphe cache par génération ;
- recherche fédérée ;
- `ContextBundle` projet-local et fédéré ;
- instructions natives, Agent Skills, registry local et Git ;
- sémantique opt-in.

### Code Intelligence

- JavaParser ;
- SCIP opportuniste ;
- JDT LS opt-in ;
- MINOS via JSON local explicite ;
- support lexical Kotlin, TypeScript, JavaScript, Python et SQL.

### Adaptateurs

- CLI autonome ;
- REST Quarkus ;
- MCP Java STDIO ;
- générateur de configuration Copilot/Claude.

## Composition unique

`NexusApplication` est maintenant le composition root commun :

```text
SqliteDatabase
SqliteProjectRepository
SqliteIndexRepository
LuceneSearchIndex
ProjectIndexingService
SearchService
FederatedSearchService
DefaultContextBuilder
FederatedContextService
```

La CLI ne possède plus de second câblage manuel. Les providers de skills local et registry sont composés indépendamment.

## Cohérence et recovery

États projet :

```text
NOT_INDEXED
INDEXING
READY
FAILED
```

Les lectures interactives dépendant de l'index exigent `READY`. Toute reprise depuis un état persistant non-READY force un rebuild complet. Un `INDEXING` abandonné par crash n'est donc pas un verrou permanent ; la vraie concurrence active est protégée par le single-flight in-process.

## Scale

Phase 6 applique d'abord des optimisations locales :

- requêtes SQL bornées pour symboles/usages ;
- fuzzy sur pool préfiltré ;
- graphe réutilisé par génération ;
- chargement ciblé des fichiers voisins ;
- sur-récupération avant diversification fédérée.

Les baselines actuelles ne justifient toujours pas Zoekt/OpenGrok/OpenSearch, vector DB ou index distribué.

## Contexte fédéré

`FederatedContextService` construit les bundles locaux sous une allocation globale, conserve la provenance projet, entrelace les items et déduplique les contenus identiques. Instructions, Skills et Git restent projet-locaux.

## Distribution 0.2.0

Le reactor produit :

```text
target/nexus-context-engine-0.2.0-cli.jar
target/nexus-context-engine-0.2.0-cli.jar.sha256
target/distribution/nexus-context-engine-0.2.0.zip
target/distribution/nexus-context-engine-0.2.0.zip.sha256
target/sbom/bom.json
```

Voir [Release et recovery](release-and-recovery.md).

## Gates

Gate Phase 6 Windows :

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\validate-phase-6.ps1
```

Il inclut le `clean install` du reactor, `scripts/self-smoke.ps1`, checksums, SBOM, exécution réelle de l'archive et exact-head.

Une itération ne doit pas être déclarée validée/livrée sans ce log sur le commit concerné.

## Principes de contribution

1. SQLite reste canonique ; Lucene reste reconstructible.
2. Le cœur ne dépend pas de Quarkus, MCP, Copilot, Claude, JARVIS ou MINOS.
3. Tout provider externe reste optionnel et borné.
4. Scores, budgets et sélections restent déterministes et explicables.
5. NEXUS ne lance pas MINOS et n'exécute pas les skills.
6. Git reste local/read-only.
7. Une décision durable structurante implique un ADR.
8. Une optimisation de scale doit être justifiée par une mesure.
9. La documentation courante doit être réconciliée avec l'exact head avant clôture.
