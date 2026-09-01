# Section 5 — Vue des blocs (C4 Container et Component)

Cette vue décrit les blocs **courants** de NEXUS 0.2.0.

## 5.1 Containers

```mermaid
C4Container
    title Diagramme de containers — NEXUS Context Engine

    Person(dev, "Développeur / Agent IA", "«Person»")

    System_Boundary(nexus, "NEXUS Context Engine") {
        Container(cli, "CLI NEXUS", "Java 21 fat JAR", "Interface ligne de commande")
        Container(rest, "Adaptateur REST", "Quarkus + JAX-RS", "API métier HTTP/JSON sur 127.0.0.1:8080 par défaut")
        Container(mgmt, "Management Quarkus", "SmallRye Health + Micrometer", "Health/metrics loopback-only sur 127.0.0.1:9000")
        Container(mcp, "Adaptateur MCP", "Java 21 + MCP SDK", "Serveur MCP STDIO JSON-RPC")
        Container(core, "NEXUS Core", "Java 21, sans framework", "NexusApplication, indexation, recherche, ranking, contexte, fédération")
        ContainerDb(sqlite, "SQLite", "Xerial JDBC", "Source de vérité structurelle")
        ContainerDb(lucene, "Lucene", "Apache Lucene", "Index lexical/sémantique dérivé")
    }

    System_Ext(ollama, "Ollama", "Embeddings opt-in")
    System_Ext(jdtls, "JDT Language Server", "Provider Java profond opt-in")
    System_Ext(minos_ext, "MINOS JSON", "Contrat local explicite")
    System_Ext(repo, "Repository Git local", "Sources, instructions, skills, Git")

    Rel(dev, cli, "Exécute")
    Rel(dev, rest, "Appelle", "HTTP JSON")
    Rel(dev, mcp, "Interagit via assistant", "MCP STDIO")
    Rel(rest, core, "Délègue")
    Rel(mcp, core, "Délègue")
    Rel(cli, core, "Délègue")
    Rel(mgmt, core, "Lit état/metrics")
    Rel(core, sqlite, "Lit / écrit", "JDBC")
    Rel(core, lucene, "Indexe / recherche")
    Rel(core, repo, "Scanne / lit Git")
    Rel(core, ollama, "Embeddings", "HTTPS distant ou HTTP loopback")
    Rel(core, jdtls, "Analyse profonde", "Processus local STDIO")
    Rel(core, minos_ext, "Importe", "JSON")
```

Le listener management est séparé du listener applicatif : `/q/*` ne doit pas être exposé par le reverse proxy métier.

## 5.2 Composants du cœur

```mermaid
C4Component
    title NEXUS Core

    Container_Boundary(core, "NEXUS Core") {
        Component(app, "NexusApplication", "Java", "Composition root partagé CLI/REST/MCP")
        Component(registry, "ProjectRegistry", "Java", "Enregistrement/résolution projets")
        Component(indexing, "ProjectIndexingService", "Java", "Indexation single-flight + revalidation")
        Component(scanner, "ProjectScanner", "Java", "Scan, ignores, hash, exclusions sensibles")
        Component(pathGuard, "ProjectPathGuard", "Java", "Confinement filesystem")
        Component(externalRunner, "ExternalTaskRunner", "Java", "Timeout + max 8 tâches externes actives")
        Component(search, "SearchService", "Java", "Recherche ciblée + Lucene borné")
        Component(fedSearch, "FederatedSearchService", "Java", "Recherche multi-projets bornée")
        Component(contextBuilder, "DefaultContextBuilder", "Java", "Recherche + instructions + skills + Git + budgets")
        Component(fedContext, "FederatedContextService", "Java", "Budget global, provenance, fairness, déduplication")
        Component(redactor, "SensitiveContentRedactor", "Java", "Redaction secrets forte confiance")
    }

    ContainerDb(sqlite, "SQLite", "database")
    ContainerDb(lucene, "Lucene", "database")
    System_Ext(repo, "Repository Git", "Software System")
    System_Ext(ollama, "Ollama", "Software System")
    System_Ext(jdtls, "JDT LS", "Software System")

    Rel(app, registry, "Résout")
    Rel(app, indexing, "Indexe")
    Rel(app, search, "Recherche")
    Rel(app, fedSearch, "Recherche fédérée")
    Rel(app, contextBuilder, "Contexte")
    Rel(app, fedContext, "Contexte fédéré")
    Rel(indexing, scanner, "Scanne")
    Rel(scanner, pathGuard, "Valide chemins")
    Rel(indexing, externalRunner, "Exécute providers externes")
    Rel(indexing, sqlite, "Persiste")
    Rel(indexing, lucene, "Indexe")
    Rel(search, sqlite, "Requêtes ciblées")
    Rel(search, lucene, "Recherche lexicale")
    Rel(contextBuilder, redactor, "Redige fragments retournés")
    Rel(indexing, redactor, "Redige contenu avant embeddings")
    Rel(externalRunner, jdtls, "Pilote")
    Rel(redactor, ollama, "Contenu redigé uniquement")
```

## 5.3 Modèle de contexte

```mermaid
classDiagram
    class ContextRequest {
        <<record>>
        +UUID projectId
        +String query
        +int tokenBudget
        +Set requestedSources
        +Map constraints
        +boolean explain
    }

    class ContextBundle {
        <<record>>
        +int tokenBudget
        +int estimatedTokens
        +List items
        +List excluded
        +Map metadata
    }

    ContextRequest --> ContextBundle
```

`constraints` est conservé dans le contrat pour compatibilité, mais **une map non vide est actuellement rejetée** : NEXUS n'ignore jamais silencieusement une contrainte qu'il ne sait pas appliquer.

## 5.4 Persistance SQLite

```mermaid
erDiagram
    PROJECTS ||--o{ INDEXED_FILES : contains
    INDEXED_FILES ||--o{ SYMBOLS : defines
    PROJECTS ||--o{ SYMBOL_RELATIONS : owns

    SYMBOLS {
        long id PK
        long file_id FK
        string kind
        string name
        string qualified_name
        string signature
        int start_line
        int end_line
        string source_provider "javaparser|jdtls|scip|minos"
    }

    SYMBOL_RELATIONS {
        long id PK
        string project_id FK
        long file_id FK
        string kind
        string source_ref
        string target_ref
        double confidence
        string source_provider
    }

    SCHEMA_MIGRATIONS {
        string version PK
        string script_name
        string applied_at
        string checksum
    }
```

Depuis V005, `SYMBOLS` impose :

```text
start_line >= 1
end_line >= start_line
```

## 5.5 Frontières de sécurité des blocs

- `ProjectPathGuard` : chemins projet canoniques, traversal/symlinks refusés.
- `NexusPaths` : stockage persistant privé sur POSIX et chemins persistants symboliques durcis refusés.
- `JdtJsonRpcFrameReader` : message 16 MiB, headers 64 KiB, ligne 8 KiB, backlog 256.
- `ExternalTaskRunner` : max 8 workers externes actifs.
- `LuceneSearchIndex` : max 128 termes analysés uniques avant expansion sur cinq champs.
- `SensitiveContentRedactor` : redaction avant embeddings et fragments retournés.
- `NexusRestExposureGuard` / transport policy : exposition API hors loopback fail-closed.
- management Quarkus : loopback-only, distinct du listener API.
