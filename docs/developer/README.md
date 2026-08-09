# Guide développeur NEXUS

Ce répertoire distingue :

1. **documentation courante** — doit suivre l'exact head ;
2. **documents d'itération/benchmark** — conservent les résultats historiques ;
3. **ADR** — conservent les décisions et ne sont pas réécrits rétroactivement.

État courant : NEXUS 0.2.0, Phase 6 intégrée via PR #15, hardening via PR #18, provenance via PR #24, licence via PR #25, supply-chain via PR #28 puis renforcée par PR #49, assistant Windows/Docker via PR #46, consolidation post-audit via PR #49 et réconciliation documentaire finale via PR #61.

## Parcours recommandé

| Sujet | Document |
|---|---|
| architecture globale | [Architecture](../architecture.md) |
| Arc42 | [Documentation d'architecture](../architecture/README.md) |
| architecture concrète | [Architecture d'implémentation](architecture-implementation.md) |
| limites et watch items | [Limites actuelles](current-limitations.md) |
| roadmap | [Roadmap](../roadmap.md) |
| release / migration / recovery | [Release et recovery](release-and-recovery.md) |
| CI / supply-chain | [CI et supply-chain](ci-and-supply-chain.md) |
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
| REST | [API REST](rest-api.md) |
| MCP | [MCP](mcp.md) |
| recherche à grande échelle | [Recherche à grande échelle](large-scale-search.md) |
| sémantique | [Recherche sémantique](semantic-search.md) |
| reproduction / diagnostic | [Reproduire et déboguer](reproduce-and-debug.md) |

## Composition courante

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

Build complet :

```powershell
.\mvnw.cmd clean install
```

## Capacités courantes

### Cœur

- Java 21 ;
- SQLite canonique ;
- Lucene lexical/sémantique dérivé ;
- indexation incrémentale avec revalidation fail-closed du snapshot canonique ;
- mutex JVM + `FileLock` OS par projet ;
- frontière filesystem durcie (`ProjectPathGuard`, `SafeFileIO`, `NOFOLLOW_LINKS`) ;
- taille de fichier bornée et politique SCIP dédiée ;
- recherche symbole/usages et projections de graphe bornées côté SQLite ;
- ranking déterministe/explicable ;
- recherche fédérée et `ContextBundle` fédéré avec coût de travail borné ;
- plafond commun des résultats CLI/REST/MCP ;
- instructions natives, Agent Skills, registry local et Git ;
- sémantique opt-in avec contrôle de provenance.

### Code Intelligence

- JavaParser ;
- SCIP opportuniste avec limites fichier/message ;
- JDT LS opt-in ;
- MINOS via JSON local explicite ;
- snapshots externes invalidés lorsque l'état canonique SOURCE/TEST change ;
- providers externes bornés par timeout wall-clock.

### Adaptateurs et distribution

- CLI autonome ;
- REST Quarkus ;
- MCP Java STDIO ;
- générateur de configuration assistants ;
- installateur Windows EXE avec runtime Java embarqué ;
- assistant Natif / Docker / Both ;
- image Docker avec Trivy, SBOM et attestations lors de la publication `main`.

## Cohérence et recovery

États projet :

```text
NOT_INDEXED
INDEXING
READY
FAILED
```

Les lectures interactives dépendant de l'index exigent `READY`. Toute reprise depuis un état persistant non-READY force un rebuild complet.

La concurrence active est protégée par mutex JVM + `FileLock` OS. Le snapshot canonique est revalidé avant publication ; une mutation concurrente détectée fait échouer l'indexation plutôt que de publier un état mixte.

La garantie inter-processus vise un filesystem local. Un `NEXUS_HOME` réseau n'est pas déclaré supporté sans qualification spécifique.

## REST distant

Loopback reste sûr par défaut. Une exposition hors loopback exige :

- `NEXUS_REST_API_TOKEN` robuste ;
- `NEXUS_REST_ALLOWED_PROJECT_ROOTS` non vide ;
- `NEXUS_REST_EXPOSURE_MODE` explicite ;
- `reverse-proxy-https` ou `direct-https`, avec `loopback-forward` réservé au runtime Docker publié sur loopback côté hôte.

## Scale

Les optimisations locales précèdent tout changement d'architecture :

- requêtes SQL bornées ;
- projections de graphe bornées ;
- sur-récupération fédérée contrôlée ;
- fair floor + refill ;
- budget de travail fédéré distinct du budget final ;
- embeddings batchables.

Zoekt/OpenGrok/OpenSearch, vector DB, FTS supplémentaire, cache Git persistant et lifecycle Lucene partagé restent conditionnés à une mesure démontrant un bénéfice réel.

## Distribution 0.2.0

Le reactor produit notamment :

```text
target/nexus-context-engine-0.2.0-cli.jar
target/nexus-context-engine-0.2.0-cli.jar.sha256
target/distribution/nexus-context-engine-0.2.0.zip
target/distribution/nexus-context-engine-0.2.0.zip.sha256
target/sbom/bom.json
```

La distribution Windows produit également un ZIP x64 autonome et un setup EXE. Voir [Release et recovery](release-and-recovery.md).

## Qualification récente

PR #49 : `QUALIFIED_HEAD=4f04c1ad3ff5b41aa9d1892ade57ad62b90a43f9` — NEXUS CI, Scale Benchmark, Windows Installer, Docker Distribution, CodeQL et OSV-Scanner PASS.

PR #61 : `QUALIFIED_HEAD=ba91be044a600d2396e0939fc154848dc47f6310` — NEXUS CI, CodeQL et OSV-Scanner PASS ; merge `660ca9f07a23950d2a5284605531524372331bc5`.

Aucun workflow/configuration/status SonarCloud actif n'est défini dans la baseline courante.

## Principes de contribution

1. SQLite reste canonique ; les index dérivés restent reconstructibles.
2. Le cœur ne dépend pas de Quarkus, MCP ou d'un orchestrateur externe.
3. Tout provider externe reste optionnel et borné.
4. Scores, budgets et sélections restent déterministes et explicables.
5. NEXUS ne lance pas MINOS et n'exécute pas les skills.
6. Git reste local/read-only.
7. Une décision durable structurante implique un ADR.
8. Une optimisation de scale doit être justifiée par une mesure.
9. La documentation courante doit être réconciliée avec l'exact head avant clôture.
10. Le dépôt étant propriétaire source-available, les contributions externes suivent [`../../CONTRIBUTING.md`](../../CONTRIBUTING.md).
