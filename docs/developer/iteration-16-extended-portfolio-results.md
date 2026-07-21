# Résultats du portefeuille étendu — Itération 16

Date de validation locale : **21 juillet 2026**.

Ce document complète `iteration-16-baseline-results.md` avec le palier étendu de sept repositories réels. Il conserve séparément les résultats du portefeuille afin de distinguer le passage à l'échelle, la qualité lexicale et la diversification des résultats.

## Portefeuille

Le palier utilise :

1. le checkout NEXUS courant ;
2. `MediaUtilityTools` au commit `91e2f003a46a842e2d194fdc4bcf26e882c99c02` ;
3. `collection-manager` au commit `37ca800b2476db6f4bdc3e976afb78764ed05dda` ;
4. `db-toolkit-core` au commit `8cacc4e181e4bd9a36551b363b4d414c51f30eed` ;
5. `ariane-chatbot` au commit `827107ce20ed1bd9a4d6242caafbec6f4e266b3e` ;
6. `market-sync-app` au commit `f2861d2a5aa0a0baf368a812a3d6980cd3acae2b` ;
7. `streamApp` au commit `af5c49a38ee2670c5bd3064872b14e3b1ec15328`.

JARVIS et ses satellites restent explicitement exclus.

Le manifest reproductible est `scripts/config/iteration-16-extended-portfolio.json`.

## Validation fonctionnelle avant le dernier run

```text
mvn install
BUILD SUCCESS
55 tests exécutés
0 échec
0 erreur
2 harness opt-in ignorés
11,063 s
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

La coordination lexicale multi-termes, la recherche multi-projet, la provenance `projectId` et les corpus golden mono/fédéré sont validés.

## Volumes du dernier run avant diversification par chemin

| Métrique | Valeur |
|---|---:|
| repositories | 7 |
| requêtes | 8 |
| fichiers | 2 108 |
| symboles | 11 190 |
| relations | 18 314 |
| index Lucene cumulé | 5 155 041 octets |
| indexation complète cumulée | 10 978 ms |
| incrémental sans changement cumulé | 919 ms |
| recherche fédérée p50 | 226 ms |
| recherche fédérée p95 | 303 ms |
| contexte p50 | 51 ms |
| contexte p95 | 244 ms |
| delta heap observé | 127 783 408 octets |

Le delta heap reste une observation ponctuelle dépendante du GC et ne doit pas être interprété isolément comme une consommation stable.

## Stabilité de performance du palier 4

Trois exécutions successives du portefeuille étendu ont donné :

| Métrique | Run initial | Run coordination v1 | Run coordination finale |
|---|---:|---:|---:|
| recherche p50 | 221 ms | 165 ms | 226 ms |
| recherche p95 | 307 ms | 331 ms | 303 ms |
| contexte p50 | 53 ms | 50 ms | 51 ms |
| contexte p95 | 223 ms | 234 ms | 244 ms |
| indexation complète | 13 230 ms | 11 357 ms | 10 978 ms |
| incrémental sans changement | 1 104 ms | 949 ms | 919 ms |

Le p95 de recherche reste donc dans une plage de `303–331 ms` sur sept repositories réels. Cette croissance par rapport au portefeuille de quatre repositories ne démontre pas une rupture nécessitant Zoekt, OpenGrok ou un index distant.

## Qualité du corpus réel

Le premier run du palier 4 avait révélé une faiblesse de coordination des requêtes multi-termes :

```text
precision@3 = 0,3333
recall@3    = 0,7500
```

Après correction de la coordination Lucene par termes analysés uniques et qualification des chemins réellement pertinents, le dernier run produit :

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

`hit@3 = 1` et `MRR@3 = 1` signifient que les huit requêtes placent au moins un chemin pertinent dans le top 3 et, plus précisément, que le premier résultat pertinent est classé au rang 1 pour chacune d'elles.

Le `recall@3` inférieur à `1` sur les deux requêtes larges n'indique donc pas l'absence d'un bon résultat. Il reflète le fait que plusieurs chemins pertinents sont déclarés alors que le top 3 est encore occupé en partie par plusieurs candidats `FILE` / `SYMBOL` pointant vers le même chemin.

## Signal restant : diversification par chemin

Les baselines ont montré de manière répétée qu'un même fichier peut apparaître plusieurs fois dans le classement fédéré via des candidats `FILE` et `SYMBOL`. Cette répétition réduit mécaniquement la diversité du top 3 et peut limiter `precision@3` / `recall@3` sans améliorer l'expérience utilisateur.

La correction retenue pour la dernière validation de l'Itération 16 est donc locale à la fédération :

- trier d'abord tous les candidats fédérés avec les règles déterministes existantes ;
- conserver uniquement le meilleur candidat pour un couple `projectId + chemin normalisé` ;
- ne jamais dédupliquer deux résultats provenant de projets différents ;
- appliquer la limite finale après diversification.

Cette évolution ne modifie ni les poids du ranking, ni SQLite, ni Lucene, ni les stratégies de recherche par projet.

## Décision moteur externe

Les mesures actuelles ne justifient toujours :

- ni Zoekt ;
- ni OpenGrok ;
- ni index distant ;
- ni distribution de l'index ;
- ni parallélisation prématurée de la fédération.

La dernière mesure à effectuer est la validation du portefeuille étendu après diversification par chemin, afin de quantifier son effet sur `precision@3`, `recall@3`, `hit@3` et `MRR@3` sans dégrader la latence observée.
