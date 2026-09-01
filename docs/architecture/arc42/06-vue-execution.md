# Section 6 — Vue d'exécution

Les scénarios ci-dessous décrivent le comportement **courant** de NEXUS 0.2.0 et les ordres de validation qui ont une valeur de correctness/sécurité.

## 6.1 Indexation nominale d'un projet

```mermaid
sequenceDiagram
    actor User as Développeur
    participant CLI as NexusCli
    participant App as NexusApplication
    participant Lock as ProjectIndexLockManager
    participant Index as ProjectIndexingService
    participant Scanner as ProjectScanner
    participant Guard as ProjectPathGuard
    participant SQLite as SQLite
    participant Lucene as Lucene

    User->>CLI: nexus index project
    CLI->>App: index(projectId, rebuild, deepJava)
    App->>Index: index(...)
    Index->>Lock: acquire JVM + FileLock
    Index->>SQLite: project = INDEXING
    Index->>Scanner: scanWithDiagnostics(root)
    Scanner->>Guard: valider les chemins sensibles
    Scanner-->>Index: fichiers + fingerprint canonique
    Index->>SQLite: applyChanges transactionnels
    Index->>Lucene: applyChanges/rebuild
    Index->>Scanner: scan final + fingerprint
    alt repository inchangé
        Index->>SQLite: project = READY
        Index-->>App: IndexingReport
    else mutation détectée ou erreur provider/index
        Index->>SQLite: project = FAILED
        Index-->>App: IOException / RuntimeException
    end
    Index->>Lock: release
```

Un projet n'est jamais publié `READY` si le fingerprint canonique final diffère de celui ayant servi à construire les index.

## 6.2 Contexte mono-projet et `constraints`

```mermaid
sequenceDiagram
    actor Agent as Agent IA
    participant Adapter as CLI / REST / MCP
    participant App as NexusApplication
    participant ProjectRepo as ProjectRepository
    participant Request as ContextRequest
    participant Builder as DefaultContextBuilder
    participant Search as SearchService
    participant Native as Instructions / Skills / Git
    participant Budget as BudgetedContextSelector

    Agent->>Adapter: build_context(project, query, budget, sources, constraints)
    Adapter->>App: context(...)
    App->>ProjectRepo: requireReadyProject(projectId)
    ProjectRepo-->>App: READY
    App->>Request: new ContextRequest(...)
    alt constraints non vide
        Request-->>App: IllegalArgumentException
        App-->>Adapter: erreur explicite / bad_request selon surface
    else constraints vide
        App->>Builder: build(request)
        Builder->>Search: search(...)
        Builder->>Native: discover sous ContextDiscoveryBudget
        Native-->>Builder: fragments bornés
        Builder->>Budget: select(..., tokenBudget)
        Budget-->>Builder: ContextBundle
        Builder-->>App: ContextBundle
        App-->>Adapter: résultat explicable
    end
```

Le champ `constraints` reste présent pour compatibilité de contrat, mais une contrainte inconnue n'est jamais ignorée silencieusement.

Les contenus de contexte retournés passent par la redaction de secrets à forte confiance avant exposition au client.

## 6.3 Recherche fédérée — fail-fast de cardinalité

```mermaid
sequenceDiagram
    actor Dev as Développeur
    participant Adapter as CLI / REST / MCP
    participant Policy as FederatedScopePolicy
    participant App as NexusApplication
    participant Repo as ProjectRepository
    participant Fed as FederatedSearchService
    participant Search as SearchService

    Dev->>Adapter: search-federated(projectIds, query, limit)
    Adapter->>Policy: normalizeProjectIds(projectIds)
    alt plus de 100 UUID uniques
        Policy-->>Adapter: TOO_MANY_PROJECTS / IllegalArgumentException
    else scope valide
        Policy-->>Adapter: UUIDs canoniques, ordre stable
        Adapter->>App: searchAcrossProjects(scope,...)
        App->>Policy: normalizeProjectIds(scope)
        App->>Repo: requireReadyProject pour chaque UUID
        Repo-->>App: projets READY
        App->>Fed: search(projects,...)
        loop chaque projet
            Fed->>Search: search(projet, overfetch bornée)
        end
        Fed-->>App: fusion globale + diversification + top-K
        App-->>Adapter: résultats avec provenance
    end
```

La limite de 100 projets uniques est appliquée avant les lookups/readiness coûteux ; un 101e UUID unique ne doit donc pas être masqué par un `PROJECT_NOT_FOUND` ultérieur.

## 6.4 JDT LS — timeout ou framing invalide

```mermaid
sequenceDiagram
    participant Index as ProjectIndexingService
    participant Runner as ExternalTaskRunner
    participant JDT as JDT Language Server
    participant Frame as JdtJsonRpcFrameReader
    participant SQLite as SQLite

    Index->>Runner: run(provider jdtls)
    Runner->>JDT: worker daemon (capacité globale <= 8)
    JDT-->>Frame: JSON-RPC / LSP STDIO
    alt frame valide et dans les bornes
        Frame-->>Index: message JSON
    else timeout / Content-Length invalide / header tronqué / backlog saturé
        Runner-->>Index: IOException
        Index->>SQLite: project = FAILED
        Index-->>Index: propager l'échec
    end
```

Contrairement à une ancienne version de cette documentation, un timeout du provider explicitement demandé n'est **pas** transformé en succès dégradé `READY`. `ProjectIndexingService` marque l'indexation `FAILED` et propage l'erreur. La prochaine indexation d'un état non-READY force le chemin de reconstruction approprié.

Le framing JDT applique avant allocation :

```text
message       <= 16 MiB
headers       <= 64 KiB
header line   <= 8 KiB
pending queue <= 256 messages
```

## 6.5 Recherche Lucene à forte cardinalité

```mermaid
sequenceDiagram
    participant Search as SearchService
    participant Lucene as LuceneSearchIndex
    participant Analyzer as StandardAnalyzer
    participant Parser as MultiFieldQueryParser

    Search->>Lucene: search(query)
    Lucene->>Analyzer: analyser les termes
    Analyzer-->>Lucene: termes uniques
    Lucene->>Lucene: conserver au plus 128 termes
    Lucene->>Parser: expansion sur 5 champs
    Parser-->>Lucene: Query bornée
```

Cette borne évite de dépasser le budget par défaut de clauses Lucene lors de l'expansion multi-champs.

## 6.6 Embedding sémantique et secrets

```mermaid
sequenceDiagram
    participant Index as SemanticIndexingService
    participant Redactor as SensitiveContentRedactor
    participant Policy as OllamaEndpointResolver
    participant Ollama as Ollama

    Index->>Redactor: redact(document.content)
    Redactor-->>Index: contenu expurgé
    Index->>Policy: valider endpoint Ollama
    alt HTTP distant sans opt-in ou URI avec credentials
        Policy-->>Index: IllegalArgumentException
    else endpoint autorisé
        Index->>Ollama: /api/embed avec contenu redigé
        Ollama-->>Index: embedding borné
    end
```

Un endpoint Ollama distant utilise HTTPS par défaut. HTTP distant exige `NEXUS_ALLOW_INSECURE_REMOTE_OLLAMA=true`.

## 6.7 REST : séparation application / management

```mermaid
sequenceDiagram
    actor Client as Client métier
    participant AppPort as 127.0.0.1:8080
    participant Mgmt as 127.0.0.1:9000
    participant API as NexusApiApplicationService

    Client->>AppPort: GET /api/v1/projects
    AppPort->>API: requête métier
    API-->>Client: réponse
    Client->>AppPort: GET /q/health/ready
    AppPort-->>Client: 404
    Note over Mgmt: /q/health, /q/health/ready et /q/metrics sont servis ici uniquement
```

Le management listener reste loopback-only et ne doit pas être publié par le reverse proxy métier.

## 6.8 Démarrage du serveur MCP

```mermaid
sequenceDiagram
    actor Agent as Agent IA
    participant OS as OS local
    participant MCP as NexusMcpServer
    participant App as NexusApplication
    participant SQLite as SQLite

    Agent->>OS: démarrer NexusMcpServer
    OS->>MCP: main(args)
    MCP->>App: NexusApplication.create(NexusPaths.fromEnvironment())
    App->>SQLite: ensurePrivateStorage + migrations
    App-->>MCP: application prête
    MCP->>MCP: enregistrer tools MCP
    MCP-->>Agent: server_info + capabilities via STDIO
```

`stdout` reste réservé au framing MCP JSON-RPC ; les diagnostics utilisent `stderr`.
