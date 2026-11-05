# Résultats du benchmark de régression scale — #23

Ce document conserve les mesures de calibration obtenues avec le profil `full` du benchmark hermétique de l'issue #23.

Les deux exécutions ci-dessous ont utilisé le même head de calibration :

```text
f4dd558b642fd29d3ebce27bb05a8e5118b9541b
```

Environnement déclaré par les rapports :

```text
OS               Linux amd64
Java             Eclipse Adoptium 21.0.11
CPU disponibles  4
Heap JVM max     4 194 304 000 octets
```

Les GitHub-hosted runners ne sont pas du matériel de benchmark fixe. Ces chiffres sont donc des **mesures de régression sur protocole identique**, pas des performances matérielles absolues.

## 1. SQLite — courbe 10k → 1M

### p95, run 1 / run 2

| Symboles + relations | Exact symbole | Contains symbole | Miss worst-case | Contains relation | 100 fichiers ciblés |
|---:|---:|---:|---:|---:|---:|
| 10k | 15,5 / 22,2 ms | 7,4 / 7,4 ms | 8,0 / 13,5 ms | 5,3 / 8,1 ms | 9,1 / 8,0 ms |
| 100k | 134,9 / 133,4 ms | 76,8 / 74,7 ms | 73,8 / 73,8 ms | 46,9 / 47,2 ms | 9,9 / 9,6 ms |
| 500k | 672,5 / 674,5 ms | 373,6 / 373,8 ms | 372,7 / 371,6 ms | 231,2 / 239,3 ms | 2,5 / 2,5 ms |
| 1M | 1 338,8 / 1 372,8 ms | 755,4 / 809,4 ms | 746,3 / 780,1 ms | 472,0 / 457,0 ms | 2,5 / 2,8 ms |

La croissance des recherches substring est approximativement linéaire avec la taille du corpus, ce qui correspond au plan SQL courant utilisant `LOWER(...) LIKE '%...%'`.

Le lookup ciblé `findFiles(projectId, paths)` reste, lui, indépendant de cette pente et inférieur à 10 ms sur les deux runs.

### Population et taille SQLite

| Palier | Population run 1 / run 2 | Taille DB run 1 |
|---:|---:|---:|
| 10k | 237 / 346 ms | 5,33 MiB |
| 100k | 1 227 / 1 252 ms | 53,49 MiB |
| 500k | 6 095 / 6 135 ms | 268,77 MiB |
| 1M | 13 143 / 13 057 ms | 537,94 MiB |

La taille est proche de la linéarité et reste sous 550 MiB pour 1M symboles + 1M relations dans ce fixture.

## 2. Fédération — 10 → 100 projets

### p95, run 1 / run 2

| Projets | Recherche fédérée | Context fédéré |
|---:|---:|---:|
| 10 | 58,3 / 54,7 ms | 77,8 / 85,3 ms |
| 25 | 81,7 / 75,1 ms | 141,0 / 168,6 ms |
| 50 | 139,0 / 108,3 ms | 247,4 / 254,9 ms |
| 100 | 221,8 / 173,7 ms | 411,7 / 537,7 ms |

Le portfolio synthétique de 100 projets reste sous 250 ms p95 pour la recherche et sous 550 ms p95 pour le contexte sur les deux runs de calibration.

Le top-20 représentatif conserve 20 projets à partir du palier 25, conformément à la taille de résultat demandée ; le palier 10 représente les 10 projets.

Indexation complète des 100 projets :

```text
run 1  3 027 ms
run 2  3 233 ms
```

## 3. DELETE vs WAL — lecture concurrente

Corpus : deux projets de 100k symboles ; 40 transactions d'écriture sur un projet pendant des recherches worst-miss sur l'autre.

| Mesure | Run 1 DELETE | Run 1 WAL | Run 2 DELETE | Run 2 WAL |
|---|---:|---:|---:|---:|
| Reader p95 | 104,4 ms | 72,6 ms | 73,6 ms | 73,4 ms |
| Writer total | 225 ms | 39 ms | 139 ms | 36 ms |
| Erreurs reader | 0 | 0 | 0 | 0 |

Amélioration reader p95 :

```text
run 1  30,4 %
run 2   0,3 %
```

### Décision WAL

**WAL n'est pas adopté dans la configuration de production.**

La règle préalable demandait une amélioration reader p95 répétée d'au moins 25 % sans régression writer/recovery. Le premier run franchit ce seuil, le second non. Le gain writer est net et reproductible, mais le bénéfice recherché côté lecture concurrente ne l'est pas.

Le mode SQLite courant reste donc inchangé. WAL pourra être réévalué si une charge réelle démontre un problème de contention write/read.

## 4. Recovery sémantique

Fixture : 20 000 documents, vecteurs déterministes 32 dimensions, batch 128, aucun réseau/Ollama.

| Mesure | Run 1 | Run 2 |
|---|---:|---:|
| Rebuild initial | 6 181 ms | 6 634 ms |
| Rebuild après provenance incompatible | 5 777 ms | 5 916 ms |
| Taille index | 4 243 648 octets | 4 243 648 octets |
| Vecteurs produits | 40 000 | 40 000 |

Le recovery complet après mismatch de provenance reste inférieur à 6 s dans les deux runs.

## 5. Mémoire et durée globale

| Mesure | Run 1 | Run 2 |
|---|---:|---:|
| Heap observé avant | 72,7 MiB env. | 72,5 MiB env. |
| Delta heap observé en fin de run | +60,3 MiB env. | +48,3 MiB env. |
| Durée totale | 89 111 ms | 90 976 ms |

La mesure heap est une observation de début/fin, pas un pic RSS. La taille des bases et de l'index sémantique constitue l'indicateur de stockage reproductible principal.

## 6. Budgets de régression retenus

Le workflow `Scale Benchmark` impose désormais des plafonds au-dessus des maxima observés afin d'absorber le bruit des runners tout en détectant une régression algorithmique matérielle.

### SQLite p95

| Palier | Exact | Contains | Miss | Relation |
|---:|---:|---:|---:|---:|
| 10k | 50 ms | 30 ms | 30 ms | 25 ms |
| 100k | 250 ms | 150 ms | 150 ms | 100 ms |
| 500k | 1 000 ms | 600 ms | 600 ms | 400 ms |
| 1M | 2 000 ms | 1 200 ms | 1 200 ms | 800 ms |

Autres limites :

- lookup ciblé 100 fichiers : <= 30 ms p95 ;
- population 1M : <= 20 s ;
- base 1M : <= 650 MiB.

### Fédération

| Projets | Recherche p95 max | Context p95 max |
|---:|---:|---:|
| 10 | 120 ms | 160 ms |
| 25 | 160 ms | 260 ms |
| 50 | 250 ms | 450 ms |
| 100 | 400 ms | 800 ms |

Indexation synthétique des 100 projets : <= 6 s.

### Sémantique et ressources

- rebuild initial 20k : <= 12 s ;
- recovery 20k : <= 12 s ;
- index sémantique : <= 8 MiB ;
- delta heap fin de run : <= 256 MiB ;
- durée totale full : <= 180 s.

## 7. Décision FTS5 / trigram / nouveau moteur

**Aucun FTS5, trigram ni moteur de recherche supplémentaire n'est introduit dans #23.**

Justification :

- le corpus réel I16 est proche de 10k symboles, où les p95 SQLite mesurés sont de l'ordre de 5–22 ms ;
- à 100k, les p95 restent sous ~135 ms ;
- la pente devient visible à 500k–1M, mais le pire cas 1M reste sous les budgets de régression retenus ;
- la fédération à 100 projets reste sous 550 ms p95 pour le contexte dans les runs de calibration ;
- introduire FTS/trigram aujourd'hui augmenterait migrations, maintenance d'index et recovery sans répondre à un SLO actuellement violé sur les corpus cibles.

### Déclencheurs de réexamen

Réouvrir la décision si l'un des faits suivants devient réel et reproductible :

1. corpus utilisateur courant >= 500k symboles avec p95 interactif jugé insuffisant ;
2. 1M symboles dépasse 2 s p95 pour la recherche exacte ou 1,2 s pour substring ;
3. portfolio 100 projets dépasse 400 ms p95 recherche ou 800 ms p95 contexte sur protocole comparable ;
4. benchmark réel montre un SLO utilisateur plus strict que les budgets synthétiques ;
5. une optimisation SQL plus simple ne suffit plus.

La prochaine étape, si un déclencheur est franchi, doit comparer d'abord une stratégie SQL/FTS ciblée contre cette baseline avant d'adopter un moteur externe.

## 8. Référence fonctionnelle réelle

La baseline I16 reste complémentaire :

```text
7 repositories
2 104 fichiers
10 878 symboles
10 087 relations
recherche fédérée p50/p95 133/304 ms
contexte p50/p95 48/206 ms
hit@3 1.0
MRR@3 1.0
```

Les fixtures synthétiques de #23 servent à détecter la pente et les limites ; elles ne remplacent pas la mesure sur des repositories représentatifs.
