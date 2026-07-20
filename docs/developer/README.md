# Guide développeur NEXUS

Ce répertoire explique **comment NEXUS est réellement implémenté**, pourquoi les composants existent, comment ils collaborent et comment reproduire le comportement localement.

L'objectif est qu'un développeur découvrant le repository puisse :

1. comprendre la mission et les frontières de NEXUS ;
2. suivre un fichier depuis le scan jusqu'à SQLite et Lucene ;
3. comprendre comment une requête devient un classement explicable ;
4. comprendre comment ce classement devient un `ContextBundle` sous budget ;
5. comprendre comment NEXUS réutilise les instructions déjà présentes dans un projet ;
6. comprendre comment les Agent Skills sont découverts puis chargés progressivement ;
7. comprendre comment l'historique Git local enrichit le ranking et le contexte ;
8. reproduire les scénarios depuis la CLI et les tests ;
9. modifier une brique sans casser les principes architecturaux.

> **Important** : `docs/architecture.md` décrit l'architecture courante à haut niveau. Les ADR sous `docs/adr/` conservent les décisions et leurs alternatives. Le présent guide décrit **l'implémentation concrète** et ses flux d'exécution.

## Parcours de lecture recommandé

| Chapitre | Ce que vous apprendrez |
|---|---|
| [1. Architecture d'implémentation](architecture-implementation.md) | packages, couches, dépendances, principaux contrats et classes |
| [2. Indexation locale](indexing-pipeline.md) | scan, ignore rules, SHA-256, JavaParser, SQLite, Lucene, incrémental |
| [3. Recherche et ranking](search-ranking.md) | BM25, recherche de symboles, graphe, fusion, score explicable |
| [4. Construction du contexte](context-building.md) | fragments, fusion, tokens, sélection sous budget, `ContextBundle` |
| [5. Reproduire et déboguer](reproduce-and-debug.md) | build, CLI, self-smoke, données locales, scénarios de diagnostic |
| [6. CLI du MVP](cli-mvp.md) | contrat humain/JSON, codes de sortie, JAR autonome, launchers Windows, métriques |
| [7. Contexte natif des projets](native-context-sources.md) | `AGENTS.md`, Copilot, Claude, Gemini, documentation et configurations existantes |
| [8. Agent Skills](agent-skills.md) | `SKILL.md`, catalogue léger, sélection, activation, ressources, sécurité et budget |
| [9. Contexte Git local](git-context.md) | récence Git, commits liés, diff local ciblé, historique, co-changements et budget Git |

## Vue d'ensemble actuelle

```mermaid
flowchart LR
    U[Utilisateur / IDE / Agent] -->|requête| CLI[CLI / futur API / MCP]
    CLI --> CORE[NEXUS Core]

    subgraph INDEXATION[Indexation]
        SCAN[ProjectScanner]
        JAVA[JavaParserLanguageAnalyzer]
        MD[MarkdownLanguageAnalyzer]
        SQL[(SQLite)]
        LUCENE[(Lucene)]
        SCAN --> JAVA
        SCAN --> MD
        JAVA --> SQL
        MD --> SQL
        JAVA --> LUCENE
        MD --> LUCENE
    end

    subgraph SEARCH[Recherche et ranking]
        LEX[LuceneFileSearchStrategy]
        SYM[SymbolSearchStrategy]
        GRAPH[GraphCandidateEnricher]
        GITRECENCY[GitRecencyCandidateEnricher]
        RANK[DeterministicContextRanker]
        LEX --> GRAPH
        SYM --> GRAPH
        GRAPH --> GITRECENCY
        GITRECENCY --> RANK
    end

    subgraph NATIF[Instructions natives]
        AGENTS[AgentsMdInstructionProvider]
        COPILOT[CopilotInstructionProvider]
        CLAUDE[ClaudeInstructionProvider]
        GEMINI[GeminiInstructionProvider]
        DISC[ContextSourceDiscoveryService]
        AGENTS --> DISC
        COPILOT --> DISC
        CLAUDE --> DISC
        GEMINI --> DISC
    end

    subgraph SKILLS[Agent Skills]
        SP[SkillSourceProvider]
        SD[SkillDiscoveryService]
        SS[SkillSelector]
        SL[SkillLoader]
        SB[SkillContextSelector]
        SP --> SD --> SS --> SL --> SB
    end

    subgraph GITCTX[Contexte Git]
        GP[GitContextSourceProvider]
        LOCALGIT[LocalGitContextSourceProvider]
        LOCALGIT --> GP
    end

    subgraph CONTEXT[Construction du contexte]
        FACTORY[ContextFragmentFactory]
        BUDGET[BudgetedContextSelector]
        BUNDLE[ContextBundle]
        FACTORY --> BUDGET --> BUNDLE
        DISC --> BUNDLE
        SB --> BUNDLE
        GP --> BUNDLE
    end

    CORE --> INDEXATION
    SQL --> SYM
    LUCENE --> LEX
    CORE --> SEARCH
    CORE --> NATIF
    CORE --> SKILLS
    CORE --> GITCTX
    CORE --> CONTEXT
    BUNDLE --> CLI
```

## Position de NEXUS

NEXUS n'est ni un chatbot, ni un LLM, ni un orchestrateur généraliste.

```text
Repository + demande + budget
          │
          ▼
        NEXUS
          │
          ├── code / symboles / tests
          ├── documentation pertinente
          ├── instructions natives applicables
          ├── skills pertinents activés progressivement
          ├── contexte Git local borné
          └── métadonnées de configuration détectées
          │
          ▼
     ContextBundle
          │
          ▼
 LLM / Agent consommateur
```

Le choix du modèle et l'exécution des agents, hooks, MCP ou scripts de skills restent hors du cœur. Le provider Git reste lui aussi strictement en lecture seule et n'effectue aucune opération réseau.

## État d'implémentation

### Itération 0 — Socle

Validée : Java 21, Maven, contrats principaux, JavaParser et architecture sans framework applicatif obligatoire dans le cœur.

### Itération 1 — Indexation locale

Validée : registre de projets, `NEXUS_HOME`, scanner, `.gitignore` / `.nexusignore`, SHA-256, SQLite, migrations, Lucene et indexation incrémentale.

### Itération 2 — Recherche et ranking

Validée : BM25 multi-champs, recherche exacte/fuzzy de symboles, fusion, graphe d'imports, ranking déterministe, explications, `precision@K` et `recall@K`.

### Itération 3 — Construction du contexte

Validée localement le 19 juillet 2026 :

- fragments symboliques ;
- fusion ;
- sélection sous budget ;
- troncatures explicables ;
- 13 tests verts ;
- self-smoke : 3 items, 178/180 tokens, réduction d'environ 96,49 %.

### Itération 4 — CLI utilisable pour le MVP

Validée localement le 19 juillet 2026 :

- sortie humaine et JSON ;
- codes de sortie `0`, `1`, `2` ;
- JAR autonome ;
- launchers Windows ;
- 16 tests verts ;
- `mean precision@3 = 0,4444`, `mean recall@3 = 1,0000` ;
- résultat `SELF-SMOKE SUCCESS`.

Cette validation clôt la Phase 1 et valide le MVP du moteur.

### Itération 5 — Instructions et documentation

Validée localement le 20 juillet 2026 :

- Markdown indexé comme documentation ;
- providers AGENTS, Copilot, Claude et Gemini ;
- scopes repository/répertoire/glob ;
- références `@fichier` sécurisées ;
- déduplication inter-provider et inter-source ;
- budget d'instructions ;
- 19 tests verts ;
- 145 fichiers, 406 symboles, 781 relations ;
- contexte strict : 172/180 tokens ;
- contexte multi-source : 1 185/1 200 tokens ;
- résultat `SELF-SMOKE SUCCESS`.

### Itération 6 — Skills et divulgation progressive

Validée localement le 20 juillet 2026 :

- ADR-0034 ;
- port `SkillSourceProvider` ;
- `LocalAgentSkillsProvider` ;
- racines `.agents/skills`, `.github/skills`, `.claude/skills` ;
- parsing YAML 1.2 du frontmatter avec SnakeYAML Engine ;
- `SkillDescriptor` sans corps Markdown complet ;
- sélection déterministe sur `name` + `description` ;
- `SkillLoader` uniquement après sélection ;
- `SkillContextSelector` sans troncature ;
- isolation des skills hors Lucene générique ;
- 100 fichiers source compilés ;
- 17 fichiers de test compilés ;
- 26 tests verts ;
- index : 170 fichiers, 480 symboles, 926 relations ;
- contexte avec skill : 1 194/1 200 tokens, 550 ms ;
- skill `nexus-context-validation` sélectionné intégralement : 233 tokens ;
- `skillsExecuted = false` ;
- résultat `SELF-SMOKE SUCCESS`.

### Itération 7 — Contexte Git

**En cours — validation locale à effectuer.**

Implémentation actuelle :

- ADR-0035 ;
- port `CandidateEnricher` ;
- chaîne d'enrichissement générique dans `SearchService` ;
- `GraphCandidateEnricher` migré vers ce contrat ;
- `GitRecencyCandidateEnricher` ;
- signal `gitRecencyScore` ;
- bonus de récence configurable, `0,05` par défaut ;
- port `GitContextSourceProvider` ;
- `LocalGitContextSourceProvider` ;
- commits récents liés ;
- historique court des fichiers cibles ;
- résumé du diff local limité aux candidats ;
- détection de co-changements ;
- support des projets imbriqués dans un monorepo ;
- contexte Git désactivé sous 500 tokens ;
- budget Git limité à 15 % du budget global et 500 tokens ;
- métadonnées Git explicables ;
- self-smoke étendu à 13 étapes.

Le chapitre [Contexte Git local](git-context.md) décrit l'implémentation complète.

## Principes à respecter en contribuant

### 1. Le cœur ne dépend pas des clients

Copilot, Claude, MCP, Quarkus, JARVIS ou un registre externe ne doivent pas contaminer les contrats métier.

### 2. Les conventions natives restent dans les providers

Les chemins et formats spécifiques restent dans leurs adaptateurs.

### 3. Découverte d'un skill ne signifie pas chargement complet

`SkillDescriptor` ne doit pas contenir le corps du `SKILL.md`.

### 4. Sélection avant activation

Le `SkillLoader` ne doit recevoir que des `SkillMatch` déjà sélectionnés.

### 5. NEXUS ne doit jamais exécuter un skill

Les scripts et outils déclarés restent sous le contrôle du consommateur.

### 6. Le contexte Git reste local et en lecture seule

Aucun provider Git du cœur ne doit effectuer `fetch`, `pull`, `push`, `checkout` ou commit.

### 7. SQLite est canonique, Lucene est dérivé

Une perte de l'index Lucene doit rester reconstructible.

### 8. Toute sélection doit être explicable

Scores, instructions, skills, contexte Git et exclusions doivent provenir de règles inspectables.

### 9. Le budget appartient au moteur

Le bundle final ne doit jamais dépasser le budget demandé.

### 10. Une décision structurante implique un ADR

Avant de modifier stockage, scoring, protocole ou stratégie de contexte, vérifier si un nouvel ADR est nécessaire.

## Repères dans le code

```text
src/main/java/com/nexus/
├── cli/
├── config/
├── context/
│   └── source/
│       ├── git/             Récence, historique et contexte Git local
│       ├── instruction/     Providers AGENTS / Copilot / Claude / Gemini
│       └── skill/           Catalogue, sélection et activation Agent Skills
├── index/
│   ├── java/
│   ├── markdown/
│   └── scan/
├── persistence/sqlite/
├── project/
├── ranking/
├── search/
│   ├── evaluation/
│   └── lucene/
└── token/
```

## UML simplifié des ports de sources

```mermaid
classDiagram
    class CandidateEnricher {
        <<interface>>
        +enrich(ProjectDescriptor, List~SearchCandidate~)
    }

    class ContextSourceProvider {
        <<interface>>
    }

    class SkillSourceProvider {
        <<interface>>
    }

    class GitContextSourceProvider {
        <<interface>>
        +discover(GitContextQuery) GitContextResult
    }

    class GraphCandidateEnricher
    class GitRecencyCandidateEnricher
    class LocalAgentSkillsProvider
    class LocalGitContextSourceProvider
    class DefaultContextBuilder

    CandidateEnricher <|.. GraphCandidateEnricher
    CandidateEnricher <|.. GitRecencyCandidateEnricher
    SkillSourceProvider <|.. LocalAgentSkillsProvider
    GitContextSourceProvider <|.. LocalGitContextSourceProvider
    DefaultContextBuilder --> ContextSourceProvider
    DefaultContextBuilder --> SkillSourceProvider
    DefaultContextBuilder --> GitContextSourceProvider
```

## Validation locale de référence

```powershell
git pull --ff-only
mvn clean install
.\scripts\self-smoke.ps1 -KeepData
```

Le self-smoke comporte désormais 13 étapes et valide notamment :

```text
Java + Markdown indexés
    ↓
instructions natives sélectionnées
    ↓
contexte multi-source
    ↓
Agent Skill découvert puis activé progressivement
    ↓
contexte Git désactivé sous 500 tokens
    ↓
repository Git local détecté sur un budget supérieur
    ↓
commits liés découverts
    ↓
au moins un item GIT sélectionné
    ↓
budget global respecté
    ↓
SELF-SMOKE SUCCESS
```

Commande de reproduction ciblée :

```powershell
.\scripts\nexus.ps1 context nexus-local "DefaultContextBuilder git context budget recent changes" --budget 1600 --explain --json
```
