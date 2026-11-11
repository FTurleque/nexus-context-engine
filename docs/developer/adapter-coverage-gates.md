# Gates de couverture des adaptateurs

NXA2-09 contractualise la couverture JaCoCo des trois modules d'adaptation NEXUS : REST, MCP et assistant-clients. Ce document décrit aussi le gate du core et les planchers par paquet et par classe qui complètent le niveau `BUNDLE`.

## Principe

Les seuils sont des planchers de non-régression exécutés par `jacoco:check` pendant la phase Maven `verify`. Ils ne remplacent pas les tests fonctionnels et ne doivent pas être abaissés pour faire passer une régression.

Aucune classe ni aucun package de production n'est exclu du calcul pour atteindre les seuils.

## Baselines mesurées

La première mesure sur GitHub Actions, avant correction de l'instrumentation MCP, a donné :

| Module | Lignes | Branches |
|---|---:|---:|
| core | 79,07 % | 61,85 % |
| REST | 61,19 % | 64,38 % |
| MCP | 6,71 % | 13,79 % |
| assistant-clients | 66,39 % | 49,44 % |

Le score MCP initial ne reflétait pas les scénarios réellement exécutés : `NexusMcpServerIntegrationTest` démarre le serveur MCP dans une JVM enfant, alors que l'agent JaCoCo hérité de Surefire instrumentait seulement la JVM de test.

NXA2-09 ajoute donc un agent JaCoCo à la JVM enfant. Pour rester fiable sur Linux **et Windows**, la collecte ne dépend ni d'une écriture concurrente ni du dump automatique à l'arrêt du processus :

- la JVM Surefire écrit `target/jacoco.exec` ;
- le serveur MCP enfant expose son agent JaCoCo en `tcpserver` uniquement sur `127.0.0.1`, sur un port éphémère réservé par le test ;
- avant `client.closeGracefully()`, `NexusMcpServerIntegrationTest` récupère explicitement les données d'exécution avec `ExecDumpClient` et les enregistre dans `target/jacoco-mcp-child.exec` ;
- `jacoco:merge` fusionne ensuite `jacoco.exec` et `jacoco-mcp-child.exec` dans `target/jacoco-merged.exec` ;
- le rapport MCP et `jacoco:check` utilisent exclusivement ce fichier fusionné.

Le dump explicite avant arrêt est nécessaire parce que le transport STDIO client du SDK MCP peut terminer le processus serveur ; la couverture ne doit donc pas dépendre d'un flush de fin de JVM dont la sémantique diffère selon le système d'exploitation.

### Baseline NXA7

L'audit NXA7 a ajouté une qualification CLI directe de `assistant-clients` couvrant les profils, modes, formats et erreurs d'arguments. Le reactor Linux qualifié sur le code NXA7 mesure :

| Module | Lignes couvertes | Lignes | Branches couvertes | Branches |
|---|---:|---:|---:|---:|
| core | 6 066 / 7 574 | 80,09 % | 2 058 / 3 257 | 63,19 % |
| REST | 360 / 518 | 69,50 % | 145 / 215 | 67,44 % |
| MCP | 291 / 328 | 88,72 % | 35 / 58 | 60,34 % |
| assistant-clients | 119 / 122 | **97,54 %** | 77 / 89 | **86,52 %** |

Le nouveau plancher `assistant-clients` est volontairement inférieur à cette mesure de plusieurs points afin de conserver une marge d'évolution tout en empêchant un retour vers la baseline historique de 66,39 % / 49,44 %.

## Seuils bloquants

Chaque module exécute deux `jacoco:check` : `jacoco-check`, hérité du `pom.xml` racine (`BUNDLE`, 65 % des lignes, 55 % des branches, propriétés `nexus.coverage.line.minimum` et `nexus.coverage.branch.minimum`), et l'exécution propre au module. Le seuil effectif est le plus strict des deux ; les tableaux ci-dessous donnent ce seuil effectif.

### Niveau module (`BUNDLE`)

| Module | Exécution | Minimum lignes | Minimum branches |
|---|---|---:|---:|
| core | `jacoco-check-core` | 84 % | 69 % |
| REST | `jacoco-check-rest` | 87 % | 75 % |
| MCP | `jacoco-check-mcp` | 86 % | 75 % |
| assistant-clients | `jacoco-check-assistant-clients` | 92 % | 84 % |

### Niveau paquet et classe

Le niveau `BUNDLE` laisse une marge de plusieurs centaines de lignes dans le core : une régression concentrée sur un paquet de confinement ou sur un importeur de code intelligence ne le fait pas échouer. De même, une règle `PACKAGE` reste verte quand une seule classe de bornes perd ses tests. Des règles `PACKAGE` et `CLASS` fixent donc un plancher pour ces zones :

| Module | Élément | Cible | Minimum lignes | Minimum branches |
|---|---|---|---:|---:|
| core | `PACKAGE` | `com.nexus.security` | 87 % | 82 % |
| core | `PACKAGE` | `com.nexus.index.jdt` | 67 % | 53 % |
| core | `PACKAGE` | `com.nexus.index.minos` | 74 % | 60 % |
| core | `PACKAGE` | `com.nexus.index.scip` | 80 % | 60 % |
| REST | `PACKAGE` | `com.nexus.api` | 87 % | 75 % |
| core | `CLASS` | `com.nexus.index.jdt.JdtStdioSession` | 40 % | 16 % |
| core | `CLASS` | `com.nexus.index.minos.MinosPayloadReader` | 60 % | 36 % |
| core | `CLASS` | `com.nexus.search.semantic.SemanticSearchConfiguration` | 74 % | 68 % |
| REST | `CLASS` | `com.nexus.api.NexusApiApplicationService` | 82 % | 58 % |
| REST | `CLASS` | `com.nexus.api.NexusRestTransportPolicy` | 77 % | 49 % |

Le plancher de branches de `JdtStdioSession` (16 %) est une garde contre la régression, pas un objectif de couverture : la classe pilote le processus JDT LS, que la suite par défaut n'exécute pas de bout en bout.

### Règle d'écart

Un plancher est au plus 3 points sous la mesure de référence de sa cible, et jamais au-dessus. La mesure de référence est la plus basse des mesures du reactor complet dans les jobs `Linux reactor Maven build` et `Windows gate` de NEXUS CI, car le même plancher s'applique aux deux builds. Un plancher se fixe à la partie entière de la mesure de référence moins 2 points ; il n'est jamais abaissé sans décision explicite, et un plancher déjà au-dessus de cette valeur reste en place tant qu'il respecte l'écart.

La PR qui améliore durablement la couverture d'une cible remonte son plancher et met à jour la table ci-dessous. Une PR qui fait varier la mesure d'une cible de plus de 3 points sur sa plateforme, ou d'une ligne ou d'une branche quand cette unité dépasse 3 points (`MinosPayloadReader`, `SemanticSearchConfiguration`, branches de `NexusApiApplicationService`), met elle aussi la table à jour. La table est remesurée en entier sur les deux plateformes au commit qu'elle cite.

`scripts/release/coverage_floors.py` vérifie ces règles :

- chaque plancher des `pom.xml` (seuil effectif par module, élément, cible et compteur) a une ligne dans la table, et chaque ligne a un plancher ;
- chaque plancher est au plus 3 points sous la mesure de référence publiée ;
- chaque règle `CLASS` ou `PACKAGE` désigne une classe ou un paquet présent dans le `jacoco.csv` du module, car JaCoCo accepte une inclusion qui ne correspond à rien et laisse la règle vide ;
- la mesure du build courant n'est ni sous le plancher, ni plus loin de la mesure publiée pour sa plateforme que 3 points ou une unité du compteur, la plus grande des deux ;
- les formes de configuration qu'il ne lit pas (inclusion à joker, exclusions, règles au niveau du plugin, exécutions JaCoCo hors de `build/plugins`) le font échouer.

NEXUS CI l'exécute après le build du reactor dans les jobs `Linux reactor Maven build` et `Windows gate`, qui conservent les `jacoco.csv` en artefact (`nexus-ci-evidence-<sha>`, `nexus-windows-coverage-<sha>`) ; `/nx-validate` l'exécute après le build local (plateforme détectée, `--platform` pour la forcer). `bash .claude/scripts/contracts.sh` le lance sans rapports (`--skip-reports`, comparaison des `pom.xml` à la table) et vérifie qu'il rejette ses fixtures négatives (`scripts/release/fixtures/coverage-floors/`).

### Mesure de référence

Mesure du reactor complet au commit `f8a27f89` : Linux dans l'artefact `nexus-ci-evidence` de NEXUS CI, Windows sur `./mvnw -B -ntp clean install` ; ligne `com.nexus.security` remesurée avec la politique `secret-redaction-v9`, Linux dans l'artefact `nexus-ci-evidence` du commit `c83c04ca`.

| Module | Élément | Cible | Lignes Linux | Branches Linux | Lignes Windows | Branches Windows |
|---|---|---|---:|---:|---:|---:|
| `core` | `BUNDLE` | — | 9 635 / 11 099 (86,81 %) | 3 336 / 4 639 (71,91 %) | 9 643 / 11 099 (86,88 %) | 3 324 / 4 639 (71,65 %) |
| `core` | `PACKAGE` | `com.nexus.security` | 625 / 700 (89,29 %) | 438 / 514 (85,21 %) | 636 / 700 (90,86 %) | 435 / 514 (84,63 %) |
| `core` | `PACKAGE` | `com.nexus.index.jdt` | 568 / 812 (69,95 %) | 208 / 375 (55,47 %) | 568 / 812 (69,95 %) | 208 / 375 (55,47 %) |
| `core` | `PACKAGE` | `com.nexus.index.minos` | 247 / 303 (81,52 %) | 131 / 195 (67,18 %) | 233 / 303 (76,90 %) | 121 / 195 (62,05 %) |
| `core` | `PACKAGE` | `com.nexus.index.scip` | 387 / 468 (82,69 %) | 167 / 261 (63,98 %) | 386 / 468 (82,48 %) | 164 / 261 (62,84 %) |
| `core` | `CLASS` | `com.nexus.index.jdt.JdtStdioSession` | 76 / 177 (42,94 %) | 13 / 70 (18,57 %) | 76 / 177 (42,94 %) | 13 / 70 (18,57 %) |
| `core` | `CLASS` | `com.nexus.index.minos.MinosPayloadReader` | 17 / 27 (62,96 %) | 7 / 18 (38,89 %) | 17 / 27 (62,96 %) | 7 / 18 (38,89 %) |
| `core` | `CLASS` | `com.nexus.search.semantic.SemanticSearchConfiguration` | 23 / 30 (76,67 %) | 14 / 20 (70,00 %) | 23 / 30 (76,67 %) | 14 / 20 (70,00 %) |
| `rest-quarkus` | `BUNDLE` | — | 488 / 544 (89,71 %) | 241 / 311 (77,49 %) | 488 / 544 (89,71 %) | 241 / 311 (77,49 %) |
| `rest-quarkus` | `PACKAGE` | `com.nexus.api` | 488 / 544 (89,71 %) | 241 / 311 (77,49 %) | 488 / 544 (89,71 %) | 241 / 311 (77,49 %) |
| `rest-quarkus` | `CLASS` | `com.nexus.api.NexusApiApplicationService` | 69 / 82 (84,15 %) | 6 / 10 (60,00 %) | 69 / 82 (84,15 %) | 6 / 10 (60,00 %) |
| `rest-quarkus` | `CLASS` | `com.nexus.api.NexusRestTransportPolicy` | 49 / 62 (79,03 %) | 28 / 54 (51,85 %) | 49 / 62 (79,03 %) | 28 / 54 (51,85 %) |
| `mcp-java` | `BUNDLE` | — | 297 / 336 (88,39 %) | 85 / 109 (77,98 %) | 297 / 336 (88,39 %) | 85 / 109 (77,98 %) |
| `assistant-clients` | `BUNDLE` | — | 121 / 128 (94,53 %) | 77 / 89 (86,52 %) | 121 / 128 (94,53 %) | 79 / 89 (88,76 %) |

`MinosCodeIndexImporter` est mieux couvert sous Linux : `MinosProjectPathGuardTest` ne s'exécute que sur un système de fichiers à séparateur `/`. Les écarts de `com.nexus.security` viennent des tests propres à chaque système (`WindowsStoragePrincipalsTest`, `SafeFileIOTest`).

Un `./mvnw -B clean install` échoue si l'un des planchers n'est plus respecté.

## Chemins critiques couverts

### REST

Les `@QuarkusTest` chargent les classes de l'application dans le `QuarkusClassLoader`, que l'agent JaCoCo de Surefire ne crédite pas pour les beans CDI : `NexusApiApplicationService`, exercé par `NexusResourceTest`, n'apparaît alors qu'à 13 lignes sur 95. L'extension `io.quarkus:quarkus-jacoco` (scope test, version gérée par le BOM Quarkus) instrumente ces classes. La configuration conjointe suit la documentation Quarkus « Tests with coverage » :

- l'exécution `jacoco-prepare` du module exclut `*QuarkusClassLoader` et ajoute ses données (`append`) ;
- Surefire fixe `quarkus.jacoco.data-file` sur `target/jacoco.exec`, avec `quarkus.jacoco.reuse-data-file=true` et `quarkus.jacoco.report=false` ;
- le rapport `target/site/jacoco` et les deux `jacoco:check` lisent ce fichier unique.

La règle `CLASS` sur `NexusApiApplicationService` échoue si cette instrumentation disparaît.

La qualification couvre notamment :

- exposition loopback/non-loopback ;
- posture loopback durcie opt-in et ses échecs fermés ;
- robustesse du token distant/local durci ;
- comparaison Bearer ;
- filtre d'authentification sans token, avec token valide et réponse 401 structurée ;
- politiques de racines projet ;
- ressources REST locales et fédérées.

### MCP

Le serveur STDIO réel est instrumenté, pas simulé. La qualification couvre les handlers publics et leurs validations, y compris les contrats de limites NXA2-06 et NXA2-08.

La JVM enfant doit conserver l'agent défini via `nexus.mcp.child.jacoco.argLine`, le dump explicite loopback via `ExecDumpClient` avant fermeture du client MCP, puis le merge de ces données avec celles de Surefire. Supprimer l'un de ces mécanismes provoquerait une chute de couverture et ferait échouer le gate MCP.

### assistant-clients

Les tests couvrent désormais :

- les sorties structurées JSON/TOML ;
- l'échappement des commandes portables : espaces, métacaractères, quotes, backslashes, arguments vides et Unicode ;
- les syntaxes legacy et explicites ;
- les modes `native` et `docker` ;
- les profils Copilot, JetBrains, Claude, Codex et générique ;
- les branches d'erreur pour profil inconnu, arguments incomplets, container vide et commande vide.

Le test Windows réel d'argv reste conditionné à Windows ; les autres tests restent multiplateformes.

## Rapports et diagnostic CI

Les rapports sont générés aux emplacements :

```text
core/target/site/jacoco/jacoco.xml
adapters/rest-quarkus/target/site/jacoco/jacoco.xml
adapters/mcp-java/target/site/jacoco/jacoco.xml
adapters/assistant-clients/target/site/jacoco/jacoco.xml
```

NEXUS CI :

- vérifie que les quatre XML existent et ne sont pas vides ;
- imprime les ratios lignes/branches dans le log Linux ;
- conserve les XML et `index.html` comme artefacts de qualification ;
- le gate Windows exige également la présence des quatre rapports après le reactor complet.

La source d'autorité du PASS reste `jacoco:check` dans le lifecycle Maven ; l'impression CI sert au diagnostic et au suivi de tendance.

## OpenSpec coverage-gates

Spec : [`openspec/specs/coverage-gates/spec.md`](../../openspec/specs/coverage-gates/spec.md) ; matrice de traçabilité : [openspec-traceability.md](openspec-traceability.md).

Cette section décrit les planchers JaCoCo bloquants par classe. Elle ne contient pas de diagramme : le sujet est une liste de règles et de mesures, que les tableaux de la section « Seuils bloquants » portent.

### Comportement existant vérifié

- **[Existant vérifié]** L'exécution `jacoco-check-core` de `core/pom.xml` porte une règle `CLASS` lignes et branches pour `JdtStdioSession`, `MinosPayloadReader` et `SemanticSearchConfiguration` ; l'exécution `jacoco-check-rest` de `adapters/rest-quarkus/pom.xml` en porte une pour `NexusApiApplicationService` et une pour `NexusRestTransportPolicy`. Seuils : section « Niveau paquet et classe » ; mesures : section « Mesure de référence ».
- **[Existant vérifié]** Une règle `CLASS` non respectée fait échouer `jacoco:check` avec un message qui nomme la classe. Preuve unique : avec les trois tests `rejectsReverseProxyHttps*` de `NexusRestExposureGuardTest` désactivés, `./mvnw -B -ntp -pl adapters/rest-quarkus -am clean verify` échoue sur `jacoco-check-rest` (« Rule violated for class com.nexus.api.NexusRestTransportPolicy: branches covered ratio is 0.48, but expected minimum is 0.49 », lignes 0,75 pour 0,77) alors que `com.nexus.api` reste à 239 / 311 branches (76,85 %), au-dessus de son plancher de paquet.
- **[Existant vérifié]** `scripts/release/coverage_floors.py` refuse une règle `CLASS` ou `PACKAGE` dont l'inclusion ne correspond à aucune classe ni aucun paquet du `jacoco.csv` du module, en nommant la règle : la fixture `scripts/release/fixtures/coverage-floors/rule-typo` (inclusion `com.nexus.api.NexusRestTransportPolicyTypo`) est rejetée par `scripts/release/test-operational-doc-contracts.sh`, et NEXUS CI exécute le contrôle sur les rapports des builds Linux et Windows.
- **[Existant vérifié]** Le même script vérifie l'écart de chaque plancher à la mesure de référence publiée et la présence d'une ligne de mesure pour chaque plancher (section « Règle d'écart ») ; ses fixtures `floor-too-low` et `floor-without-measure` sont rejetées par le même contrat.
- **[Existant vérifié]** Le module REST ajoute ses données d'exécution à `target/jacoco.exec` (`append`) : un `verify` sans `clean` relit les données d'une exécution précédente et peut masquer la perte d'un test. La mesure et la preuve d'une règle se font après `clean`.

### Écarts et limites

- **[Limite]** La preuve de régression est manuelle et n'est rejouée par aucun test ni job CI.
- **[Limite]** Sous Linux, `com.nexus.index.minos` dépasse de plus de 3 points sa mesure de référence, prise sous Windows : le plancher commun aux deux builds y laisse une marge plus large, que seul le contrôle de la mesure du build contre la colonne Linux borne.

## OpenSpec adapter-contract-tests

Spec : [`openspec/specs/adapter-contract-tests/spec.md`](../../openspec/specs/adapter-contract-tests/spec.md) ; matrice de traçabilité : [openspec-traceability.md](openspec-traceability.md).

Cette section décrit la granularité des tests de contrat REST et MCP, les tests de bout en bout dont dépendent aussi les gates de couverture ci-dessus. Elle ne contient pas de diagramme : le sujet est une correspondance endpoint → test et outil → test, que deux tableaux rendent mieux qu'une vue.

### Comportement existant vérifié

- **[Existant vérifié]** `NexusResourceTest` est un `@QuarkusTest` à cycle de vie `PER_CLASS` qui compte 14 méthodes `@Test` (`adapters/rest-quarkus/src/test/java/com/nexus/api/NexusResourceTest.java:38-40`). Sa Javadoc annonce « un test par endpoint public et par endpoint Quarkus attendu » (`:31-37`).
- **[Existant vérifié]** L'adaptateur REST publie 11 endpoints applicatifs : 9 dans `NexusResource` (`adapters/rest-quarkus/src/main/java/com/nexus/api/NexusResource.java:29-98`) et 2 dans `FederatedResource` (`adapters/rest-quarkus/src/main/java/com/nexus/api/FederatedResource.java:23-47`). Correspondance avec `NexusResourceTest` :

| Endpoint | Test dans `NexusResourceTest` |
|---|---|
| `GET /api/v1/projects` | `listProjectsIncludesTheFixtureWithoutRootPaths` |
| `POST /api/v1/projects` | `createProjectReturnsANotIndexedProjectWithoutRootPath` |
| `GET /api/v1/projects/{projectId}` | **aucun** (voir Écarts) |
| `POST /api/v1/projects/{projectId}/index` | `indexMarksAFreshProjectReadyAndReportsStatistics` (et le `@BeforeEach` de la fixture) |
| `GET /api/v1/projects/{projectId}/index` | `inspectIndexReturnsTheFixtureStatistics` |
| `POST …/search` | `searchReturnsExplainedResultsWithPublicPaths`, `searchRejectsBlankAndOversizedQueries` |
| `POST …/explain/search` | `explainSearchForcesExplanation` |
| `POST …/context` | `contextRespectsTheBudgetAndRedactsSecrets`, `contextRejectsConstraintsAndOversizedQueries` |
| `POST …/explain/context` | `explainContextForcesExplanation` |
| `POST /api/v1/federated/search` | `federatedSearchAcceptsTheMaximumLimitAndRejectsMaximumPlusOne` |
| `POST /api/v1/federated/context` | `federatedContextRespectsTheBudgetAndRejectsMaximumPlusOne` |
| `/q/health/ready` | `readinessIsNotPublishedOnTheApplicationListener` (attend `404`) |
| `/q/metrics` | `metricsAreNotPublishedOnTheApplicationListener` (attend `404`) |

- **[Existant vérifié]** Health et métriques ne sont plus servis par le listener applicatif : `quarkus.management.enabled=true`, hôte `127.0.0.1`, port `9000` (`adapters/rest-quarkus/src/main/resources/application.properties:8-10`). Leur exposition effective (`200`) est vérifiée dans une autre classe, `ManagementEndpointIsolationTest#exposesHealthAndMetricsOnlyOnTheDedicatedManagementListener` (`adapters/rest-quarkus/src/test/java/com/nexus/api/ManagementEndpointIsolationTest.java:24-25`).
- **[Existant vérifié]** `NexusMcpServerIntegrationTest` lance un vrai serveur STDIO et compte 14 méthodes `@Test`. Le serveur enregistre 9 outils (`adapters/mcp-java/src/main/java/com/nexus/mcp/NexusMcpTools.java:41-51`). Chaque outil a au moins un test propre :

| Outil MCP | Test dans `NexusMcpServerIntegrationTest` |
|---|---|
| `list_projects` | `listProjectsReturnsTheRegisteredProjectWithoutRootPath` |
| `search_code` | `searchCodeReturnsTheDirectSearchResultWithAPublicPath`, `searchCodeRejectsAnOversizedUtf8Query`, `searchCodeRejectsSensitiveSelectorsAndUnexpectedArgumentsWithoutEchoingThem` |
| `build_context` | `buildContextRedactsSecretsAndMatchesTheDirectContextBudget` |
| `explain_context` | `explainContextReturnsAnExplainedContext` (et `explainToolsDoNotLeakPathsWhenIndexedFilesHaveDisappeared`) |
| `find_symbol` | `findSymbolReturnsTheIndexedSymbol` |
| `find_usages` | `findUsagesReturnsARelationArray` |
| `search_across_projects` | `searchAcrossProjectsDeduplicatesTheScope`, `searchAcrossProjectsRejectsTooManyDistinctProjects` |
| `build_context_across_projects` | `buildContextAcrossProjectsCoversTheRequestedProject` |
| `explain_context_across_projects` | `explainContextAcrossProjectsReturnsAnExplainedContext` (et le test de disparition de fichiers) |

  Le quatorzième test, `listsExactlyTheExpectedTools`, compare l'ensemble des outils exposés à `EXPECTED_TOOLS` (9 noms) : un outil ajouté sans mise à jour de ce test le fait échouer, mais il n'impose pas de méthode `@Test` dédiée au nouvel outil.
- **[Existant vérifié]** Non-fuite REST : les 14 tests appellent `assertNoLeak` sur chaque corps lu (`NexusResourceTest.java:404-413`). Elle vérifie l'absence du répertoire de fixture (formes `\`, `\\` et `/`), de `synthetic-unquoted-secret-123456` et de six marqueurs de modèle interne (`com.nexus.`, `ProjectDescriptor`, `RankedCandidate`, `IndexingReport`, `FederatedSearchHit`, `CodeSymbol`).
- **[Existant vérifié]** Non-fuite MCP : `assertNoLeak` (méthode privée de `NexusMcpServerIntegrationTest`) inspecte la charge sérialisée et le texte décodé, avec la racine du projet, la home NEXUS, le répertoire de classe, les noms `Alice Smith` et `Build Secret`, le secret de fixture et cinq marqueurs de modèle interne (`com.nexus.`, `ProjectDescriptor`, `RankedCandidate`, `ContextBundle`, `FederatedSearchHit`). Les tests d'outils l'appellent ; `listsExactlyTheExpectedTools` ne lit aucune réponse d'outil et ne l'appelle pas.
- **[Existant vérifié]** Indépendance d'ordre : ni `@Order` ni `@TestMethodOrder` dans `adapters/*/src/test`, et aucune option d'ordre (`runOrder`, `junit.jupiter.testmethod.order`) dans les `pom.xml`, `.java` ou `.properties` du dépôt (recherche par Grep). Par construction, REST crée la fixture paresseusement dans un `@BeforeEach` car un `@BeforeAll` précède la fixation du port RestAssured (`NexusResourceTest.java:57-75`), et les tests qui mutent l'état créent leur propre projet (`indexMarksAFreshProjectReadyAndReportsStatistics`). MCP prépare serveur et projet dans un `@BeforeAll` ; le seul test mutant (`explainToolsDoNotLeakPathsWhenIndexedFilesHaveDisappeared`) démarre sa propre home et son propre serveur (Javadoc de classe, `NexusMcpServerIntegrationTest.java:42-49`).

### Vues

Aucune : la correspondance endpoint → test et outil → test est donnée par les deux tableaux ci-dessus. Exigences : `adapter-contract-tests › Un test par endpoint REST`, `adapter-contract-tests › Un test par outil MCP`.

### Écarts et limites

- **[Écart]** `GET /api/v1/projects/{projectId}` (`NexusResource#getProject`, `NexusResource.java:54-55`) n'a pas de test dans `NexusResourceTest`, alors que l'exigence demande « une méthode `@Test` distincte pour chaque endpoint REST public ». Il est exercé ailleurs, dans `NexusRestOutputParityTest#indexAndProjectMatchThePublicContract` (`adapters/rest-quarkus/src/test/java/com/nexus/api/NexusRestOutputParityTest.java:101-108`), classe qui n'est pas celle visée. La tâche archivée 2.1 ne listait pas cet endpoint.
- **[Écart]** Pour `/q/health/ready` et `/q/metrics`, l'exigence parle d'endpoints « exposés » ; `NexusResourceTest` vérifie au contraire qu'ils ne sont **pas** publiés sur le listener applicatif (`404`). La preuve d'exposition sur le listener de management vit dans `ManagementEndpointIsolationTest`, hors de `NexusResourceTest`. Le texte de l'exigence n'a pas été modifié.
- **[Écart]** « Seul le test de cet endpoint échoue » : `POST …/search` et `POST …/context` sont chacun visés par deux méthodes (nominal et rejets) ; une régression de contrat sur l'un de ces endpoints peut donc faire échouer deux tests.
- **[Écart]** « Ni chemin absolu de la machine » : les assertions ne cherchent que les chemins de la fixture (répertoire de classe, racine projet, home NEXUS), pas un chemin absolu quelconque ; la liste des marqueurs de modèle interne est fermée (six en REST, cinq en MCP, listes différentes).
- **[Limite]** Aucun test ni job CI n'exécute les classes dans un ordre inversé ou aléatoire : l'indépendance d'ordre repose sur la lecture de la conception.
- **[Limite]** Le rapport Surefire du HEAD courant n'a pas été relu (aucune exécution Maven pour cette documentation) ; les 14 méthodes de chaque classe sont comptées dans les sources.

### Propositions et questions ouvertes

- **[Proposition]** Ajouter dans `NexusResourceTest` un test dédié à `GET /api/v1/projects/{projectId}` (avec `assertNoLeak`), ou préciser dans l'exigence que la couverture de cet endpoint peut résider dans `NexusRestOutputParityTest`.
- **[Question]** L'exigence « un test par endpoint Quarkus exposé » doit-elle être reformulée pour distinguer le `404` du listener applicatif de l'exposition sur l'interface de management (port `9000`) ?
- **[Proposition]** Exécuter les deux classes avec un ordre de méthodes inversé ou aléatoire (`junit.jupiter.testmethod.order.default`) dans un job CI, pour prouver le scénario « Ordre inversé » ; rien ne l'exécute aujourd'hui.
- **[Proposition]** Un test de complétude qui dérive les endpoints JAX-RS (ou les outils MCP) par réflexion et exige un `@Test` référencé pour chacun, au lieu de la seule égalité d'ensemble de `listsExactlyTheExpectedTools`.
