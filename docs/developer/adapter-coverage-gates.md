# Gates de couverture des adaptateurs

NXA2-09 contractualise la couverture JaCoCo des trois modules d'adaptation NEXUS : REST, MCP et assistant-clients. Le gate historique du core reste inchangé.

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

| Module | Minimum lignes | Minimum branches |
|---|---:|---:|
| core | 70 % | 50 % |
| REST | 60 % | 60 % |
| MCP | 80 % | 55 % |
| assistant-clients | **90 %** | **75 %** |

Ces seuils sont versionnés dans le `pom.xml` de chaque module. Un `./mvnw -B clean install` échoue si l'un des planchers n'est plus respecté.

## Chemins critiques couverts

### REST

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
