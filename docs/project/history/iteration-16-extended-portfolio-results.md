# Résultats du portefeuille étendu — Itération 16

Date de validation locale : **21 juillet 2026**.

Ce document complète `iteration-16-baseline-results.md` avec le palier étendu de sept repositories réels. Il conserve séparément les résultats du portefeuille afin de distinguer le passage à l'échelle, la qualité lexicale, la diversification des résultats et le protocole final reproductible.

## Portefeuille

Le palier final utilise :

1. un snapshot Git contrôlé du `HEAD` NEXUS courant ;
2. `MediaUtilityTools` au commit `91e2f003a46a842e2d194fdc4bcf26e882c99c02` ;
3. `collection-manager` au commit `37ca800b2476db6f4bdc3e976afb78764ed05dda` ;
4. `db-toolkit-core` au commit `8cacc4e181e4bd9a36551b363b4d414c51f30eed` ;
5. `ariane-chatbot` au commit `827107ce20ed1bd9a4d6242caafbec6f4e266b3e` ;
6. `market-sync-app` au commit `f2861d2a5aa0a0baf368a812a3d6980cd3acae2b` ;
7. `streamApp` au commit `af5c49a38ee2670c5bd3064872b14e3b1ec15328`.

JARVIS et ses satellites restent explicitement exclus.

Le manifest reproductible est `scripts/config/iteration-16-extended-portfolio.json`.

## Validation fonctionnelle

Derniers builds locaux validés après diversification par chemin :

```text
mvn install
BUILD SUCCESS
55 tests exécutés
0 échec
0 erreur
2 harness opt-in ignorés
11,515 s puis 11,002 s
```

Validation ciblée :

```text
scripts/validate-iteration-16.ps1 -FocusedOnly
7 tests exécutés
0 échec
0 erreur
0 ignoré
BUILD SUCCESS
```

La coordination lexicale multi-termes, la recherche multi-projet, la diversification par chemin, la provenance `projectId` et les corpus golden mono/fédéré sont validés.

## Historique du palier étendu avant diversification

Trois exécutions successives avant diversification ont donné :

| Métrique | Run initial | Run coordination v1 | Run coordination finale |
|---|---:|---:|---:|
| recherche p50 | 221 ms | 165 ms | 226 ms |
| recherche p95 | 307 ms | 331 ms | 303 ms |
| contexte p50 | 53 ms | 50 ms | 51 ms |
| contexte p95 | 223 ms | 234 ms | 244 ms |
| indexation complète | 13 230 ms | 11 357 ms | 10 978 ms |
| incrémental sans changement | 1 104 ms | 949 ms | 919 ms |

Le dernier de ces runs indexait 2 108 fichiers, 11 190 symboles et 18 314 relations pour 5 155 041 octets d'index Lucene.

La qualité après correction de la coordination lexicale multi-termes était :

```text
precision@3 moyenne = 0,4583
recall@3 moyenne    = 0,8958
hit@3 moyen         = 1,0000
MRR@3 moyen         = 1,0000
```

## Diversification par chemin

Les baselines ont montré qu'un même fichier pouvait apparaître plusieurs fois dans le classement fédéré via des candidats `FILE` et `SYMBOL`. La correction retenue est locale à la fédération :

- trier d'abord tous les candidats fédérés avec les règles déterministes existantes ;
- conserver uniquement le meilleur candidat pour un couple `projectId + chemin normalisé` ;
- ne jamais dédupliquer deux résultats provenant de projets différents ;
- appliquer la limite finale après diversification.

Cette évolution ne modifie ni les poids du ranking, ni SQLite, ni Lucene, ni les stratégies de recherche par projet.

Deux runs consécutifs après diversification, mais avant isolation du corpus NEXUS, ont donné :

| Métrique | Run 1 | Run 2 |
|---|---:|---:|
| fichiers | 2 109 | 2 109 |
| symboles | 11 191 | 11 191 |
| relations | 18 315 | 18 315 |
| index Lucene | 5 160 406 octets | 5 160 406 octets |
| indexation complète | 10 668 ms | 10 744 ms |
| incrémental sans changement | 897 ms | 889 ms |
| recherche p50 | 202 ms | 203 ms |
| recherche p95 | 253 ms | 251 ms |
| contexte p50 | 42 ms | 42 ms |
| contexte p95 | 212 ms | 209 ms |

La qualité observée restait `precision@3 = 0,4583`, `recall@3 = 0,8958` et `hit@3 = 1,0000`, mais `MRR@3` tombait à `0,9375` parce que ce document de résultats était lui-même indexé dans le checkout NEXUS et passait au rang 1 pour `architecture hexagonale`.

Cette baisse était donc un biais du protocole, pas une régression de la diversification.

## Isolation finale du corpus NEXUS

Le runner construit désormais un snapshot Git contrôlé du `HEAD` NEXUS via `git archive` lorsque le manifest le demande. Onze artefacts propres au benchmark sont retirés du snapshot : rapports, manifests, runners et harness de mesure.

Le checkout utilisateur n'est jamais modifié.

Le snapshot Git ne transporte que le contenu versionné. Il n'embarque donc pas les artefacts locaux dérivés éventuellement présents dans le checkout, notamment un `index.scip` non versionné. NEXUS importe opportunistement ce fichier lorsqu'il existe à la racine du projet ; son absence explique que les volumes de symboles et surtout de relations du run hermétique ne soient pas directement comparables aux runs effectués sur le checkout local enrichi.

Le run hermétique est retenu comme **baseline finale canonique** parce qu'il est reconstructible à partir du commit et du manifest, indépendamment de l'état local non versionné de la machine.

## Baseline finale canonique — corpus hermétique et diversifié

Snapshot NEXUS mesuré : `eea8d2585b60533dfc1f4586bf752e6c89bc2fb4`.

| Métrique | Valeur |
|---|---:|
| repositories | 7 |
| requêtes | 8 |
| fichiers | 2 104 |
| symboles | 10 878 |
| relations | 10 087 |
| index Lucene cumulé | 5 121 497 octets |
| indexation complète cumulée | 8 818 ms |
| incrémental sans changement cumulé | 762 ms |
| recherche fédérée p50 | 133 ms |
| recherche fédérée p95 | 304 ms |
| contexte p50 | 48 ms |
| contexte p95 | 206 ms |
| delta heap observé | -2 235 520 octets |

Le delta heap négatif est une observation ponctuelle liée au GC et ne représente pas une consommation mémoire négative.

Pour NEXUS seul dans ce snapshot hermétique :

| Métrique | Valeur |
|---|---:|
| fichiers | 231 |
| symboles | 921 |
| relations | 1 497 |
| index Lucene | 790 077 octets |
| indexation complète | 1 895 ms |
| incrémental sans changement | 138 ms |

## Qualité finale du corpus réel

```text
precision@3 moyenne = 0,4583
recall@3 moyenne    = 0,8958
hit@3 moyen         = 1,0000
MRR@3 moyen         = 1,0000
```

Détail :

| Requête | precision@3 | recall@3 | hit@3 | RR@3 |
|---|---:|---:|---:|---:|
| `SearchService` | 0,6667 | 1,0000 | 1,0000 | 1,0000 |
| `MediaFileNameController` | 0,3333 | 1,0000 | 1,0000 | 1,0000 |
| `FlywayMigrator` | 0,6667 | 1,0000 | 1,0000 | 1,0000 |
| `DatabaseMigrationManager` | 0,3333 | 1,0000 | 1,0000 | 1,0000 |
| `SqlScriptExecutor` | 0,3333 | 1,0000 | 1,0000 | 1,0000 |
| `candidate-multiplier` | 0,3333 | 0,5000 | 1,0000 | 1,0000 |
| `architecture hexagonale` | 0,6667 | 0,6667 | 1,0000 | 1,0000 |
| `SceneFxmlApp` | 0,3333 | 1,0000 | 1,0000 | 1,0000 |

`hit@3 = 1` et `MRR@3 = 1` signifient que les huit requêtes placent au moins un chemin pertinent dans le top 3 et que le premier résultat pertinent est classé au rang 1 pour chacune d'elles.

Le `recall@3` inférieur à `1` sur les deux requêtes larges ne traduit donc pas l'absence d'un bon résultat. Il reflète simplement le fait que le corpus déclare plusieurs chemins pertinents alors que l'évaluation est limitée aux trois premières positions.

Exemples confirmés au rang 1 :

- `candidate-multiplier` → `ariane-chatbot/src/main/java/fr/ariane/chatbot/knowledge/KSearchSettings.java` ;
- `architecture hexagonale` → `collection-manager/docs/architecture/architecture.md` ;
- `SceneFxmlApp` → `streamApp/src/main/java/com/streamapp/SceneFxmlApp.java`.

Un faux positif de stratégie symbole reste visible en deuxième position sur `candidate-multiplier` (`GitRecencyCandidateEnricherTest.java`), mais le résultat attendu est rang 1. Ce signal peut être réévalué ultérieurement avec un corpus dédié aux requêtes symboliques multi-termes ; il ne bloque pas l'Itération 16.

## Décision moteur externe

Les quatre paliers mesurés ne justifient :

- ni Zoekt ;
- ni OpenGrok ;
- ni index distant ;
- ni distribution de l'index ;
- ni parallélisation prématurée de la fédération ;
- ni modification supplémentaire des poids de ranking.

Sur sept repositories réels, la fédération locale séquentielle reste compatible avec l'objectif local-first. Le p95 final de `304 ms` reste du même ordre de grandeur que les runs précédents du palier étendu, tandis que toutes les requêtes du corpus ont un résultat pertinent au rang 1.

## Conclusion de l'Itération 16

Les objectifs techniques de l'Itération 16 sont considérés comme **atteints** :

- recherche fédérée multi-repository opérationnelle et déterministe ;
- provenance projet conservée ;
- coordination lexicale multi-termes corrigée ;
- résultats diversifiés par chemin ;
- indexation incrémentale validée sur petit delta avec accélération mesurée de `34,45×` ;
- portefeuille réel étendu jusqu'à sept repositories ;
- protocole final reproductible et isolé de ses propres artefacts de mesure ;
- aucune limite mesurée ne justifie un moteur externe.

Une future réouverture du sujet Zoekt/OpenGrok devra être déclenchée par une limite reproductible sur un portefeuille réellement plus grand ou par un besoin fonctionnel non couvert, pas par anticipation.