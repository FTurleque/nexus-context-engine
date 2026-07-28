# Guide développeur NEXUS

Ce répertoire décrit l'implémentation **actuelle** de NEXUS et sépare clairement trois types de documents :

1. documentation courante — doit suivre le code de `main` ;
2. documents d'itération/benchmark — conservent une mesure historique ;
3. ADR — conservent les décisions et ne sont pas réécrits rétroactivement.

État de référence : 29 juillet 2026, `main` `13fd6970f7350602c7a86aae729ddd4adad771bd`.

## Parcours recommandé

| Sujet | Document |
|---|---|
| architecture globale | [Architecture](../architecture.md) |
| architecture concrète / packages / composition | [Architecture d'implémentation](architecture-implementation.md) |
| limites et dette active | [Limites actuelles](current-limitations.md) |
| roadmap | [Roadmap](../roadmap.md) |
| indexation | [Pipeline d'indexation](indexing-pipeline.md) |
| recherche et ranking | [Recherche et ranking](search-ranking.md) |
| construction du contexte | [Construction du contexte](context-building.md) |
| CLI courante | [CLI](cli.md) |
| contrat historique du MVP | [CLI du MVP — historique](cli-mvp.md) |
| contexte natif | [Contexte natif des projets](native-context-sources.md) |
| Agent Skills | [Agent Skills](agent-skills.md) |
| AI Skills Registry | [AI Skills Registry](ai-skills-registry.md) |
| Git | [Contexte Git local](git-context.md) |
| Code Intelligence | [Code Intelligence](code-intelligence.md) |
| JDT LS | [JDT Language Server](jdt-language-server.md) |
| multi-langage | [Support multi-langage](multi-language.md) |
| MINOS | [MINOS Code Intelligence](minos-code-intelligence.md) |
| REST | [API REST](rest-api.md) |
| MCP | [MCP](mcp.md) |
| recherche multi-repository | [Recherche à grande échelle](large-scale-search.md) |
| runbook baseline multi-repository | [Runbook Itération 16](large-scale-baseline-runbook.md) |
| résultats Itération 16 | [Baseline](iteration-16-baseline-results.md) / [portfolio étendu](iteration-16-extended-portfolio-results.md) |
| recherche sémantique | [Recherche sémantique](semantic-search.md) |
| résultats sémantiques | [Résultats Itération 17](iteration-17-semantic-results.md) |
| reproduction / diagnostic | [Reproduire et déboguer](reproduce-and-debug.md) |

## Vue d'ensemble actuelle

```text
                             consommateurs
                  CLI / REST / MCP / Java / JARVIS
                               │
                               ▼
                        NexusApplication
                               │
            ┌──────────────────┼──────────────────┐
            │                  │                  │
            ▼                  ▼                  ▼
      ProjectRegistry   ProjectIndexingService Search/Context
            │                  │                  │
            │          ┌───────┴────────┐         │
            │          ▼                ▼         │
            │       SQLite           Lucene       │
            │       canonique        dérivé       │
            │          ▲                ▲         │
            │          │                │         │
            │   analyzers/importers/providers     │
            │   JavaParser / SCIP / JDT / MINOS  │
            │                                     │
            └─────────────────────────────────────┘
                               │
                    ranking + sources natives
                               │
                               ▼
                        ContextBundle
```

## Capacités livrées

### Cœur

- Java 21 sans framework applicatif obligatoire ;
- SQLite canonique ;
- Lucene lexical dérivé ;
- indexation incrémentale ;
- ranking déterministe et explicable ;
- construction sous budget ;
- instructions natives ;
- Agent Skills ;
- contexte Git ;
- recherche fédérée ;
- sémantique opt-in.

### Code Intelligence

- JavaParser embarqué ;
- SCIP opportuniste ;
- JDT Language Server opt-in ;
- MINOS via contrat JSON local explicite ;
- support lexical Kotlin, TypeScript, JavaScript, Python et SQL.

### Adaptateurs

- CLI autonome ;
- REST Quarkus ;
- MCP Java STDIO ;
- générateur de configuration Copilot/Claude.

## Organisation du code

```text
src/main/java/com/nexus/
├── application/       façade NexusApplication
├── cli/               adaptateur CLI historique
├── config/            NEXUS_HOME et chemins locaux
├── context/           fragments, budgets, bundle
│   └── source/
│       ├── git/
│       ├── instruction/
│       └── skill/
├── index/             scan, analyse, importers/providers
│   ├── java/
│   ├── jdt/
│   ├── markdown/
│   ├── minos/
│   ├── scan/
│   └── scip/
├── persistence/       ports/adaptateur SQLite
├── project/           registre et état projet
├── ranking/           ranking et graphe
├── search/            stratégies, fédération, sémantique
└── token/             estimation du budget

adapters/
├── rest-quarkus/
├── mcp-java/
└── assistant-clients/
```

## Composition actuelle

`NexusApplication` centralise la composition partagée par REST et MCP :

```text
SqliteDatabase
SqliteProjectRepository
SqliteIndexRepository
LuceneSearchIndex
JavaParserLanguageAnalyzer
MarkdownLanguageAnalyzer
ScipCodeIndexImporter
JdtLanguageServerCodeIntelligenceProvider optionnel
SemanticIndexingService optionnel
SearchService
FederatedSearchService
DefaultContextBuilder
```

La CLI possède encore une composition manuelle similaire. C'est une dette explicite, suivie en Itération 20.

Le pipeline de skills possède également une divergence : `SkillDiscoveryService` sait agréger plusieurs providers, mais `LocalAgentSkillsProvider` instancie aujourd'hui `AiSkillsRegistryProvider` directement. La Phase 6 doit revenir à une composition indépendante des providers.

## Persistance et disponibilité

SQLite est canonique ; Lucene et l'index sémantique sont reconstructibles.

Un projet possède un `IndexStatus` :

```text
NOT_INDEXED
INDEXING
READY
FAILED
```

`DefaultContextBuilder` exige déjà `READY`. La recherche et les outils symboliques doivent encore recevoir le même gate de manière uniforme ; voir [Limites actuelles](current-limitations.md).

## Recherche

Le pipeline principal :

```text
LuceneFileSearchStrategy
SymbolSearchStrategy
SemanticSearchStrategy opt-in
        │
        ▼
CandidateMerger
        │
GraphCandidateEnricher
GitRecencyCandidateEnricher
        │
        ▼
DeterministicContextRanker
ou SemanticHybridContextRanker
```

La recherche multi-projet utilise `FederatedSearchService` et conserve la provenance projet.

La baseline de l'Itération 16 ne justifie pas aujourd'hui Zoekt/OpenGrok ou un index distant. Le prochain chantier de scale porte d'abord sur les scans complets symboles/relations et le graphe reconstruit par requête.

## Construction du contexte

`DefaultContextBuilder` orchestre :

1. recherche et ranking ;
2. filtrage des types demandés ;
3. instructions natives applicables ;
4. discovery/matching/loading de skills ;
5. contexte Git ciblé ;
6. matérialisation et fusion de fragments ;
7. budgets par famille ;
8. sélection finale ;
9. métadonnées d'explication.

Invariant :

```text
ContextBundle.estimatedTokens <= ContextBundle.tokenBudget
```

## Documentation historique vs courante

Les documents portant explicitement le nom d'une itération conservent souvent le raisonnement ou les mesures de cette étape. Une phrase au futur dans un ADR accepté ne doit pas être corrigée rétroactivement : elle reflète le contexte de la décision à sa date.

En revanche :

- `README.md` ;
- `docs/architecture.md` ;
- ce guide ;
- `architecture-implementation.md` ;
- `current-limitations.md` ;
- `docs/roadmap.md`

doivent décrire l'état courant.

`docs/mvp.md` et `cli-mvp.md` sont explicitement des documents historiques du MVP.

## Gates de développement

Gate de base :

```powershell
mvn clean install
.\scripts\self-smoke.ps1
```

Une itération spécialisée doit ajouter son runner ciblé sans remplacer ces deux gates.

Pour une modification du ranking ou du retrieval, rejouer les corpus golden concernés.

Pour une revendication de performance, mesurer avant/après sur le même corpus et la même machine ; une seule durée observée n'est pas une preuve.

## Principes de contribution

1. SQLite reste canonique ; Lucene reste reconstructible.
2. Le cœur ne dépend pas de Quarkus, MCP, Copilot, Claude, JARVIS ou MINOS.
3. Un provider externe reste optionnel.
4. Les scores et sélections restent déterministes et explicables.
5. NEXUS ne lance pas MINOS et n'exécute pas les skills.
6. Le Git context reste local et en lecture seule.
7. Une décision structurante durable implique un ADR.
8. Une optimisation de scale doit être justifiée par une mesure.
9. Une itération n'est pas terminée tant que la documentation courante n'a pas été réconciliée avec l'exact head.
