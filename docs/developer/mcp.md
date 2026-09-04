# Adaptateur MCP Java

L'adaptateur MCP expose NEXUS aux assistants compatibles Model Context Protocol sans introduire le protocole dans le cœur.

## Stack

```text
module      adapters/mcp-java
Java        21
MCP SDK     2.0.1
transport   STDIO
version     NEXUS 0.2.0
```

Le BOM MCP et Jackson sont gouvernés par le parent Maven. Le SDK MCP 2.0.1 apporte notamment en amont des lectures HTTP/STDIO bornées ; NEXUS supporte ici le transport local STDIO.

## Architecture

```text
Client MCP
  ↓ JSON-RPC / STDIO
NexusMcpServer
  ↓
NexusMcpTools
  ↓
NexusApplication
```

`stdout` reste réservé au framing JSON-RPC ; les diagnostics utilisent `stderr`.

## Tools

Mono-projet :

```text
list_projects
search_code
find_symbol
find_usages
build_context
explain_context
```

Fédérés :

```text
search_across_projects
build_context_across_projects
explain_context_across_projects
```

Les trois surfaces CLI/REST/MCP délèguent aux mêmes politiques applicatives.

## Portée fédérée

`projects` est une liste non vide de sélecteurs. Le contrat commun impose au maximum **100 projets uniques** (UUID canoniques).

L'ordre de validation est volontaire :

1. normaliser/valider les sélecteurs UUID explicites ;
2. appliquer la limite de cardinalité canonique ;
3. seulement ensuite résoudre les projets et vérifier `READY`.

Ainsi, un scope de 101 UUID uniques inexistants produit l'erreur de portée maximale avant une erreur de projet absent. Les doublons ne consomment qu'une place canonique et l'ordre d'insertion stable est préservé.

## Contextes

`build_context` utilise `DefaultContextBuilder`, y compris le budget partagé de découverte native. Les tools fédérés utilisent `FederatedContextService` avec provenance, budget global, fairness, déduplication et travail préparatoire borné.

Instructions, skills et Git restent projet-locaux avant fusion.

## Erreurs

Les erreurs tools sont structurées avec `isError=true` et un payload JSON stable ; les erreurs de portée/readiness proviennent de la façade commune au lieu d'une politique MCP parallèle.

## Build et qualification

```powershell
.\mvnw.cmd clean install
```

Runner :

```text
adapters/mcp-java/target/nexus-mcp-java-0.2.0-runner.jar
```

Le test d'intégration STDIO exerce le vrai processus enfant ; JaCoCo récupère explicitement sa couverture. NEXUS CI et Docker Distribution qualifient également le runner MCP sur l'exact head.

## Hors périmètre

Aucun transport MCP distant n'est activé par défaut. Toute exposition réseau future devra avoir un modèle de sécurité explicite.
