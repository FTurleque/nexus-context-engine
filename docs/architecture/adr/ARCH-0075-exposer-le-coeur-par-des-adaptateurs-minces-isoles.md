---
id: ARCH-0075
title: "Exposer le cœur par des adaptateurs minces isolés : REST Quarkus, MCP STDIO sur le SDK Java officiel, clients assistants"
domain: architecture
rules: true
files: ["**/src/*/java/**/*.java", "core/src/main/java/**", "adapters/*/src/main/java/**", "adapters/rest-quarkus/src/main/java/**", "adapters/assistant-clients/**", "core/pom.xml", "adapters/rest-quarkus/pom.xml", "adapters/mcp-java/pom.xml", "adapters/assistant-clients/pom.xml"]
---

# Exposer le cœur par des adaptateurs minces isolés : REST Quarkus, MCP STDIO sur le SDK Java officiel, clients assistants

- **Statut** : Accepted
- **Date** : 2026-11-08
- **Origine** : consolidation du registre ADR (audit du 2026-11-08)
- **Remplace** : [ARCH-0039](archive/ARCH-0039-isoler-l-adaptateur-rest-quarkus-du-coeur-nexus.md), [ARCH-0040](archive/ARCH-0040-exposer-nexus-via-un-adaptateur-mcp-stdio-mince.md), [ARCH-0041](archive/ARCH-0041-reutiliser-le-serveur-mcp-pour-les-clients-assistants.md), [BE-0016](archive/BE-0016-utiliser-le-sdk-java-officiel-pour-mcp.md)
- **Remplacé par** : —

## Context

Le cœur NEXUS est une bibliothèque Java sans framework applicatif ([ARCH-0076](ARCH-0076-garder-un-coeur-java-sans-framework-acyclique-et-verifie.md)), utilisable par la CLI, en bibliothèque et hors ligne. Il doit aussi être exposé à d'autres applications par HTTP, avec santé et métriques, et à des assistants compatibles Model Context Protocol (MCP), dont plusieurs clients (Claude, Copilot, IDE) veulent une configuration prête à l'emploi.

Trois risques se cumulent : faire entrer un framework HTTP ou un SDK de protocole dans le classpath et les contrats du cœur ; créer un point de composition du moteur par surface, dont les résultats divergent ; réimplémenter un protocole (framing, transport, négociation, outils) qui possède déjà un SDK Java officiel.

La question est : **comment exposer le cœur en REST, en MCP et aux clients assistants sans qu'aucune de ces surfaces ne contamine le cœur, ne porte de logique métier ni ne diverge des autres ?**

## Options envisagées

- **Placement de l'adaptateur REST** : Quarkus dans le module racine (Quarkus devient une dépendance du cœur, couplage aux annotations CDI/REST) ; conversion immédiate de tout le dépôt en agrégateur multi-module (déplacement massif sans bénéfice direct) ; **sous-projet Maven `adapters/rest-quarkus` dépendant du JAR cœur** (cœur inchangé, Quarkus évolue seul, validation en deux temps).
- **Protocole MCP** : réimplémentation dans NEXUS (maintenance du protocole, incompatibilités) ; SDK communautaire non officiel (retard sur le protocole) ; **SDK Java MCP officiel et adaptateur mince** (moins de code maison, dépendance à suivre) ; REST seul et proxy MCP externe (composant de plus, saut réseau inutile en local, mapping des outils hors du projet).
- **Composition derrière REST et MCP** : proxy MCP vers l'API REST (serveur HTTP requis en local) ; recomposition des services dans chaque adaptateur (câblage dupliqué, divergence) ; **façade applicative commune indépendante des protocoles** (une abstraction de plus, un point de composition unique) ; MCP dans le cœur (fuite d'un protocole client dans le moteur).

## Decision

**Options retenues : sous-projet REST isolé, SDK Java MCP officiel dans un adaptateur mince, façade applicative commune `NexusApplication`, intégrations clientes qui réutilisent le serveur MCP.**

### Principe commun

- La composition du moteur est centralisée dans `NexusApplication`, façade indépendante des frameworks et des protocoles, à laquelle délèguent la CLI, REST et MCP. La parité entre surfaces repose sur ce même service applicatif, non sur des implémentations parallèles.
- Chaque adaptateur dépend du JAR cœur, jamais l'inverse ; le cœur ne contient aucune dépendance Quarkus ni MCP.
- Un adaptateur valide et traduit la requête, résout le projet, appelle `NexusApplication` puis traduit le résultat. Il ne contient aucune logique métier d'indexation, de recherche, de ranking, de budget ni de construction de contexte : il ne calcule aucun score et ne reconstruit aucun `ContextBundle`.
- La forme des réponses publiques est fixée par la projection du cœur ([ARCH-0085](ARCH-0085-stabiliser-le-contrat-de-sortie-publique-cli-rest-mcp.md)) ; aucun adaptateur ne la recompose.
- NEXUS reste utilisable sans serveur REST ni serveur MCP : la CLI et l'usage bibliothèque ne dépendent d'aucun adaptateur.

### Adaptateur REST Quarkus

- L'adaptateur REST vit dans `adapters/rest-quarkus/`, avec son propre `pom.xml`, et dépend du JAR cœur NEXUS. Quarkus n'est déclaré que dans `adapters/rest-quarkus/pom.xml` ; le `pom.xml` racine ne reçoit aucune dépendance Quarkus.
- L'adaptateur porte la composition du runtime HTTP, les DTO HTTP de requête et d'erreur, le mapping vers la façade, les ressources, la gestion des erreurs HTTP, la santé (SmallRye Health) et les métriques (Micrometer/Prometheus).
- Le contrat HTTP est versionné sous `/api/v1` : projets (création, lecture, liste), indexation (normale, reconstruction, analyse Java profonde optionnelle), statistiques d'index, recherche, construction de contexte, variantes d'explication explicites.
- Aucun modèle métier du cœur n'est un contrat HTTP public : les réponses sont les records publics de la projection ([ARCH-0085](ARCH-0085-stabiliser-le-contrat-de-sortie-publique-cli-rest-mcp.md)).

### Adaptateur MCP

- L'adaptateur MCP vit dans `adapters/mcp-java` et s'appuie sur le SDK Java MCP officiel ; aucune implémentation maison du framing ou du transport MCP n'est ajoutée.
- Le SDK MCP est une dépendance d'adaptateur. Il n'apparaît ni dans `ContextRequest`, `ContextBundle`, `SearchStrategy`, ni dans les modèles métier.
- Le premier transport est STDIO, adapté aux clients MCP locaux. `stdout` est réservé au flux JSON-RPC du SDK ; les diagnostics applicatifs ne sont jamais écrits sur `stdout`.
- La surface d'outils reste réduite aux capacités stables (`list_projects`, `search_code`, `find_symbol`, `find_usages`, `build_context`, `explain_context`). Un outil s'ajoute sur un besoin réel, sans variante redondante de `build_context`. La liste des outils candidats (`get_relevant_files`, `get_related_tests`, `get_architecture_context`, `get_module_context`, `get_project_instructions`, `get_recent_changes`…) est une orientation, pas une API figée.
- Les réponses des outils sont sérialisées en JSON dans un contenu texte MCP, contrat simple et inspectable.

### Intégrations clientes

- Les intégrations clientes réutilisent le serveur MCP STDIO NEXUS : une seule surface d'outils est maintenue.
- Le module `adapters/assistant-clients` reste autonome et génère uniquement des commandes ou fragments de configuration. Il ne modifie pas les préférences de l'utilisateur et ne crée aucun nouveau chemin métier vers le cœur. Le cœur et le serveur MCP restent indépendants de ce module.

### Hors décision

Aucun transport MCP distant (Streamable HTTP) n'est décidé. La structure du build Maven (réacteur parent) et la règle d'extraction d'un module relèvent d'[ARCH-0076](ARCH-0076-garder-un-coeur-java-sans-framework-acyclique-et-verifie.md).

## Do's and Don'ts

### Do

- Faire déléguer la CLI, REST et MCP à `NexusApplication`, point de composition unique du moteur.
- Déclarer Quarkus dans le seul `adapters/rest-quarkus/pom.xml` et le SDK MCP dans le seul `adapters/mcp-java/pom.xml`.
- Limiter un handler MCP ou une ressource REST à valider les arguments, résoudre le projet, appeler `NexusApplication` et traduire le résultat.
- Versionner le contrat HTTP sous `/api/v1` et définir les DTO HTTP dans l'adaptateur.
- Limiter `adapters/assistant-clients` à générer des commandes ou fragments de configuration, sans modifier les préférences de l'utilisateur.

### Don't

- Ne pas ajouter de dépendance Quarkus ou MCP au `pom.xml` racine ni à `core/pom.xml`.
- Ne pas calculer de score ni reconstruire de `ContextBundle` dans un adaptateur.
- Ne pas faire apparaître un type du SDK MCP dans le cœur ni dans les autres adaptateurs.
- Ne pas écrire sur `stdout` dans l'adaptateur MCP hors du transport STDIO.
- Ne pas ajouter de variante redondante de `build_context` ni traiter la liste des outils candidats comme une API figée.
- Ne pas faire dépendre `adapters/assistant-clients` du cœur ou du serveur MCP, ni créer par lui un chemin métier vers le cœur.

## Consequences

### Positives

- Quarkus et MCP restent optionnels ; le cœur demeure réutilisable comme bibliothèque et la CLI reste autonome.
- Aucune réimplémentation du protocole MCP ; la compatibilité suit le SDK.
- Parité structurelle CLI/REST/MCP par une façade unique ; le contrat HTTP évolue avec sa propre version.
- Le serveur MCP se teste avec un vrai client STDIO ; la génération des profils clients est déterministe et testable.
- D'autres transports peuvent s'ajouter sans modifier le moteur.

### Négatives

- Une façade applicative à maintenir ; les concepts MCP et HTTP sont traduits à la frontière.
- L'adaptateur MCP dépend du cycle de vie du SDK : une version majeure peut imposer une migration.
- STDIO ne couvre pas les scénarios de serveur distant ; certaines fonctionnalités du protocole restent inutilisées.
- La validation complète construit le cœur puis chaque adaptateur.

### Risques

| Risque | Impact | Mesure de maîtrise |
|---|---|---|
| Fuite de types Quarkus ou MCP dans le cœur | Élevé | Règles Archgate compagnon ; règle `core-sans-framework` de `CoreArchitectureTest` |
| Logique métier dans les handlers ou ressources | Élevé | Règles Archgate compagnon de délégation à `NexusApplication` |
| Corruption du transport STDIO par des logs | Élevé | Aucun log applicatif sur `stdout` ; règle `mcp-stdout-reserved-for-transport` |
| Divergence REST/MCP | Élevé | Façade `NexusApplication` commune et tests de parité ([ARCH-0085](ARCH-0085-stabiliser-le-contrat-de-sortie-publique-cli-rest-mcp.md)) |
| Rupture de compatibilité du SDK | Moyen | Version gérée par le `mcp-bom` du pom parent ; `scripts/release/test-mcp-protocol.ps1` sur l'artefact empaqueté |
| Multiplication d'outils redondants | Moyen | Surface réduite, extension guidée par les usages |

## Compliance and Enforcement

- Règle Archgate compagnon `ARCH-0075-exposer-le-coeur-par-des-adaptateurs-minces-isoles.rules.ts` :
  - `quarkus-dependencies-only-in-rest-adapter` : aucun `groupId` Quarkus dans `core/pom.xml`, `adapters/mcp-java/pom.xml`, `adapters/assistant-clients/pom.xml` (pom racine non couvert, voir « Écarts constatés ») ;
  - `rest-adapter-delegates-business-logic`, `mcp-handlers-delegate-to-nexus-application` : aucun service ni package technique du moteur nommé, aucun `ContextBundle` ni `RankedCandidate` construit dans les sources REST et MCP ;
  - `mcp-sdk-only-in-mcp-adapter`, `mcp-sdk-confined-to-mcp-adapter` : `io.modelcontextprotocol` absent des sources et des pom hors de `adapters/mcp-java` ;
  - `mcp-adapter-uses-official-sdk` : dépendance `io.modelcontextprotocol.sdk` et import de `io.modelcontextprotocol.server` ;
  - `mcp-stdout-reserved-for-transport` : `System.out` seulement en argument de `new StdioServerTransportProvider(...)` ;
  - `assistant-clients-independent-of-engine`, `assistant-clients-write-no-file` : aucune dépendance entre `adapters/assistant-clients` et le cœur ou le serveur MCP, imports limités à `com.nexus.integrations`, aucune écriture de fichier.
- Tests REST `NexusResourceTest` et `ManagementEndpointIsolationTest` (`adapters/rest-quarkus/src/test/java/com/nexus/api`).
- Tests MCP `NexusMcpServerIntegrationTest` (vrai client STDIO, parité avec `NexusApplication` : `searchCodeReturnsTheDirectSearchResultWithAPublicPath`, `buildContextRedactsSecretsAndMatchesTheDirectContextBudget`), `NexusMcpServerLifecycleTest`, `NexusMcpToolSchemaContractTest`, `NexusMcpNegativeContractTest` (`adapters/mcp-java/src/test/java/com/nexus/mcp`).
- Tests `AssistantIntegrationGeneratorTest`, `AssistantIntegrationCliTest`, `AssistantIntegrationEscapingTest` (`adapters/assistant-clients/src/test/java/com/nexus/integrations`).
- Tous lancés par `./mvnw -B clean install` (job « Linux reactor Maven build » de `.github/workflows/ci.yml`). `scripts/release/test-mcp-protocol.ps1`, appelé par `scripts/release/test-docker-runtime.ps1` et `scripts/release/test-windows-installer.ps1`, dialogue en MCP réel avec l'artefact empaqueté.
- Revue : les handlers ne calculent aucun score métier ; un nouvel outil MCP répond à un besoin réel.

## Écarts constatés

Constat du 2026-10-29 (la décision ci-dessus est inchangée) :

- Le `pom.xml` racine importe la BOM Quarkus dans son `dependencyManagement` (`pom.xml:89-95`) et gère `quarkus-maven-plugin` dans son `pluginManagement` (`pom.xml:172-176`).
- L'adaptateur n'est plus un sous-projet Maven autonome (option C) : c'est un module du réacteur racine (`pom.xml:25`) qui hérite de `nexus-context-engine-parent` (`adapters/rest-quarkus/pom.xml:7-12`), forme proche de l'option B, et dépend de `nexus-context-engine:${project.version}`, soit `0.2.0` (`adapters/rest-quarkus/pom.xml:19-23`, `pom.xml:9`), et non de `0.1.0-SNAPSHOT`.

Relevé du 2026-11-08 : constat toujours vrai ; au HEAD, la BOM Quarkus est en `pom.xml:105-111`, `quarkus-maven-plugin` en `pom.xml:221-225`, et `${project.version}` vaut `0.2.0-rc.1` (`pom.xml:9`).

## Conditions de réexamen

La décision est réévaluée si : le transport STDIO ne répond plus aux usages clients ou une exposition distante (Streamable HTTP) devient nécessaire ; le SDK officiel n'est plus maintenu, devient incompatible avec le runtime NEXUS ou change substantiellement son modèle d'outils ou de transport ; un proxy externe standard devient objectivement plus simple et plus fiable ; la façade applicative devient trop large et demande des ports applicatifs plus fins ; la composition du moteur se dédouble entre adaptateurs. Une modification substantielle donne lieu à un nouvel ADR qui remplace explicitement celui-ci.

## Mise en œuvre

- Façade : `core/src/main/java/com/nexus/application/NexusApplication.java`, composée par `NexusApplicationFactory.java`.
- REST : `adapters/rest-quarkus/src/main/java/com/nexus/api/` (`NexusResource`, `FederatedResource`, `NexusApiApplicationService`, `ApiModels`, mappers d'exceptions) ; version Quarkus épinglée par `quarkus.platform.version` (`pom.xml:55`).
- MCP : `adapters/mcp-java/src/main/java/com/nexus/mcp/` (`NexusMcpServer` construit `McpServer.sync` sur `StdioServerTransportProvider`, `NexusMcpTools`, `McpToolArguments`, `McpToolSchemas`, `McpProjectResolver`) ; SDK `io.modelcontextprotocol.sdk:mcp-core` (`adapters/mcp-java/pom.xml:25-26`), version gérée par `mcp-bom` (`pom.xml:113-114`).
- Clients : `adapters/assistant-clients/src/main/java/com/nexus/integrations/AssistantIntegrationGenerator.java`.
- Pages développeur : [REST](../../developer/rest-api.md), [MCP](../../developer/mcp.md), [architecture d'implémentation](../../developer/architecture-implementation.md).

## References

- [ARCH-0076](ARCH-0076-garder-un-coeur-java-sans-framework-acyclique-et-verifie.md) (cœur sans framework), [ARCH-0077](ARCH-0077-positionner-nexus-comme-moteur-de-contexte-independant.md) (indépendance vis-à-vis des outils externes), [ARCH-0085](ARCH-0085-stabiliser-le-contrat-de-sortie-publique-cli-rest-mcp.md) (contrat de sortie publique).
- Model Context Protocol : https://modelcontextprotocol.io/ ; SDK Java MCP : https://github.com/modelcontextprotocol/java-sdk
