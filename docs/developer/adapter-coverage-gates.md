# Gates de couverture des adaptateurs

NXA2-09 contractualise la couverture JaCoCo des trois modules d'adaptation NEXUS : REST, MCP et assistant-clients. Le gate historique du core reste inchangé.

## Principe

Les seuils sont des planchers de non-régression exécutés par `jacoco:check` pendant la phase Maven `verify`. Ils ne remplacent pas les tests fonctionnels et ne doivent pas être abaissés pour faire passer une régression.

Aucune classe ni aucun package de production n'est exclu du calcul pour atteindre les seuils NXA2-09.

## Baseline mesurée

La première mesure sur GitHub Actions, avant correction de l'instrumentation MCP, a donné :

| Module | Lignes | Branches |
|---|---:|---:|
| core | 79,07 % | 61,85 % |
| REST | 61,19 % | 64,38 % |
| MCP | 6,71 % | 13,79 % |
| assistant-clients | 66,39 % | 49,44 % |

Le score MCP initial ne reflétait pas les scénarios réellement exécutés : `NexusMcpServerIntegrationTest` démarre le serveur MCP dans un JVM enfant, alors que l'agent JaCoCo hérité de Surefire instrumentait seulement le JVM de test.

NXA2-09 ajoute donc un agent JaCoCo au JVM enfant. Pour rester fiable sur Linux **et Windows**, les deux JVM n'écrivent pas simultanément dans le même fichier :

- le JVM Surefire écrit `target/jacoco.exec` ;
- le serveur MCP enfant écrit `target/jacoco-mcp-child.exec` ;
- `jacoco:merge` fusionne les deux après les tests dans `target/jacoco-merged.exec` ;
- le rapport MCP et `jacoco:check` utilisent exclusivement ce fichier fusionné.

Cette séparation évite les divergences de sémantique de verrouillage fichier entre systèmes d'exploitation. Le test STDIO réel exerce en outre la surface publique des outils MCP : listing projets, recherche, symboles/usages, contexte local, contexte expliqué, contexte fédéré, limite de requête et limite de portée fédérée.

Après ce renforcement et l'ajout d'un test direct du filtre Bearer REST, la baseline qualifiée sur GitHub Actions est :

| Module | Lignes couvertes | Lignes | Branches couvertes | Branches |
|---|---:|---:|---:|---:|
| core | 5 433 / 6 871 | 79,07 % | 1 824 / 2 949 | 61,85 % |
| REST | 265 / 420 | 63,10 % | 97 / 146 | 66,44 % |
| MCP | 291 / 328 | 88,72 % | 35 / 58 | 60,34 % |
| assistant-clients | 81 / 122 | 66,39 % | 44 / 89 | 49,44 % |

## Seuils bloquants

Les planchers conservent une marge comparable au gate historique du core tout en empêchant une dégradation silencieuse des frontières publiques :

| Module | Minimum lignes | Minimum branches |
|---|---:|---:|
| core | 70 % | 50 % |
| REST | 60 % | 60 % |
| MCP | 80 % | 55 % |
| assistant-clients | 60 % | 45 % |

Ces seuils sont versionnés dans le `pom.xml` de chaque module. Un `./mvnw -B clean install` échoue si l'un des planchers n'est plus respecté.

## Chemins critiques couverts

### REST

La qualification couvre notamment :

- exposition loopback/non-loopback ;
- robustesse du token distant ;
- comparaison Bearer ;
- filtre d'authentification sans token, avec token valide et réponse 401 structurée ;
- politiques de racines projet ;
- ressources REST locales et fédérées.

### MCP

Le serveur STDIO réel est instrumenté, pas simulé. La qualification couvre les handlers publics et leurs validations, y compris les contrats de limites NXA2-06 et NXA2-08.

Le JVM enfant doit conserver l'agent défini via `nexus.mcp.child.jacoco.argLine` et le merge explicite de ses données avec celles de Surefire. Supprimer l'un de ces mécanismes provoquerait une chute de couverture et ferait échouer le gate MCP.

### assistant-clients

Les tests couvrent les sorties structurées JSON/TOML et l'échappement des commandes portables : espaces, métacaractères, quotes, backslashes, arguments vides et Unicode. Le test Windows réel d'argv reste conditionné à Windows ; les autres tests restent multiplateformes.

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
