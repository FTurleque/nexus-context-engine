# Adaptateur REST NEXUS

L'adaptateur Quarkus expose NEXUS sans introduire Quarkus dans le cœur. Il fait partie du reactor Phase 6 et délègue à `NexusApplication`, comme CLI et MCP.

## Module et build

```text
adapters/rest-quarkus/
Java 21
Quarkus 3.33.2.1
version NEXUS 0.2.0
```

Build reactor recommandé :

```powershell
.\mvnw.cmd clean install
```

Lancement après packaging :

```powershell
java -jar .\adapters\rest-quarkus\target\quarkus-app\quarkus-run.jar
```

Par défaut, l'API reste local-first sur `127.0.0.1:8080`.

## Architecture

```text
Client HTTP
    ↓
Resources REST
    ↓
DTO / ApiMapper
    ↓
NexusApiApplicationService
    ↓
NexusApplication
    ├─ ProjectIndexingService
    ├─ SearchService
    ├─ FederatedSearchService
    ├─ DefaultContextBuilder
    └─ FederatedContextService
```

Les DTO REST restent distincts des modèles du cœur.

## Projets

```http
GET  /api/v1/projects
POST /api/v1/projects
GET  /api/v1/projects/{projectId}
```

Création :

```json
{
  "rootPath": "N:/workspace-dev/my-project",
  "name": "my-project"
}
```

## Indexation

```http
POST /api/v1/projects/{projectId}/index
POST /api/v1/projects/{projectId}/index?rebuild=true
POST /api/v1/projects/{projectId}/index?deepJava=true
GET  /api/v1/projects/{projectId}/index
```

La réponse d'indexation Phase 6 expose aussi :

```text
skippedFiles
diagnostics
providerDurationsMs via le rapport applicatif/métriques
```

`deepJava=true` nécessite un provider JDT LS réellement configuré ; sinon l'appel échoue explicitement au lieu de prétendre avoir exécuté une analyse profonde.

## Recherche mono-projet

```http
POST /api/v1/projects/{projectId}/search
Content-Type: application/json
```

```json
{
  "query": "ProjectIndexingService",
  "limit": 10,
  "explain": true
}
```

Le projet doit être `READY`.

Endpoints d'explication historiques :

```http
POST /api/v1/projects/{projectId}/explain/search
POST /api/v1/projects/{projectId}/explain/context
```

## Recherche fédérée — Phase 6

```http
POST /api/v1/federated/search
Content-Type: application/json
```

```json
{
  "projectIds": [
    "11111111-1111-1111-1111-111111111111",
    "22222222-2222-2222-2222-222222222222"
  ],
  "query": "billing adapter",
  "limit": 20,
  "explain": true
}
```

Chaque projet doit être `READY`. Le top-K est global et diversifié par `(projectId,path)` après sur-récupération locale bornée.

## Contexte mono-projet

```http
POST /api/v1/projects/{projectId}/context
```

```json
{
  "query": "ProjectIndexingService architecture",
  "tokenBudget": 1200,
  "requestedSources": ["FILE", "SYMBOL", "DOCUMENTATION"],
  "constraints": {},
  "explain": true
}
```

Invariant : `estimatedTokens <= tokenBudget`.

## Contexte fédéré — Phase 6

```http
POST /api/v1/federated/context
Content-Type: application/json
```

```json
{
  "projectIds": [
    "11111111-1111-1111-1111-111111111111",
    "22222222-2222-2222-2222-222222222222"
  ],
  "query": "change the billing contract",
  "tokenBudget": 4000,
  "requestedSources": [],
  "constraints": {},
  "explain": true
}
```

Le bundle conserve la provenance projet, applique un budget global, un merge round-robin et une déduplication inter-projet. Instructions, skills et Git restent projet-locaux.

## Sémantique

L'adaptateur n'a aucune configuration sémantique spécifique. Il utilise le même composition root que la CLI/MCP :

```text
NEXUS_SEMANTIC_PROVIDER=ollama
NEXUS_SEMANTIC_RRF_WEIGHT
NEXUS_OLLAMA_BASE_URL
NEXUS_OLLAMA_EMBEDDING_MODEL
NEXUS_OLLAMA_EMBEDDING_DIMENSIONS
NEXUS_OLLAMA_TIMEOUT_SECONDS
```

Sans `NEXUS_SEMANTIC_PROVIDER`, aucun provider d'embeddings n'est créé.

## Readiness

```http
GET /q/health
GET /q/health/ready
```

Le check NEXUS expose :

```text
registeredProjects
semanticSearchEnabled
projects.ready
projects.indexing
projects.failed
projects.not_indexed
```

Un projet `FAILED` rend le check NEXUS non opérationnel ; un projet `INDEXING` est visible sans rendre automatiquement tout le service indisponible.

## Métriques

```http
GET /q/metrics
```

Métriques applicatives :

```text
nexus.api.operations
nexus.api.operation.duration
nexus.code_intelligence.duration
```

Opérations : `index`, `search`, `context`, `search_federated`, `context_federated`.

Les labels n'incluent ni requête, ni contenu source, ni chemin de projet. Le label provider utilise seulement l'identifiant contrôlé du provider/importer.

## Erreurs

Les erreurs de validation restent normalisées par les mappers REST. Les gates READY et les erreurs de providers remontent de la façade applicative ; elles ne sont pas contournées dans les resources.

## Qualification

Les runners historiques REST restent utiles pour leur périmètre. Le gate final Phase 6 est :

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\validate-phase-6.ps1
```

Il construit le reactor complet, donc l'adaptateur REST est qualifié dans le même exact-head que le core et MCP.
