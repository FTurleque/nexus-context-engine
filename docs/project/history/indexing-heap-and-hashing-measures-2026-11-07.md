# Mesures du pipeline d'indexation : heap, transaction d'écriture et hachage (2026-11-07)

Relevé figé du sprint 5 (`openspec/changes/sprint-05-indexation-et-ecriture-sqlite`, tâches 2.1 et 3.1/3.2, constats AUD-PRF-12 et AUD-PRF-08). Les mesures ont été prises **avant** toute modification du pipeline : `ProjectIndexingService`, `IndexDocumentPublisher` et `ProjectScanner` sont ceux du head `ae4179cc`. Les conclusions durables sont dans [le pipeline d'indexation](../../developer/indexing-pipeline.md#9-coûts-mesurés-du-pipeline).

## Environnement et réserves

```text
OS               Windows 10 Pro (poste de développement)
Java             Microsoft OpenJDK 21.0.12.1
CPU disponibles  16
Heap JVM max     12 859 736 064 octets (défaut de la JVM de test)
```

- La machine était **chargée** : plusieurs lots d'implémentation du sprint 5 compilaient et testaient en parallèle dans d'autres worktrees pendant les relevés. Les durées absolues ne sont pas comparables entre elles ni à un runner ; seuls le heap vivant et les rapports entre mesures d'un même run sont exploités.
- Ce n'est pas le runner de référence (`ubuntu-24.04`). Sous Windows, l'ouverture d'un fichier par `SafeFileIO` coûte plusieurs millisecondes ; ce coût domine tout ce qui suit.
- Les relevés sont des mesures ponctuelles : une exécution par configuration (sauf les trois tours du scanner), sans répétition de calibration.

## 1. Pic de heap d'un rebuild complet

Benchmark : `IndexingHeapPeakBenchmarkTest` (nouveau, opt-in par `nexus.scale.benchmark.enabled`). Il génère un projet Java synthétique (13 symboles et 4 imports par fichier, environ 1,8 Kio par fichier), l'enregistre dans un `NEXUS_HOME` temporaire et lance `index(projectId, rebuild=true)`. Le « heap vivant » est le heap occupé juste après une collection forcée toutes les 2 s ; le « heap occupé » est le maximum échantillonné toutes les 10 ms, déchets non collectés compris.

| Mesure | Profil `full` | Profil `ci` |
|---|---:|---:|
| Fichiers modifiés | 20 000 | 2 000 |
| Symboles / relations écrits | 260 000 / 80 000 | 26 000 / 8 000 |
| Heap de départ (après collection) | 8,1 MiB | 8,1 MiB |
| Pic de heap vivant | 111,9 MiB | 22,3 MiB |
| Croissance vivante au-dessus du départ | 103,8 MiB | 14,2 MiB |
| Croissance vivante par fichier | 5 441 octets | 7 457 octets |
| Pic de heap occupé (échantillonné) | 278,1 MiB | 294,2 MiB |
| Part du heap maximal (vivant / occupé) | 0,9 % / 2,3 % | 0,2 % / 2,4 % |
| Durée d'indexation | 1 073 s | 94,6 s |

Le profil `full` a été exécuté avec une première version du benchmark qui ne relevait que le heap ; le profil `ci` avec la version courante, qui relève en plus le scan et la transaction d'écriture (§ 2 et 3). Le code mesuré du pipeline est identique.

Lecture :

- Le heap vivant croît avec le nombre de fichiers modifiés (les `IndexedFileUpdate` de tous les fichiers sont retenus jusqu'à l'écriture SQLite), de l'ordre de 5 à 7 Kio par fichier pour ce fixture. La valeur par fichier du profil `ci` inclut une part fixe (Lucene, caches) qui pèse davantage sur un petit corpus.
- Extrapolation linéaire, à prendre comme un ordre de grandeur pour des fichiers de cette taille : environ 0,5 Gio à 100 000 fichiers (plafond par défaut de `NEXUS_MAX_INDEX_FILES`, qui compte aussi les répertoires) et plusieurs Gio au plafond dur de 1 000 000. Des fichiers à plus de symboles coûtent proportionnellement plus.
- Avec 12 Gio de heap maximal, aucun des deux profils n'est proche de la saturation : la rétention est un enjeu de dimensionnement du heap pour les très gros dépôts, pas un défaut observé sur ce corpus.

## 2. Transaction d'écriture finale et durée d'indexation

Mesure (profil `ci`, 2 000 fichiers) : après l'indexation, les mêmes volumes (13 symboles et 4 relations par fichier) sont réappliqués par `SqliteIndexRepository.applyChanges` en **une** transaction, soit ce que le pipeline fait aujourd'hui en fin d'analyse. La base contient déjà ces fichiers : la mesure inclut donc la suppression des anciennes lignes et surestime une première écriture.

| Mesure | Valeur |
|---|---:|
| Transaction d'écriture unique | 4 513 ms |
| Durée d'indexation | 94 574 ms |
| Part de la transaction dans l'indexation | 4,8 % |

Si les analyses étaient écrites dans la transaction d'indexation au rythme des flushs, la transaction resterait ouverte pendant toute l'indexation : 100 % de la durée au lieu de 4,8 %, soit environ 21 fois plus longtemps. La politique de reprise d'écriture est de 5 s de `busy_timeout` et deux tentatives ; une indexation de 94 s (ou de 1 073 s en profil `full`) ferait échouer en `SQLITE_BUSY` tout autre écrivain, y compris l'indexation d'un autre projet.

## 3. Part du hachage dans le scan

### 3.1 Scanner seul : `ProjectScannerCorpusBudgetBenchmarkTest`

Le benchmark enchaîne trois tours (scan complet, puis hachage seul des mêmes fichiers avec la même lecture sans suivi de lien et la même borne). Le premier tour est le scan initial, le dernier le scan final. La part est `hashPassMs / scanMs`.

Profil `full`, 10 000 fichiers de 44 octets :

| Scan | Durée du scan | Hachage seul | Part |
|---|---:|---:|---:|
| initial | 46 528 ms | 37 380 ms | 80,3 % |
| final | 45 677 ms | 37 219 ms | 81,5 % |

Profil `full`, 5 000 fichiers de 16 Kio :

| Scan | Durée du scan | Hachage seul | Part |
|---|---:|---:|---:|
| initial | 65 037 ms | 22 695 ms | 34,9 % |
| final | 27 383 ms | 22 297 ms | 81,4 % |

Profil `ci` (2 000 fichiers de 44 octets, 1 000 fichiers de 16 Kio) : parts de 79,9 % (initial) et 81,6 % (final) pour 44 octets, de 35,7 % (initial) et 86,5 % (final) pour 16 Kio.

Lecture :

- Coût de hachage par fichier : 3,7 ms pour 44 octets, 4,5 ms pour 16 Kio. Il est quasi indépendant de la taille : il vient de l'ouverture du fichier, pas du calcul SHA-256.
- La part « initial » de 35 % sur 16 Kio n'est pas comparable aux autres : les fichiers venaient d'être écrits et le scan paie un premier accès à froid (13 ms par fichier, contre 5,5 ms au tour suivant) que le hachage mesuré ensuite ne paie pas. La part du scan **final** (fichiers chauds) est la représentative.
- Hors hachage, un scan coûte environ 1 ms par fichier (parcours, attributs, garde de chemin).
- La première mesure `scanMs` de 10 000 fichiers (58,6 s) dépasse le budget de 30 s du workflow, calibré pour `ubuntu-24.04` ; elle ne reflète que ce poste.

### 3.2 Dans une indexation complète (profil `ci`, 2 000 fichiers)

Un scan complet du projet synthétique du § 1, mesuré après l'indexation :

| Mesure | Valeur |
|---|---:|
| Scan complet | 20 773 ms |
| Hachage seul | 20 326 ms (97,8 % du scan) |
| Deux scans (initial et final) dans la durée d'indexation | 43,9 % |
| Deux hachages dans la durée d'indexation | 43,0 % |

Ce run a eu la charge la plus élevée des relevés (environ 10 ms par ouverture de fichier contre 4 ms ci-dessus). Un rebuild relit en outre chaque fichier modifié une troisième fois (lecture des octets puis hachage en mémoire par `IndexDocumentPublisher`).

## 4. Décisions

- **Design D2** : la mesure du § 2 écarte l'écriture des analyses dans la transaction d'indexation unique (verrou d'écriture tenu pendant toute l'indexation). La mesure du § 1 confirme une rétention linéaire en nombre de fichiers. L'alternative retenue est la table de staging, alimentée par de courtes transactions au rythme des flushs, vidée par la transaction finale (migration dédiée et ADR complétant ADR-0051 et ADR-0052).
- **Design D3** : aucun raccourci par taille et date de modification, aucun nouvel ADR. Le coût est celui de l'ouverture des fichiers sur un poste Windows chargé, pas celui de SHA-256 ; le raccourci altérerait la détection de mutation du scan final, et son seuil doit être fixé par une mesure du workflow `scanner-corpus-benchmark.yml` (déclenchement manuel, profil `full`) sur `ubuntu-24.04`, non réalisée ici.

## 5. Reproduction

```powershell
./mvnw -B -ntp -pl core test -Dtest=IndexingHeapPeakBenchmarkTest -Dnexus.scale.benchmark.enabled=true -Dnexus.scale.benchmark.profile=full -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true
./mvnw -B -ntp -pl core test -Dtest=ProjectScannerCorpusBudgetBenchmarkTest -Dnexus.scale.benchmark.enabled=true -Dnexus.scale.benchmark.profile=full -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true
```
