# Résultats de baseline — Itération 16

Ce document conserve les résultats mesurés pendant l'Itération 16 afin de distinguer les limites réellement observées des hypothèses de passage à l'échelle.

Les mesures sont des observations reproductibles sur la machine de validation utilisée. Elles ne constituent pas, à elles seules, des seuils universels pour Lucene ou SQLite.

## 1. Validation fonctionnelle

Date de validation locale : **21 juillet 2026**.

### Build complet après le runner portefeuille

```text
mvn clean install
BUILD SUCCESS
117 fichiers source compilés
28 fichiers de test compilés
51 tests exécutés
0 échec
0 erreur
1 test ignoré
14,286 s
```

Le test ignoré est `LargeScaleSearchBaselineTest`, volontairement opt-in pendant le build standard.

La validation précédente avait également produit :

```text
SELF-SMOKE SUCCESS
```

Les avertissements SLF4J, native access, Vector API, Maven Shade et `sun.misc.Unsafe` observés restent non bloquants.

### Validation dédiée de la recherche fédérée

```text
scripts/validate-iteration-16.ps1 -FocusedOnly
BUILD SUCCESS
4 tests exécutés
0 échec
0 erreur
0 ignoré
```

Validation obtenue :

- recherche multi-projet : succès ;
- provenance `projectId` : validée par test ;
- corpus golden historique : succès ;
- corpus golden fédéré : succès ;
- aucun moteur externe introduit.

Baselines techniques conservées :

```text
mono-projet
precision@3 = 0,4444
recall@3    = 1,0000

fédéré technique
precision@3 = 0,4444
recall@3    = 1,0000
```

## 2. Palier 1 — repository NEXUS seul

Repository mesuré :

```text
N:\workspace-dev\nexus-context-engine
```

Requête de référence :

```text
SearchService
```

Résultats :

| Métrique | Valeur |
|---|---:|
| repositories | 1 |
| fichiers | 232 |
| symboles | 1 208 |
| relations | 9 684 |
| taille totale index Lucene | 795 968 octets |
| indexation complète | 2 194 ms |
| indexation incrémentale sans changement | 371 ms |
| recherche fédérée p50 | 140 ms |
| recherche fédérée p95 | 162 ms |
| construction contexte p50 | 257 ms |
| construction contexte p95 | 263 ms |
| heap utilisé avant | 28 888 896 octets |
| heap utilisé après | 117 693 064 octets |
| delta heap observé | 88 804 168 octets |

À ce volume, aucune métrique ne démontre que Lucene est insuffisant.

## 3. Palier 2 — portefeuille Java réel de quatre repositories

Commande :

```powershell
.\scripts\measure-iteration-16-portfolio.ps1
```

Portefeuille :

1. checkout NEXUS courant ;
2. `MediaUtilityTools` au commit `91e2f003a46a842e2d194fdc4bcf26e882c99c02` ;
3. `collection-manager` au commit `37ca800b2476db6f4bdc3e976afb78764ed05dda` ;
4. `db-toolkit-core` au commit `8cacc4e181e4bd9a36551b363b4d414c51f30eed`.

JARVIS et ses satellites ne font pas partie de ce portefeuille.

Le runner a terminé avec succès :

```text
LargeScaleSearchBaselineTest
1 test exécuté
0 échec
0 erreur
0 ignoré
BUILD SUCCESS
```

### Volumes et performances cumulés

| Métrique | Valeur |
|---|---:|
| repositories | 4 |
| requêtes | 5 |
| fichiers | 1 176 |
| symboles | 6 716 |
| relations | 13 820 |
| taille totale index Lucene | 3 457 736 octets |
| indexation complète cumulée | 7 312 ms |
| indexation incrémentale sans changement cumulée | 631 ms |
| recherche fédérée p50 | 92 ms |
| recherche fédérée p95 | 202 ms |
| construction contexte p50 | 52 ms |
| construction contexte p95 | 205 ms |
| heap utilisé avant | 43 069 040 octets |
| heap utilisé après | 230 396 544 octets |
| delta heap observé | 187 327 504 octets |

### Métriques par projet

| Projet | Fichiers | Symboles | Relations | Index Lucene | Indexation complète | Incrémental sans changement |
|---|---:|---:|---:|---:|---:|---:|
| NEXUS | 233 | 1 215 | 9 685 | 805 405 octets | 2 239 ms | 371 ms |
| MediaUtilityTools | 103 | 920 | 650 | 376 795 octets | 1 061 ms | 60 ms |
| collection-manager | 722 | 4 256 | 3 296 | 2 088 434 octets | 3 476 ms | 149 ms |
| db-toolkit-core | 118 | 325 | 189 | 187 102 octets | 536 ms | 51 ms |

### Latences par requête

| Requête | Recherche p50 | Recherche p95 | Contexte p50 | Contexte p95 |
|---|---:|---:|---:|---:|
| `SearchService` | 197 ms | 208 ms | 29 ms | 268 ms |
| `MediaFileNameController` | 92 ms | 96 ms | 52 ms | 124 ms |
| `FlywayMigrator` | 91 ms | 94 ms | 29 ms | 173 ms |
| `DatabaseMigrationManager` | 155 ms | 166 ms | 45 ms | 205 ms |
| `SqlScriptExecutor` | 85 ms | 88 ms | 40 ms | 120 ms |

## 4. Comparaison des deux paliers

Entre le palier NEXUS seul et le portefeuille de quatre repositories :

- le nombre de fichiers est multiplié par environ `5,07` ;
- le nombre de symboles est multiplié par environ `5,56` ;
- la taille cumulée des index Lucene est multipliée par environ `4,34` ;
- le delta heap observé est multiplié par environ `2,11` ;
- la recherche fédérée p95 passe de `162 ms` à `202 ms`, soit environ `+24,7 %` ;
- l'indexation complète cumulée reste inférieure à huit secondes ;
- l'indexation incrémentale sans changement reste inférieure à une seconde.

Les valeurs de contexte ne doivent pas être comparées comme un benchmark strict entre les deux paliers : le second run agrège cinq requêtes et vingt couples projet/requête, alors que le premier n'en mesurait qu'une.

La tendance observée ne montre pas de dégradation proportionnelle au volume. À ce palier, la fédération séquentielle et les index Lucene isolés restent compatibles avec l'objectif local-first.

## 5. Analyse du ranking réel

Le premier corpus portefeuille a produit :

```text
precision@3 moyenne = 0,3333
recall@3 moyenne    = 1,0000
```

Tous les chemins déclarés pertinents ont donc été retrouvés dans les trois premiers résultats.

La valeur `precision@3 = 0,3333` n'indiquait pas une dégradation du ranking : chaque requête ne déclarait initialement qu'un seul chemin pertinent, ce qui plafonne mécaniquement la précision à `1 / 3` même lorsque ce chemin est classé premier.

Le run a également révélé deux correspondances exactes légitimes qui n'étaient pas encore déclarées dans le corpus :

- `SearchService` existe à la fois dans NEXUS et dans `collection-manager` ;
- `FlywayMigrator` existe dans les packages `infra.migration` et `infra.config` de `collection-manager`.

Le manifest du corpus a été corrigé pour considérer ces chemins exacts comme pertinents. Une nouvelle exécution du runner doit enregistrer la qualité avec cette définition enrichie ; aucune modification des poids de ranking n'est justifiée par le run initial.

Signal inter-projets notable : pour la requête non qualifiée `SearchService`, le fichier de `collection-manager` est classé devant celui de NEXUS (`0,5650` contre `0,5585`). Les deux résultats sont exacts et pertinents. Ce cas confirme que les futures évaluations métier devront préciser la portée ou le projet attendu lorsqu'un nom de symbole est partagé.

## 6. Décision à ce palier

Le palier de quatre repositories ne justifie :

- ni Zoekt ;
- ni OpenGrok ;
- ni index distant ;
- ni parallélisation prématurée de la fédération ;
- ni changement des poids de ranking sur une seule mesure.

La recherche fédérée reste sous `p95 = 210 ms` sur les cinq requêtes mesurées, avec une taille cumulée d'index inférieure à 3,5 Mo.

## 7. Mesures encore nécessaires

Avant de considérer l'Itération 16 totalement close, il reste utile de mesurer :

1. le corpus portefeuille après enrichissement des chemins pertinents ;
2. une indexation incrémentale avec petit delta sur une copie contrôlée ;
3. un palier supérieur si des repositories réellement pertinents permettent d'atteindre cinq à dix projets sans duplication artificielle ;
4. la stabilité du ranking sur des requêtes métier ambiguës et qualifiées.

La décision d'évaluer Zoekt ou OpenGrok ne sera réouverte que si ces mesures montrent une limite reproductible que l'architecture locale ne peut pas corriger simplement.
