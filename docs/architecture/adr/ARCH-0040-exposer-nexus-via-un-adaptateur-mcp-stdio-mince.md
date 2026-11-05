---
id: ARCH-0040
title: "Exposer NEXUS via un adaptateur MCP STDIO mince"
domain: architecture
rules: true
files: ["**/src/*/java/**/*.java", "core/pom.xml", "adapters/rest-quarkus/pom.xml", "adapters/assistant-clients/pom.xml"]
---

# Exposer NEXUS via un adaptateur MCP STDIO mince

- **Statut** : Accepted
- **Date** : 2026-07-20

## Context

ADR-0016 impose l'utilisation du SDK Java MCP officiel et interdit de réimplémenter le protocole dans NEXUS. L'Itération 12 doit maintenant concrétiser cette décision tout en préservant deux invariants : le cœur reste indépendant de MCP et les résultats exposés par MCP restent identiques à ceux produits par les autres adaptateurs.

Le risque principal est de créer un troisième point de composition du moteur, distinct de la CLI et de REST, puis de laisser les comportements diverger progressivement.

## Facteurs de décision

- respecter ADR-0016 ;
- conserver les types MCP hors du cœur ;
- ne pas dupliquer le ranking, la recherche ou la construction du contexte ;
- permettre l'utilisation locale par des assistants MCP ;
- préserver `stdout` pour le transport JSON-RPC ;
- tester la parité avec les services NEXUS réels ;
- rester utilisable sans serveur MCP.

## Options envisagées

- réutiliser l'API REST depuis un proxy MCP ;
- recomposer directement tous les services NEXUS dans l'adaptateur MCP ;
- introduire une façade applicative indépendante des protocoles, puis brancher REST et MCP dessus ;
- intégrer MCP directement dans le module cœur.

## Decision

**Créer un adaptateur MCP Java isolé dans `adapters/mcp-java`, basé sur le SDK Java MCP officiel, avec STDIO comme premier transport, et centraliser la composition du moteur dans une façade `NexusApplication` indépendante des protocoles.**

Les handlers MCP :

1. valident les arguments du tool ;
2. résolvent le projet NEXUS ;
3. appellent `NexusApplication` ;
4. traduisent le résultat vers une réponse MCP ;
5. ne calculent aucun score et ne reconstruisent aucun `ContextBundle` eux-mêmes.

L'adaptateur REST délègue également à `NexusApplication`. La parité REST/MCP repose ainsi sur un même service applicatif et non sur deux implémentations parallèles.

Le premier transport est STDIO, adapté aux clients MCP locaux. `stdout` est réservé au flux JSON-RPC du SDK ; les diagnostics applicatifs ne doivent jamais y être écrits.

La première surface de tools est volontairement réduite aux capacités déjà stables :

```text
list_projects
search_code
find_symbol
find_usages
build_context
explain_context
```

Les autres tools envisagés par la roadmap pourront être ajoutés lorsqu'ils correspondent à un besoin réel, sans multiplier des variantes redondantes de `build_context`.

## Do's and Don'ts

### Do

- Isoler l'adaptateur MCP dans `adapters/mcp-java`, sur le SDK Java MCP officiel, avec STDIO comme premier transport.
- Centraliser la composition du moteur dans `NexusApplication`, à laquelle délèguent les adaptateurs MCP et REST.
- Limiter un handler MCP à valider les arguments, résoudre le projet, appeler `NexusApplication` et traduire le résultat en réponse MCP.
- Réserver `stdout` au flux JSON-RPC du SDK.

### Don't

- Ne pas calculer de score ni reconstruire de `ContextBundle` dans un handler MCP.
- Ne pas écrire de diagnostic applicatif sur `stdout`.
- Ne pas ajouter de variantes redondantes de `build_context`.

## Conséquences positives

- aucune dépendance MCP dans le cœur ;
- aucune réimplémentation du framing ou du transport ;
- parité structurelle entre REST et MCP via `NexusApplication` ;
- test possible avec un vrai client MCP STDIO ;
- packaging MCP autonome ;
- extension ultérieure vers d'autres transports sans modifier le moteur.

## Conséquences négatives et compromis acceptés

- ajout d'une façade applicative à maintenir ;
- dépendance de l'adaptateur au cycle de vie du SDK MCP ;
- STDIO ne couvre pas les scénarios de serveur distant ;
- les réponses des tools sont initialement sérialisées en JSON dans un contenu texte MCP pour conserver un contrat simple et inspectable.

## Risques et mesures de maîtrise

| Risque | Impact | Mesure de maîtrise |
|---|---|---|
| Corruption du transport STDIO par des logs | Élevé | Aucun log applicatif sur `stdout` |
| Divergence REST/MCP | Élevé | Façade `NexusApplication` commune |
| Logique métier dans les handlers | Élevé | Handlers limités à validation, appel et mapping |
| Multiplication de tools redondants | Moyen | Surface initiale réduite et extension guidée par les usages |
| MCP devient obligatoire | Élevé | Module adaptateur autonome, dépendance unidirectionnelle vers le cœur |

## Confirmation

La décision est respectée si :

- `mvn clean install` du cœur fonctionne sans dépendance MCP ;
- l'adaptateur MCP est construit séparément ;
- un client MCP officiel peut initialiser une session STDIO et lister les tools NEXUS ;
- `search_code` retourne le même premier résultat et le même score que `NexusApplication.search` pour une requête identique ;
- `build_context` retourne le même budget, le même nombre estimé de tokens et le même premier item que `NexusApplication.context` ;
- l'adaptateur REST continue de déléguer à cette même façade ;
- le serveur MCP peut être absent sans empêcher la CLI, l'API ou l'usage bibliothèque de fonctionner.

## Analyse détaillée des options

### Proxy MCP vers REST

Cette approche réduit le code Java MCP mais ajoute une dépendance opérationnelle à un serveur HTTP et un saut réseau inutile pour l'usage local. Elle n'est pas retenue comme chemin par défaut.

### Recomposition indépendante dans MCP

Techniquement simple à court terme, elle dupliquerait le câblage SQLite/Lucene/ranking/contexte déjà présent ailleurs et augmenterait le risque de divergence. Cette option est rejetée.

### Façade applicative commune

Cette option introduit une abstraction supplémentaire mais fournit un point de composition unique, indépendant des frameworks et des protocoles. Elle est retenue.

### MCP dans le cœur

Cette option violerait les ADR-0003, ADR-0016 et ADR-0017 en faisant fuiter un protocole client dans le moteur. Elle est rejetée.

## Impacts sur l'architecture

```text
CLI / REST / MCP
       │
       ▼
NexusApplication
       │
       ├── ProjectRegistry
       ├── ProjectIndexingService
       ├── SearchService
       └── ContextBuilder
              │
              ▼
          NEXUS Core
```

Le SDK MCP n'existe que sous `adapters/mcp-java`.

## Conditions de réexamen

Réexaminer si :

- le transport STDIO ne répond plus aux usages clients ciblés ;
- Streamable HTTP devient nécessaire pour une exposition distante ;
- le SDK MCP modifie substantiellement son modèle d'outils ou de transport ;
- la façade applicative devient trop large et nécessite des ports applicatifs plus fins.

## Compliance and Enforcement

- Règle Archgate `ARCH-0040-exposer-nexus-via-un-adaptateur-mcp-stdio-mince.rules.ts` / `mcp-sdk-only-in-mcp-adapter` : aucune référence à `io.modelcontextprotocol` dans les sources Java hors de `adapters/mcp-java` (commentaires et littéraux effacés), ni dans `core/pom.xml`, `adapters/rest-quarkus/pom.xml` et `adapters/assistant-clients/pom.xml`.
- Règle Archgate `ARCH-0040-exposer-nexus-via-un-adaptateur-mcp-stdio-mince.rules.ts` / `mcp-stdout-reserved-for-transport` : dans `adapters/mcp-java/src/main/java`, `System.out` (ou `System.setOut`, `FileDescriptor.out`) n'apparaît que comme argument de `new StdioServerTransportProvider(...)`.
- Règle Archgate `ARCH-0040-exposer-nexus-via-un-adaptateur-mcp-stdio-mince.rules.ts` / `mcp-handlers-delegate-to-nexus-application` : les sources de `adapters/mcp-java/src/main/java` ne nomment aucun service ni package technique du moteur et ne construisent ni `ContextBundle` ni `RankedCandidate`.
- Tests `NexusMcpServerIntegrationTest.exposesTheSameSearchAndContextResultsThroughARealMcpStdioClient` (parité avec `NexusApplication` par un vrai client STDIO), `NexusMcpServerLifecycleTest` et `NexusMcpToolSchemaContractTest` (`adapters/mcp-java/src/test/java/com/nexus/mcp`) ; tests lancés par `./mvnw -B clean install` (job « Linux reactor Maven build » de `.github/workflows/ci.yml`).
- Script `scripts/release/test-mcp-protocol.ps1`, lancé par `scripts/release/test-windows-installer.ps1` (workflow `.github/workflows/windows-installer.yml`).

## Décisions liées

- ADR-0003 — Conserver un cœur Java sans framework applicatif obligatoire.
- ADR-0016 — Utiliser le SDK Java officiel pour MCP.
- ADR-0017 — Découpler NEXUS des outils et orchestrateurs externes.
- ADR-0039 — Isoler l'adaptateur REST Quarkus du cœur NEXUS.
