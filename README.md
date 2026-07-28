# NEXUS Context Engine

> Moteur local d'intelligence de contexte pour projets logiciels : recherche, ranking explicable et construction de `ContextBundle` sous budget.

NEXUS n'est ni un chatbot, ni un LLM, ni un orchestrateur d'agents. Il se place entre un repository et le consommateur IA afin de sélectionner le contexte technique réellement utile.

## État actuel

État de référence au 29 juillet 2026 :

```text
repository  FTurleque/nexus-context-engine
main        13fd6970f7350602c7a86aae729ddd4adad771bd
Java        21
version     0.1.0-SNAPSHOT
roadmap     Itérations 0 → 17 terminées
MINOS       intégration issue #11 / PR #12 livrée
```

Dernière qualification NEXUS documentée avec l'intégration MINOS :

```text
sources main   128
sources test   41
tests          80
failures       0
errors         0
skipped        6
BUILD SUCCESS
```

La prochaine phase est une phase de **consolidation et hardening**, pas une course aux intégrations. Voir [`docs/roadmap.md`](docs/roadmap.md) et [`docs/developer/current-limitations.md`](docs/developer/current-limitations.md).

## Ce que NEXUS sait faire

### Indexation et recherche

- registre de plusieurs projets locaux ;
- scan respectant `.gitignore`, `.nexusignore` et des exclusions sensibles intégrées ;
- SQLite comme source de vérité structurelle ;
- Lucene comme index lexical dérivé et reconstructible ;
- indexation incrémentale par SHA-256 ;
- recherche BM25 multi-champs ;
- recherche exacte/fuzzy de symboles ;
- graphe d'imports et enrichissement structurel ;
- ranking déterministe et explicable ;
- recherche fédérée sur une liste explicite de projets.

### Langages et Code Intelligence

| Capacité | État |
|---|---|
| Java lexical | natif |
| Java structurel | JavaParser embarqué |
| Kotlin | lexical natif |
| TypeScript / JavaScript | lexical natif |
| Python | lexical natif |
| SQL | lexical natif |
| SCIP | import opportuniste `index.scip` |
| JDT Language Server | provider Java profond opt-in via `--deep-java` |
| MINOS | import JSON local explicite via `minos-import` |

Les langages sans analyseur structurel embarqué restent pleinement recherchables lexicalement. Leur structure peut être enrichie par SCIP, MINOS ou un autre provider compatible.

### Sources de contexte

NEXUS peut construire un bundle à partir de :

```text
FILE
SYMBOL
TEST
DOCUMENTATION
INSTRUCTION
SKILL
GIT
```

Il comprend notamment :

- `AGENTS.md` / `AGENT.md` ;
- instructions GitHub Copilot ;
- `CLAUDE.md` / `.claude/CLAUDE.md` ;
- `GEMINI.md` ;
- Agent Skills `SKILL.md` avec divulgation progressive ;
- snapshot local AI Skills Registry ;
- contexte Git local borné ;
- documentation Markdown.

NEXUS sélectionne les skills mais **n'exécute jamais leurs scripts**.

### Recherche sémantique

La recherche sémantique est une capacité locale **opt-in** validée :

- `EmbeddingProvider` abstrait le fournisseur ;
- `LuceneSemanticSearchIndex` utilise le kNN/cosine Lucene ;
- `SemanticHybridContextRanker` utilise une RRF déterministe ;
- Ollama/qwen3-embedding constitue la baseline locale mesurée ;
- aucun provider d'embeddings ni vector DB n'est obligatoire.

Elle reste désactivée par défaut car la baseline réelle a mesuré un coût d'indexation d'environ `33×` le chemin lexical.

## Architecture

```text
                         NEXUS
                           │
          ┌────────────────┼────────────────┐
          │                │                │
   Project Registry   Context Sources   Code Intelligence
          │                │                │
        SQLite       instructions       JavaParser
                     skills             SCIP
                     Git                JDT LS opt-in
                     docs               MINOS explicite
                          \             /
                           \           /
                            ▼         ▼
                         Indexation
                     SQLite + Lucene
                            │
                            ▼
                  SearchService / fédération
                            │
                   enrichissements graphe/Git
                            │
                            ▼
                    ranking explicable
                            │
                            ▼
                  DefaultContextBuilder
                            │
                       budget tokens
                            │
                            ▼
                      ContextBundle
                            │
            ┌───────────────┼───────────────┐
            ▼               ▼               ▼
           CLI             REST             MCP
```

Le cœur reste Java 21 sans framework applicatif obligatoire. Quarkus et le SDK MCP vivent dans des adaptateurs séparés.

Documentation d'architecture : [`docs/architecture.md`](docs/architecture.md).

## Surfaces disponibles

### CLI

Le build principal produit un JAR bibliothèque et un JAR CLI autonome.

Commandes actuelles :

```text
project add <chemin> [nom] [--json]
project list [--json]
index <id-ou-nom> [--rebuild] [--deep-java] [--json]
minos-import <id-ou-nom> < export-minos.json [--json]
search <id-ou-nom> <requête> [--limit N] [--explain] [--json]
context <id-ou-nom> <requête> [--budget N] [--explain] [--json]
inspect <id-ou-nom> [--json]
--help [--json]
--version [--json]
```

Guide courant : [`docs/developer/cli.md`](docs/developer/cli.md).

Le document [`docs/developer/cli-mvp.md`](docs/developer/cli-mvp.md) conserve uniquement le contrat historique du MVP.

### REST

L'adaptateur Quarkus se trouve sous `adapters/rest-quarkus` et expose les opérations projet, indexation, recherche, contexte et explication, avec health et métriques.

Documentation : [`docs/developer/rest-api.md`](docs/developer/rest-api.md).

Par défaut, le serveur écoute sur `127.0.0.1:8080`.

### MCP

L'adaptateur Java MCP STDIO se trouve sous `adapters/mcp-java` et expose :

```text
list_projects
search_code
find_symbol
find_usages
build_context
explain_context
```

Documentation : [`docs/developer/mcp.md`](docs/developer/mcp.md).

### Copilot / Claude

`adapters/assistant-clients` génère les configurations nécessaires pour connecter le serveur MCP NEXUS à Copilot CLI, Copilot JetBrains et Claude.

## Utilisation locale de la CLI

Construire :

```powershell
mvn clean install
```

Enregistrer et indexer un projet :

```powershell
.\scripts\nexus.ps1 project add N:\workspace-dev\mon-app mon-app
.\scripts\nexus.ps1 index mon-app
```

Recherche :

```powershell
.\scripts\nexus.ps1 search mon-app "service de facturation" --limit 10 --explain
```

Contexte sous budget :

```powershell
.\scripts\nexus.ps1 context mon-app "Corriger la réconciliation des factures" --budget 2000 --explain
```

Analyse Java profonde lorsque JDT LS est configuré :

```powershell
.\scripts\nexus.ps1 index mon-app --deep-java
```

Import MINOS explicite :

```powershell
Get-Content -Raw .\minos-export.json | .\scripts\nexus.ps1 minos-import mon-app
```

## Principes de sécurité

- fonctionnement local-first ;
- secrets et formats sensibles connus exclus du scanner ;
- références d'instructions confinées au repository ;
- contexte Git en lecture seule ;
- aucun script de skill exécuté ;
- aucun lancement MINOS depuis NEXUS ;
- aucun provider sémantique activé implicitement ;
- REST lié à loopback par défaut.

## Limites connues

Les principales limites actives sont désormais suivies explicitement :

- top-K fédéré à durcir après diversification ;
- scans complets de symboles/relations à éliminer pour la montée en charge ;
- gate `IndexStatus.READY` à uniformiser ;
- cohérence SQLite/Lucene à formaliser sur panne partielle ;
- composition CLI/builds à unifier ;
- concurrence et ressources d'indexation à borner ;
- recherche fédérée et sémantique à exposer de façon cohérente dans les adaptateurs ;
- `ContextBundle` fédéré multi-projet non encore livré ;
- distribution encore en `0.1.0-SNAPSHOT`.

Détail et preuves : [`docs/developer/current-limitations.md`](docs/developer/current-limitations.md).

## Documentation

- [Architecture courante](docs/architecture.md)
- [Roadmap active](docs/roadmap.md)
- [Guide développeur](docs/developer/README.md)
- [Limites et dette active](docs/developer/current-limitations.md)
- [Indexation](docs/developer/indexing-pipeline.md)
- [Recherche et ranking](docs/developer/search-ranking.md)
- [Construction du contexte](docs/developer/context-building.md)
- [Code Intelligence](docs/developer/code-intelligence.md)
- [Multi-langage](docs/developer/multi-language.md)
- [Recherche à grande échelle](docs/developer/large-scale-search.md)
- [Recherche sémantique](docs/developer/semantic-search.md)
- [MINOS](docs/developer/minos-code-intelligence.md)
- [REST](docs/developer/rest-api.md)
- [MCP](docs/developer/mcp.md)
- [ADR](docs/adr/README.md)

## Validation locale

Le gate de base du repository reste :

```powershell
mvn clean install
.\scripts\self-smoke.ps1
```

Les scripts `validate-iteration-*.ps1` et `measure-iteration-*.ps1` conservent les validations et benchmarks spécialisés.

## Licence

Le choix de la licence reste volontairement ouvert tant que le repository n'est pas rendu public.
