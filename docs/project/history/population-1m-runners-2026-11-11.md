# Peuplement du palier 1M selon le runner (2026-11-11)

Relevé figé de l'analyse du plafond de peuplement à 1 000 000 de symboles du gate scale, après l'échec du run de release `v0.2.0` du 2026-11-11 (run `42945695122`, rapport général du profil `full` à 40 755 ms pour un plafond de 40 000 ms). La règle qui en découle est décrite dans [scale-regression-benchmark.md](../../developer/scale-regression-benchmark.md#protocole-6).

## Sources

- Rapports généraux `full` (`sqliteOnly=false`, un peuplement par grand palier) : runs de release `42691808777` (`v0.2.0-rc.3`, 2026-11-09) et `42945695122` (`v0.2.0`, 2026-11-11).
- Courbes dédiées (`sqliteOnly=true`, médiane de trois peuplements) du candidat et de la base, mesurées sur le même runner : runs de PR `42864678217`, `42873101696`, `42905337786`, `42907072155`, `42913657254`, `42925036373` et `42925876238` (2026-11-10).
- Tous les runners : Linux amd64, 4 processeurs, Java 21.0.13 (Temurin), heap maximal de 4 Gio. Le rapport ne publie pas le modèle de processeur.

## Mesures

Population en millisecondes (médiane pour la courbe dédiée, mesure unique pour le rapport général) :

| Run | Rapport | 100k | 500k | 1M | 1M / 500k |
|---|---|---:|---:|---:|---:|
| 42864678217 | dédié candidat | 2 066 | 12 652 | 30 940 | 2,445 |
| 42864678217 | dédié base | 2 059 | 12 838 | 30 993 | 2,414 |
| 42873101696 | dédié candidat | 2 162 | 13 874 | 33 187 | 2,392 |
| 42873101696 | dédié base | 2 248 | 14 581 | 35 753 | 2,452 |
| 42905337786 | dédié candidat | 2 166 | 13 663 | 33 339 | 2,440 |
| 42905337786 | dédié base | 2 198 | 13 616 | 33 182 | 2,437 |
| 42907072155 | dédié candidat | 2 066 | 12 991 | 31 195 | 2,401 |
| 42907072155 | dédié base | 2 052 | 12 647 | 30 697 | 2,427 |
| 42913657254 | dédié candidat | 2 444 | 16 244 | 39 401 | 2,426 |
| 42913657254 | dédié base | 2 310 | 15 143 | 37 410 | 2,470 |
| 42925036373 | dédié candidat | 1 733 | 10 401 | 25 046 | 2,408 |
| 42925036373 | dédié base | 1 588 | 10 386 | 25 237 | 2,430 |
| 42925876238 | dédié candidat | 1 587 | 10 650 | 25 144 | 2,361 |
| 42925876238 | dédié base | 1 639 | 10 542 | 25 760 | 2,444 |
| 42691808777 | général `rc.3` | 2 642 | 15 608 | 38 435 | 2,463 |
| 42945695122 | général `v0.2.0` | 2 620 | 16 810 | 40 755 | 2,424 |

## Constats

- L'écart entre le rapport général (38 à 41 s) et une courbe dédiée « vers 31,5 s » ne vient pas du rapport : la courbe dédiée elle-même va de 25,0 à 39,4 s selon le run. Les deux releases ont tourné sur des runners lents ; leurs paliers 100k et 500k sont ralentis dans la même proportion que 1M.
- Dans un même run, les trois échantillons d'un palier restent à moins de 3 % les uns des autres à 1M. Une médiane de trois peuplements dans le rapport général n'aurait pas fait passer le run du 2026-11-11.
- Ordre des scénarios : dans les deux rapports, `sqliteTiers` est exécuté en premier, après les deux échauffements de 10k, dans l'ordre 10k, 100k, 500k, 1M ; `ScaleRegressionBenchmarkTest` produit son rapport avant les benchmarks graphe, fédération et découverte native du même job. Heap au départ : environ 77 Mo dans les deux releases. Taille de la base à 1M : 909 à 923 Mo, comparable à la courbe dédiée.
- Le rapport 1M / 500k d'un même rapport reste entre 2,36 et 2,47 sur les seize mesures, quel que soit le runner et le type de rapport.

## Décision

Le plafond de 1M devient `max(40 s, 2,6 × population 500k du même rapport)`. Le coefficient 2,6 laisse environ 5 % au-dessus du plus grand rapport observé ; le plafond de 500k (20 s) borne le plafond de 1M à 52 s. Avec cette règle, les seize rapports passent et le run du 2026-11-11 est admis (plafond 43 706 ms).
