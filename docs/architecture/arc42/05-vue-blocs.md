# Section 5 — Vue des blocs (C4 Container et Component)

## 5.1 Diagramme C4 Niveau 2 — Containers

```mermaid
C4Container
    title Diagramme de containers — NEXUS Context Engine

    Person(dev, "Développeur / Agent IA", "«Person»")

    System_Boundary(nexus, "NEXUS Context Engine") {
        Container(cli, "CLI NEXUS", "«Container»\nJava 21 fat JAR", "Interface ligne de commande.\nPoint d'entrée pour l'utilisation directe.")
        Container(rest, "Adaptateur REST", "«Container»\nQuarkus + JAX-RS", "API HTTP/JSON.\nExpose les opérations NEXUS via REST.\nÉcoute sur 127.0.0.1:8080.")
        Container(mcp, "Adaptateur MCP", "«Container»\nJava 21 + MCP SDK", "Serveur MCP STDIO.\nExpose les outils NEXUS aux assistants IA\nvia JSON-RPC 2.0.")
        Container(core, "NEXUS Core", "«Container»\nJava 21, sans framework", "Façade NexusApplication.\nIndexation, recherche, ranking, contexte,\nfédération. Toute la logique métier.")
        ContainerDb(sqlite, "SQLite", "«database»\nSQLite via Xerial JDBC", "Source de vérité structurelle.\nProjects, fichiers indexés, symboles, relations.")
        ContainerDb(lucene, "Index Lucene", "«database»\nApache Lucene", "Index de recherche lexical dérivé.\nReconstructible depuis SQLite.\nLucene sémantique optionnel.")
    }

    System_Ext(ollama, "Ollama", "«Software System»\nServeur d'embeddings local")
    System_Ext(jdtls, "JDT Language Server", "«Software System»\nProvider Java profond")
    System_Ext(minos_ext, "MINOS JSON", "«Software System»\nFichier JSON local versionné")
    System_Ext(repo, "Repository Git local", "«Software System»\nSources, instructions, skills, Git")

    Rel(dev, cli, "Exécute des commandes", "CLI args / stdin")
    Rel(dev, rest, "Appelle l'API", "HTTP JSON")
    Rel(dev, mcp, "Interagit via l'assistant", "MCP STDIO")
    Rel(cli, core, "Délègue les opérations", "Appels Java directs")
    Rel(rest, core, "Délègue les opérations", "Appels Java directs")
    Rel(mcp, core, "Délègue les opérations", "Appels Java directs")
    Rel(core, sqlite, "Lit / Écrit", "JDBC SQL")
    Rel(core, lucene, "Indexe / Recherche", "Lucene API")
    Rel(core, repo, "Scanne les fichiers, lit Git", "Filesystem / JGit")
    Rel(core, ollama, "Génère des embeddings", "HTTP /api/embed (opt-in)")
    Rel(core, jdtls, "Analyse Java profonde", "Processus local (opt-in)")
    Rel(core, minos_ext, "Importe l'intelligence de code", "Lecture JSON (opt-in)")
```

## 5.2 Diagramme C4 Niveau 3 — Composants du NEXUS Core

```mermaid
C4Component
    title Diagramme de composants — NEXUS Core

    Container_Boundary(core, "NEXUS Core") {
        Component(app, "NexusApplication", "«Component»\nJava class", "Composition root et façade.\nInstancie et câble tous les services.\nPartagée par CLI, REST, MCP.")

        Component(registry, "ProjectRegistry", "«Component»\nJava class", "Enregistrement et résolution des projets.\nNormalisation de la racine.")

        Component(indexing, "ProjectIndexingService", "«Component»\nJava class", "Orchestre l'indexation d'un projet.\nSingle-flight par projectId.\nGère l'état NOT_INDEXED → INDEXING → READY|FAILED.")

        Component(scanner, "ProjectScanner", "«Component»\nJava class", "Parcourt le filesystem.\nApplique .gitignore / .nexusignore.\nHash SHA-256, classification FileCategory.")

        Component(analyzers, "LanguageAnalyzers", "«Component»\n«interface»", "JavaParserLanguageAnalyzer, MarkdownLanguageAnalyzer.\nExtension possible via CodeIntelligenceProvider.")

        Component(search, "SearchService", "«Component»\nJava class", "Pipeline de recherche mono-projet.\nFusionne LuceneFileSearchStrategy\net SymbolSearchStrategy.\nEnrichit via graphe et Git.")

        Component(fedSearch, "FederatedSearchService", "«Component»\nJava class", "Recherche multi-projets.\nSur-récupération locale bornée\navant tri global et diversification.")

        Component(contextBuilder, "DefaultContextBuilder", "«Component»\nJava class", "Orchestre : recherche, instructions,\nskills, Git. Applique le budget via\nBudgetedContextSelector.")

        Component(fedContext, "FederatedContextService", "«Component»\nJava class", "Contexte multi-projets.\nBudget global, round-robin,\ndéduplication, provenance.")

        Component(ranker, "ContextRanker", "«Component»\n«interface»", "DeterministicContextRanker (par défaut).\nSemanticHybridContextRanker (opt-in).")

        Component(skills, "SkillDiscoveryService", "«Component»\nJava class", "Agrège LocalAgentSkillsProvider\net AiSkillsRegistryProvider.\nDivulgation progressive.")

        Component(instrProviders, "InstructionProviders", "«Component»\n«interface»", "AgentsMd, Claude, Copilot, Gemini.\nRésolution de scope et applyTo.")

        Component(gitProvider, "LocalGitContextSourceProvider", "«Component»\nJava class", "Contexte Git borné : commits récents,\nfichiers modifiés, message HEAD.")

        Component(lockMgr, "ProjectIndexLockManager", "«Component»\nJava class", "Verrou JVM (ReentrantLock) + FileLock OS.\nSingle-flight inter-processus.")

        Component(pathGuard, "ProjectPathGuard", "«Component»\nJava class", "Confinement filesystem.\nRefuse les liens symboliques hors racine.")
    }

    ContainerDb(sqlite, "SQLite", "«database»")
    ContainerDb(lucene, "Lucene", "«database»")
    System_Ext(repo, "Repository Git", "«Software System»")
    System_Ext(ollama, "Ollama", "«Software System»")

    Rel(app, registry, "Crée / résout")
    Rel(app, indexing, "Lance l'indexation")
    Rel(app, search, "Délègue la recherche")
    Rel(app, fedSearch, "Délègue la recherche fédérée")
    Rel(app, contextBuilder, "Construit le contexte")
    Rel(app, fedContext, "Construit le contexte fédéré")
    Rel(indexing, scanner, "Scanne le filesystem")
    Rel(indexing, analyzers, "Analyse les fichiers")
    Rel(indexing, lockMgr, "Acquiert le verrou")
    Rel(indexing, sqlite, "Persiste l'état", "JDBC")
    Rel(indexing, lucene, "Indexe les documents", "Lucene API")
    Rel(scanner, pathGuard, "Vérifie les chemins")
    Rel(scanner, repo, "Lit les fichiers", "Filesystem / JGit")
    Rel(search, sqlite, "Recherche symboles", "SQL borné")
    Rel(search, lucene, "Recherche lexicale", "Lucene API")
    Rel(contextBuilder, search, "Récupère les candidats")
    Rel(contextBuilder, instrProviders, "Découvre les instructions")
    Rel(contextBuilder, skills, "Découvre et sélectionne les skills")
    Rel(contextBuilder, gitProvider, "Récupère le contexte Git")
    Rel(ranker, ollama, "Embeddings vectoriels", "HTTP (opt-in)")
```

## 5.3 Modèle de domaine — Entités principales

```mermaid
classDiagram
    class ProjectDescriptor {
        <<record>>
        +UUID id
        +String name
        +Path rootPath
        +ProjectSourceType sourceType
        +List~String~ languages
        +List~String~ technologies
        +Instant lastIndexedAt
        +IndexStatus indexStatus
    }

    class IndexStatus {
        <<enumeration>>
        NOT_INDEXED
        INDEXING
        READY
        FAILED
    }

    class ContextRequest {
        <<record>>
        +UUID projectId
        +String query
        +int tokenBudget
        +Set~CandidateType~ requestedSources
        +Map~String,String~ constraints
        +boolean explain
    }

    class ContextBundle {
        <<record>>
        +int tokenBudget
        +int estimatedTokens
        +List~ContextItem~ items
        +List~String~ excluded
        +Map~String,Object~ metadata
    }

    class ContextItem {
        <<record>>
        +CandidateType type
        +Path path
        +String symbol
        +int startLine
        +int endLine
        +String content
        +double score
        +Map scoreComponents
        +List~String~ reasons
        +int estimatedTokens
        +boolean truncated
    }

    class RankedCandidate {
        <<record>>
        +SearchCandidate candidate
        +double score
        +Map scoreComponents
        +List~String~ reasons
    }

    class SearchCandidate {
        <<record>>
        +String id
        +CandidateType type
        +Path path
        +String excerpt
        +CodeSymbol symbol
    }

    ProjectDescriptor --> IndexStatus
    ContextBundle "1" --> "*" ContextItem
    RankedCandidate --> SearchCandidate
```

## 5.4 Schéma de données SQLite

```mermaid
erDiagram
    PROJECTS ||--o{ PROJECT_LANGUAGES : has
    PROJECTS ||--o{ PROJECT_TECHNOLOGIES : has
    PROJECTS ||--o{ INDEXED_FILES : contains
    INDEXED_FILES ||--o{ SYMBOLS : defines
    PROJECTS ||--o{ SYMBOL_RELATIONS : owns
    INDEXED_FILES ||--o{ SYMBOL_RELATIONS : contributes

    PROJECTS {
        string id PK "UUID métier"
        string name
        string root_path UK "chemin canonicalisé"
        string source_type
        string last_indexed_at
        string index_status
    }

    INDEXED_FILES {
        long id PK "identifiant local SQLite"
        string project_id FK
        string relative_path
        string language
        long size_bytes
        string content_hash "SHA-256"
        string modified_at
        int estimated_tokens
        string category
    }

    SYMBOLS {
        long id PK
        long file_id FK
        string kind
        string name
        string qualified_name
        string signature
        int start_line
        int end_line
        string source_provider "java_parser|jdt|scip|minos"
    }

    SYMBOL_RELATIONS {
        long id PK
        string project_id FK
        long file_id FK
        string kind "CALLS|IMPLEMENTS|EXTENDS|..."
        string source_ref
        string target_ref
        double confidence
        string source_provider
    }

    SCHEMA_MIGRATIONS {
        string version PK
        string script_name
        string applied_at
    }
```

## 5.5 Responsabilités par conteneur

| Conteneur | Responsabilité | Interfaces exposées | Dépendances |
|-----------|----------------|---------------------|-------------|
| CLI | Parsing args, formatage humain/JSON, codes de sortie | - | NexusApplication |
| Adaptateur REST | Routing HTTP, DTO, mapping, auth Bearer, health, métriques | REST `/api/v1/` | NexusApplication, Quarkus |
| Adaptateur MCP | Framing JSON-RPC STDIO, tool specifications, sérialisation | MCP tools | NexusApplication, MCP Java SDK |
| NEXUS Core | Indexation, recherche, ranking, contexte, fédération | NexusApplication (façade) | SQLite, Lucene, Filesystem, Providers opt-in |
| SQLite | Persistance structurelle canonique | JDBC SQL | - |
| Lucene | Index lexical / sémantique dérivé | Lucene Query API | SQLite (reconstruction) |
