# Adaptateur MCP Java

L'adaptateur MCP expose NEXUS aux assistants compatibles Model Context Protocol sans introduire le protocole dans le cœur.

## Stack

```text
module      adapters/mcp-java
Java        21
MCP SDK     2.0.0
transport   STDIO
version     NEXUS 0.2.0
```

Le MCP SDK et Jackson sont gouvernés par le parent Maven Phase 6. Le conflit de versions Jackson précédemment traité manuellement est désormais couvert par la gestion centrale des dépendances.

## Architecture

```text
Client MCP
   ↓ JSON-RPC / STDIO
NexusMcpServer
   ↓
NexusMcpTools
   ↓
NexusApplication
   ├─ SearchService
   ├─ FederatedSearchService
   ├─ DefaultContextBuilder
   └─ FederatedContextService
```

CLI, REST et MCP partagent donc les mêmes gates READY, providers, ranking et opt-ins.

## Transport STDIO

`stdout` reste réservé au transport JSON-RPC. Aucun log applicatif ne doit y être écrit. Les diagnostics utilisent `stderr` ou le mécanisme compatible du SDK.

## Tools mono-projet

### `list_projects`

Liste les projets enregistrés et leur état.

### `search_code`

Arguments : `project`, `query`, `limit` optionnel, `explain` optionnel.

Le projet doit être READY.

### `find_symbol`

Arguments : `project`, `query`, `limit` optionnel.

Phase 6 utilise une recherche repository/SQLite bornée au lieu d'un scan applicatif de tous les symboles.

### `find_usages`

Arguments : `project`, `symbol`, `limit` optionnel.

Les relations source/cible sont filtrées côté repository avec une limite stricte.

### `build_context`

Arguments : `project`, `query`, `tokenBudget`, `requestedSources`, `constraints`.

### `explain_context`

Même contrat que `build_context`, avec explicabilité forcée.

## Tools fédérés — Phase 6

### `search_across_projects`

Arguments :

```text
projects  tableau non vide d'UUID ou noms uniques
query     requête
limit     optionnel, 10 par défaut
explain   optionnel
```

Retourne le top-K global après sur-récupération locale et diversification `(projectId,path)`. Chaque hit conserve son projet d'origine.

### `build_context_across_projects`

Arguments :

```text
projects
query
tokenBudget      2000 par défaut
requestedSources optionnel
constraints      optionnel
```

Construit un `FederatedContextBundle` avec budget global, provenance, fairness round-robin et déduplication inter-projet.

### `explain_context_across_projects`

Même contrat avec explicabilité forcée.

Les instructions, Skills et données Git restent calculés dans leur projet d'origine avant fusion.

## Format des réponses

Chaque tool retourne un contenu texte MCP contenant un JSON sérialisé. Les erreurs sont retournées avec `isError=true` et :

```json
{
  "error": "nexus_tool_error",
  "message": "..."
}
```

## Sémantique

MCP utilise `SemanticSearchConfiguration.fromEnvironment()` via `NexusApplication`. Aucune capacité sémantique spécifique au protocole n'existe.

Activation explicite :

```text
NEXUS_SEMANTIC_PROVIDER=ollama
```

Sans cette variable, aucun provider d'embeddings n'est créé.

## Build

Le module est dans le reactor :

```powershell
.\mvnw.cmd clean install
```

Runner :

```text
adapters/mcp-java/target/nexus-mcp-java-0.2.0-runner.jar
```

Lancement manuel :

```powershell
java -jar .\adapters\mcp-java\target\nexus-mcp-java-0.2.0-runner.jar
```

## Parité

La parité repose maintenant sur une seule façade :

```text
CLI  ─┐
REST ─┼──> NexusApplication ──> services NEXUS
MCP  ─┘
```

Il n'existe plus de second composition root CLI à réconcilier.

## Qualification

Le runner historique I12 reste utile pour le transport STDIO. Le gate intégral Phase 6 est :

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\validate-phase-6.ps1
```

Le reactor construit/teste MCP avec le même exact-head que le core et REST.

## Hors périmètre

Un transport MCP distant n'est pas ajouté automatiquement. STDIO reste le chemin local minimal ; Streamable HTTP ne sera étudié qu'en présence d'un besoin concret et d'un modèle d'exposition/sécurité explicite.
