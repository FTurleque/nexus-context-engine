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

Chaque module exécute deux `jacoco:check` : `jacoco-check`, hérité du `pom.xml` racine (`BUNDLE`, 65 % des lignes, 55 % des branches, propriétés `nexus.coverage.line.minimum` et `nexus.coverage.branch.minimum`), et l'exécution propre au module. Le seuil effectif est le plus strict des deux ; les valeurs du `pom.xml` de module sont égales à ce seuil effectif.

### Niveau module (`BUNDLE`)

| Module | Exécution | Minimum lignes | Minimum branches |
|---|---|---:|---:|
| core | `jacoco-check-core` | 70 % | 55 % |
| REST | `jacoco-check-rest` | 65 % | 60 % |
| MCP | `jacoco-check-mcp` | 80 % | 55 % |
| assistant-clients | `jacoco-check-assistant-clients` | **90 %** | **75 %** |

### Niveau paquet et classe

Le niveau `BUNDLE` laisse une marge de plusieurs centaines de lignes dans le core : une régression concentrée sur un paquet de confinement ou sur un importeur de code intelligence ne le fait pas échouer. Des règles `PACKAGE` et `CLASS` fixent donc un plancher proche de la mesure pour ces zones :

| Module | Élément | Cible | Minimum lignes | Minimum branches |
|---|---|---|---:|---:|
| core | `PACKAGE` | `com.nexus.security` | 85 % | 77 % |
| core | `PACKAGE` | `com.nexus.index.jdt` | 66 % | 52 % |
| core | `PACKAGE` | `com.nexus.index.minos` | 73 % | 59 % |
| core | `PACKAGE` | `com.nexus.index.scip` | 79 % | 60 % |
| REST | `PACKAGE` | `com.nexus.api` | 78 % | 67 % |
| REST | `CLASS` | `com.nexus.api.NexusApiApplicationService` | 60 % | 38 % |

Ces planchers sont deux à trois points sous la mesure Windows du reactor complet :

| Cible | Lignes couvertes | Lignes | Branches couvertes | Branches |
|---|---:|---:|---:|---:|
| core (`BUNDLE`) | 8 378 / 9 997 | 83,81 % | 2 926 / 4 321 | 67,72 % |
| `com.nexus.security` | 360 / 413 | 87,17 % | 260 / 326 | 79,75 % |
| `com.nexus.index.jdt` | 550 / 804 | 68,41 % | 204 / 375 | 54,40 % |
| `com.nexus.index.minos` | 224 / 296 | 75,68 % | 120 / 195 | 61,54 % |
| `com.nexus.index.scip` | 373 / 459 | 81,26 % | 159 / 255 | 62,35 % |
| REST (`BUNDLE`, `com.nexus.api`) | 496 / 612 | 81,05 % | 183 / 261 | 70,11 % |
| `NexusApiApplicationService` | 60 / 95 | 63,16 % | 4 / 10 | 40,00 % |
| MCP | 392 / 437 | 89,70 % | 89 / 113 | 78,76 % |
| assistant-clients | 121 / 128 | 94,53 % | 79 / 89 | 88,76 % |

Un `./mvnw -B clean install` échoue si l'un des planchers n'est plus respecté. Une hausse durable de la mesure justifie de remonter le plancher correspondant ; une baisse ne justifie jamais de l'abaisser sans décision explicite.

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
