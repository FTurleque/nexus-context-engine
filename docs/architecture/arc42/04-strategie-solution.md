# Section 4 — Stratégie de solution

## 4.1 Principes architecturaux

| Principe | Formulation | ADR |
|----------|-------------|-----|
| **Local-first** | Aucun appel réseau obligatoire ; l'index est entièrement local | ADR-0005 |
| **Ports / adaptateurs** | Le cœur Java n'a aucune dépendance vers Quarkus, MCP ou un IDE | ARCH-0076, ARCH-0075 |
| **Source de vérité unique** | SQLite est canonique ; tout autre index est dérivé et reconstructible | DATA-0073 |
| **Opt-in strict** | Chaque provider externe (Ollama, JDT LS, MINOS, SCIP) doit être activé explicitement | ADR-0005, ARCH-0080 |
| **Déterminisme / explicabilité** | Scores, ranking et sélection doivent être reproductibles et auditables | BE-0071, BE-0072 |
| **Budget strict** | Le ContextBundle ne peut jamais dépasser le budget déclaré | BE-0072 |
| **Fédération isolée** | Le contexte fédéré multi-projets conserve la provenance et n'étend pas implicitement les sources natives | `docs/architecture/overview.md` Invariant 9 |

## 4.2 Style de décomposition

NEXUS utilise un style **ports / adaptateurs** (Architecture Hexagonale) :

- Le **cœur** (`nexus-context-engine`) contient toute la logique métier : indexation,
  recherche, ranking, construction du contexte et fédération.
- Les **adaptateurs** (REST-Quarkus, MCP-Java, assistant-clients) traduisent les protocoles
  externes vers les opérations du cœur et n'en contiennent aucune logique métier propre.
- `NexusApplication` est le **composition root** partagé par les trois adaptateurs.

```mermaid
flowchart TD
    subgraph Core ["«Software System» NEXUS Core"]
        NA["«Component»\nNexusApplication\nFaçade applicative"]
        IS["«Component»\nProjectIndexingService"]
        SS["«Component»\nSearchService"]
        FSS["«Component»\nFederatedSearchService"]
        CB["«Component»\nDefaultContextBuilder"]
        FCS["«Component»\nFederatedContextService"]
        SQLite[("«database»\nSQLite")]
        Lucene[("«database»\nLucene")]
    end

    CLI["«adapter»\nNexusCli"]
    REST["«adapter»\nREST Quarkus"]
    MCP["«adapter»\nMCP STDIO"]

    CLI --> NA
    REST --> NA
    MCP --> NA
    NA --> IS
    NA --> SS
    NA --> FSS
    NA --> CB
    NA --> FCS
    IS --> SQLite
    IS --> Lucene
    SS --> SQLite
    SS --> Lucene
    CB --> SQLite
```

## 4.3 Technologies structurantes

| Technologie | Rôle | Optionnel |
|-------------|------|-----------|
| **Java 21** | Langage et runtime | Non |
| **Maven 3.9+** | Build multi-module (reactor) | Non |
| **SQLite (Xerial JDBC)** | Persistance canonique structurelle | Non |
| **Apache Lucene** | Index de recherche lexical dérivé | Non |
| **JavaParser** | Analyse AST Java embarquée | Non |
| **JGit** | Règles .gitignore / .nexusignore, contexte Git | Non |
| **Jackson** | Sérialisation JSON | Non |
| **SnakeYAML-Engine** | Parsing frontmatter YAML (skills, instructions) | Non |
| **Quarkus + JAX-RS** | Adaptateur REST | Oui (module séparé) |
| **MCP Java SDK** | Adaptateur MCP STDIO | Oui (module séparé) |
| **Micrometer + Prometheus** | Métriques REST | Oui (avec l'adaptateur REST) |
| **Ollama HTTP API** | Embeddings sémantiques | Opt-in strict |
| **JDT Language Server** | Analyse Java profonde | Opt-in strict |
| **MINOS** | Intelligence de code enrichie | Opt-in strict |
| **SCIP** | Index de code statique externe | Opt-in opportuniste |

## 4.4 Mécanismes permettant d'atteindre les objectifs qualité

| Objectif | Mécanisme |
|----------|-----------|
| Correctness | Ranking déterministe (`DeterministicContextRanker`), hash SHA-256 pour la détection de changements, transactions SQLite atomiques |
| Fiabilité / Sécurité locale | `ProjectPathGuard`, `SafeFileIO` (`NOFOLLOW_LINKS`), `FileLock` OS par projet, verrou JVM single-flight |
| Indépendance fournisseur | Interfaces `ContextSourceProvider`, `CodeIntelligenceProvider`, `CodeIndexImporter` ; pas d'import Quarkus/MCP dans le cœur |
| Opérabilité | `NexusLivenessCheck` / `NexusReadinessCheck` Quarkus, métriques Micrometer, codes de sortie CLI normalisés |
| Évolutivité | Nouveaux providers ajoutés sans modifier `NexusApplication`, ADR pour chaque décision structurante |

## 4.5 Liens vers les ADR structurants

| Décision | ADR |
|----------|-----|
| Positionnement comme moteur de contexte indépendant | [ARCH-0077](../adr/ARCH-0077-positionner-nexus-comme-moteur-de-contexte-independant.md) |
| Java 21 | [ADR-0002](../adr/GEN-0002-compiler-le-coeur-en-java-21.md) |
| Cœur sans framework | [ARCH-0076](../adr/ARCH-0076-garder-un-coeur-java-sans-framework-acyclique-et-verifie.md) |
| Local-first et opt-in | [ADR-0005](../adr/ARCH-0005-adopter-un-fonctionnement-local-first-et-opt-in.md) |
| SQLite canonique | [DATA-0073](../adr/DATA-0073-tenir-sqlite-pour-source-de-verite-et-lucene-pour-index-derive.md) |
| Lucene dérivé | [DATA-0073](../adr/DATA-0073-tenir-sqlite-pour-source-de-verite-et-lucene-pour-index-derive.md) |
| Ranking hybride | [BE-0071](../adr/BE-0071-rechercher-et-classer-les-candidats-par-un-score-compose-explicable.md) |
| ContextBundle sous budget | [BE-0072](../adr/BE-0072-construire-le-contextbundle-sous-budget-de-tokens.md) |
| Adaptateur REST Quarkus | [ARCH-0075](../adr/ARCH-0075-exposer-le-coeur-par-des-adaptateurs-minces-isoles.md) |
| Adaptateur MCP STDIO | [ARCH-0075](../adr/ARCH-0075-exposer-le-coeur-par-des-adaptateurs-minces-isoles.md) |
| Fédération locale | [BE-0081](../adr/BE-0081-federer-la-recherche-locale-par-projet-sous-echeance-globale.md) |
