# Section 6 — Vue d'exécution

Les noms des participants correspondent strictement à la Section 5 (vue des blocs).

---

## 6.1 Scénario nominal — Indexation d'un projet

**Stimulus** : l'utilisateur enregistre et indexe un projet Java local via la CLI.

```mermaid
sequenceDiagram
    actor User as «Person» Développeur
    participant CLI as «adapter» NexusCli
    participant App as «Component» NexusApplication
    participant LockMgr as «Component» ProjectIndexLockManager
    participant IndexSvc as «Component» ProjectIndexingService
    participant Guard as «Component» ProjectPathGuard
    participant Scanner as «Component» ProjectScanner
    participant Analyzer as «Component» LanguageAnalyzer
    participant SQLiteDB as «database» SQLite
    participant LuceneDB as «database» Lucene

    User->>CLI: nexus project add /repo my-project
    CLI->>App: registerProject(path, "my-project")
    App->>Guard: validateRoot(path)
    Guard-->>App: chemin canonicalisé OK
    App->>SQLiteDB: INSERT projects (UUID, name, rootPath, NOT_INDEXED)
    App-->>CLI: ProjectDescriptor

    User->>CLI: nexus index my-project
    CLI->>App: index(projectId)
    App->>LockMgr: acquireJvmLock(projectId)
    LockMgr->>LockMgr: acquireFileLock(NEXUS_HOME/locks/projectId)
    LockMgr-->>App: verrou acquis
    App->>SQLiteDB: UPDATE projects SET index_status = INDEXING
    App->>IndexSvc: index(projectId)
    IndexSvc->>Scanner: scan(rootPath)
    Scanner->>Guard: chaque chemin vérifié (NOFOLLOW_LINKS)
    Scanner-->>IndexSvc: ScannedFile[] (SHA-256, catégorie)
    IndexSvc->>SQLiteDB: findFiles(projectId) — SHA-256 connus
    loop fichier nouveau ou modifié
        IndexSvc->>Analyzer: analyze(projectRoot, file)
        Analyzer-->>IndexSvc: AnalysisResult (symboles, relations)
    end
    IndexSvc->>SQLiteDB: applyChanges(updates, removedPaths) — transaction atomique
    IndexSvc->>LuceneDB: applyChanges ou rebuild
    App->>SQLiteDB: UPDATE projects SET index_status = READY
    App->>LockMgr: releaseLock(projectId)
    App-->>CLI: IndexingReport
    CLI-->>User: rapport (fichiers indexés, symboles, durée ms)
```

---

## 6.2 Scénario nominal — Construction d'un contexte mono-projet

**Stimulus** : un assistant IA appelle l'outil MCP `build_context` pour une tâche donnée.

```mermaid
sequenceDiagram
    actor Agent as «Person» Agent IA
    participant MCP as «adapter» Adaptateur MCP
    participant App as «Component» NexusApplication
    participant Search as «Component» SearchService
    participant Ranker as «Component» DeterministicContextRanker
    participant CB as «Component» DefaultContextBuilder
    participant Skills as «Component» SkillDiscoveryService
    participant Instr as «Component» InstructionProviders
    participant Git as «Component» LocalGitContextSourceProvider
    participant Budget as «Component» BudgetedContextSelector
    participant SQLiteDB as «database» SQLite
    participant LuceneDB as «database» Lucene

    Agent->>MCP: tool call build_context(project, query, tokenBudget)
    MCP->>App: context(projectId, query, budget, sources, constraints, explain)
    App->>SQLiteDB: verifier indexStatus == READY
    App->>CB: build(ContextRequest)
    CB->>Search: search(projectId, query, overFetch)
    Search->>LuceneDB: LuceneFileSearchStrategy(query)
    Search->>SQLiteDB: SymbolSearchStrategy(query) — pool préfiltré borné
    Search->>Ranker: rank(candidates + graphEnricher + gitRecencyEnricher)
    Ranker-->>Search: RankedCandidate[] top-K
    Search-->>CB: candidats classés
    CB->>Instr: discover(projectRoot, candidates)
    Instr-->>CB: instructions applicables (scope, applyTo)
    CB->>Skills: discover(projectRoot, query)
    Skills-->>CB: SkillDiscoveryResult (frontmatter seulement)
    CB->>Skills: activate(matchedSkills)
    Skills-->>CB: SKILL.md sélectionnés (contenu complet)
    CB->>Git: getContext(projectId)
    Git-->>CB: commits récents, fichiers modifiés HEAD
    CB->>Budget: select(allFragments, tokenBudget)
    Budget-->>CB: ContextBundle (items, excluded, metadata)
    CB-->>App: ContextBundle
    App-->>MCP: ContextOperation
    MCP-->>Agent: JSON (items, score, raisons, metadata)
```

---

## 6.3 Scénario nominal — Recherche fédérée multi-projets

**Stimulus** : un développeur recherche une fonctionnalité sur plusieurs projets simultanément.

```mermaid
sequenceDiagram
    actor Dev as «Person» Développeur
    participant CLI as «adapter» NexusCli
    participant App as «Component» NexusApplication
    participant FedSearch as «Component» FederatedSearchService
    participant Search as «Component» SearchService
    participant SQLiteDB as «database» SQLite

    Dev->>CLI: nexus search-federated --projects p1,p2,p3 "OrderService payment"
    CLI->>App: searchAcrossProjects([id1,id2,id3], query, topK)
    App->>SQLiteDB: vérifier que chaque projet est READY
    App->>FedSearch: search([id1,id2,id3], query, topK)

    par pour chaque projet
        FedSearch->>Search: search(id1, query, overFetch bornée)
        FedSearch->>Search: search(id2, query, overFetch bornée)
        FedSearch->>Search: search(id3, query, overFetch bornée)
    end

    FedSearch->>FedSearch: fusion globale, diversification (projectId, path)
    FedSearch->>FedSearch: top-K global
    FedSearch-->>App: FederatedSearchHit[] (projet + RankedCandidate)
    App-->>CLI: FederatedSearchOperation
    CLI-->>Dev: résultats classés avec provenance projet
```

---

## 6.4 Scénario d'erreur — Indexation en timeout d'un provider JDT

**Stimulus** : le provider JDT Language Server ne répond pas dans le délai configuré.

```mermaid
sequenceDiagram
    participant IndexSvc as «Component» ProjectIndexingService
    participant Runner as «Component» ExternalTaskRunner
    participant JDT as «Software System» JDT Language Server
    participant SQLiteDB as «database» SQLite

    IndexSvc->>Runner: submit(jdtTask, timeout=180s)
    Runner->>JDT: lancement du processus JDT LS
    Runner->>Runner: wall-clock timeout démarre
    Note over Runner,JDT: JDT ne répond pas
    Runner-->>IndexSvc: TimeoutException (annulation, interruption)
    IndexSvc->>IndexSvc: loguer le timeout, continuer sans résultats JDT
    IndexSvc->>SQLiteDB: UPDATE projects SET index_status = READY
    Note over IndexSvc: indexation réussie sans intelligence profonde
```

---

## 6.5 Scénario d'exploitation — Démarrage du serveur MCP

**Stimulus** : un assistant IA lance le serveur MCP NEXUS.

```mermaid
sequenceDiagram
    actor Agent as «Person» Agent IA (Copilot / Claude)
    participant OS as «node» OS local
    participant MCP as «adapter» NexusMcpServer
    participant App as «Component» NexusApplication
    participant SQLiteDB as «database» SQLite
    participant LuceneDB as «database» Lucene

    Agent->>OS: démarrer le processus NexusMcpServer
    OS->>MCP: main(args)
    MCP->>App: NexusApplication.create(NexusPaths.fromEnvironment())
    App->>SQLiteDB: ouverture de la base, SchemaMigrator (migrations forward-only)
    App->>LuceneDB: ouverture des index Lucene par projet READY
    App-->>MCP: application prête
    MCP->>MCP: enregistrer les tool specifications
    MCP->>OS: StdioServerTransportProvider prêt
    MCP-->>Agent: MCP server_info + capabilities
    Agent->>MCP: tool call list_projects
    MCP->>App: listProjects()
    App->>SQLiteDB: SELECT projects
    App-->>MCP: List~ProjectDescriptor~
    MCP-->>Agent: JSON projects
```
