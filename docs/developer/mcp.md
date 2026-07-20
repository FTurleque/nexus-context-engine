# Adaptateur MCP Java

## 1. Objectif

L'adaptateur MCP expose les capacités NEXUS à des assistants et agents compatibles avec le Model Context Protocol sans introduire le protocole dans le cœur.

Le module est isolé dans :

```text
adapters/mcp-java
```

Il dépend du JAR `nexus-context-engine`, tandis que le cœur ne dépend jamais du SDK MCP.

## 2. Stack

- Java 21 ;
- SDK Java MCP officiel `2.0.0` ;
- transport STDIO ;
- mapper Jackson 2 fourni par `mcp-json-jackson2` ;
- packaging autonome via Maven Shade.

La version du SDK est figée dans `adapters/mcp-java/pom.xml` pour rendre les validations reproductibles.

## 3. Architecture

```text
Client MCP
   │
   │ JSON-RPC / STDIO
   ▼
SDK Java MCP officiel
   │
   ▼
NexusMcpServer
   │
   ▼
NexusMcpTools
   │
   ▼
NexusApplication
   │
   ├── ProjectRegistry
   ├── ProjectIndexingService
   ├── SearchService
   └── ContextBuilder
```

`NexusApplication` est indépendante de MCP et de Quarkus. L'adaptateur REST délègue également à cette façade, ce qui réduit le risque de divergence entre clients.

## 4. Transport STDIO

Le premier transport MCP est STDIO.

Le serveur est lancé par :

```text
com.nexus.mcp.NexusMcpServer
```

Règle impérative : `stdout` est réservé au transport JSON-RPC. Aucun log applicatif ne doit y être écrit.

Les diagnostics éventuels doivent utiliser `stderr` ou un mécanisme explicitement compatible avec le transport.

## 5. Tools disponibles

### `list_projects`

Liste les projets enregistrés dans le `NEXUS_HOME` courant.

Aucun argument requis.

### `search_code`

Arguments :

- `project` : UUID ou nom unique du projet ;
- `query` : requête ;
- `limit` : optionnel, 10 par défaut ;
- `explain` : optionnel.

Le tool appelle directement `NexusApplication.search` et conserve le score, ses composantes et les raisons produites par NEXUS.

### `find_symbol`

Arguments :

- `project` ;
- `query` ;
- `limit` optionnel.

Le tool recherche dans les symboles persistés par nom ou nom qualifié.

### `find_usages`

Arguments :

- `project` ;
- `symbol` ;
- `limit` optionnel.

Le tool retourne les relations structurelles connues dont la source ou la cible correspond au symbole. Il peut donc refléter les données JavaParser, SCIP ou celles d'un provider profond optionnel. Il ne prétend pas reconstruire des usages absents de l'index.

### `build_context`

Arguments :

- `project` ;
- `query` ;
- `tokenBudget` optionnel, 2 000 par défaut ;
- `requestedSources` optionnel ;
- `constraints` optionnel.

Le tool appelle `NexusApplication.context` et respecte les mêmes budgets, stratégies de ranking, instructions, skills et contexte Git que les autres adaptateurs.

### `explain_context`

Même contrat que `build_context`, avec le mode explicable forcé.

## 6. Format des réponses

Le premier contrat retourne un contenu texte MCP contenant un document JSON sérialisé.

Ce choix rend la réponse :

- inspectable ;
- stable pour les tests ;
- facile à comparer avec les sorties JSON de NEXUS.

Les erreurs de tool sont retournées avec `isError = true` et un objet JSON contenant :

```json
{
  "error": "nexus_tool_error",
  "message": "..."
}
```

## 7. Parité avec REST et le cœur

L'Itération 12 vérifie la parité de deux capacités centrales :

1. `search_code` doit retourner le même premier chemin et le même score que `NexusApplication.search` ;
2. `build_context` doit retourner le même budget, le même nombre estimé de tokens et le même premier item que `NexusApplication.context`.

L'adaptateur REST délègue lui aussi à `NexusApplication`.

La parité est donc garantie par :

```text
REST ─┐
      ├──> NexusApplication ──> services NEXUS
MCP ──┘
```

## 8. Build

Le cœur doit d'abord être installé dans Maven Local :

```text
mvn clean install
```

Puis l'adaptateur MCP :

```text
mvn -f adapters/mcp-java/pom.xml clean verify
```

Le runner attendu est :

```text
adapters/mcp-java/target/nexus-mcp-java-0.1.0-SNAPSHOT-runner.jar
```

## 9. Validation

Le script d'itération est :

```text
scripts/validate-iteration-12.ps1
```

Il exécute :

1. `mvn clean install` sur le cœur ;
2. `scripts/self-smoke.ps1` ;
3. le build et les tests de l'adaptateur REST après extraction de la façade commune ;
4. le build et le test d'intégration MCP avec un vrai client STDIO ;
5. la vérification du runner autonome MCP.

Le mode `-AdapterOnly` permet de rejouer uniquement REST + MCP lorsque le cœur et le self-smoke ont déjà été validés dans la même itération.

## 10. Lancement manuel

Après packaging :

```text
java -jar adapters/mcp-java/target/nexus-mcp-java-0.1.0-SNAPSHOT-runner.jar
```

Le processus attend alors les messages MCP sur STDIN et écrit uniquement les réponses du transport sur STDOUT.

Le `NEXUS_HOME` suit les mêmes règles que la CLI et REST :

1. propriété JVM `nexus.home` ;
2. variable `NEXUS_HOME` ;
3. défaut `~/.nexus`.

## 11. Extension future

Les tools candidats de la roadmap (`get_related_tests`, `get_architecture_context`, `get_recent_changes`, etc.) ne sont pas ajoutés automatiquement. Ils doivent correspondre à un besoin client réel et réutiliser les services NEXUS existants.

Un transport distant pourra être étudié ultérieurement si un usage concret justifie Streamable HTTP. STDIO reste le chemin local minimal de l'Itération 12.
