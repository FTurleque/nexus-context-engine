# Mesures après mise en attente des analyses et writers Lucene par indexation (2026-11-07)

Relevé figé du sprint 5 (`openspec/changes/sprint-05-indexation-et-ecriture-sqlite`, tâches 2.2 et 4.1, constats AUD-PRF-12 et AUD-PRF-11). Il fait suite aux [mesures avant modification](indexing-heap-and-hashing-measures-2026-11-07.md) et documente le résultat de la décision DATA-0062 (table de mise en attente V014, un writer Lucene par indexation). Les conclusions durables sont dans [le pipeline d'indexation](../../developer/indexing-pipeline.md#9-coûts-mesurés-du-pipeline).

## Environnement et réserves

Même poste que le relevé précédent : Windows 10 Pro, Java 21.0.12.1, 16 CPU, heap maximal de 12 Gio. Le poste était chargé par d'autres lots du sprint pendant les relevés : les durées absolues ne sont comparables ni à un runner ni entre elles. Une exécution par configuration.

## 1. Heap vivant d'un rebuild (`IndexingHeapPeakBenchmarkTest`, profil `ci`)

| Mesure | Avant (relevé précédent) | Après, 2 000 fichiers | Après, 6 000 fichiers |
|---|---:|---:|---:|
| Pic de heap vivant | 22,3 MiB | 18,1 MiB | 33,8 MiB |
| Croissance vivante au-dessus du départ | 14,2 MiB | 9,7 MiB | 25,4 MiB |
| Croissance vivante par fichier | 7 457 octets | 5 079 octets | 4 446 octets |
| Pic de heap occupé (échantillonné) | 294,2 MiB | 110,4 MiB | 140,0 MiB |
| Durée d'indexation | 94,6 s | 85,5 s | 306,9 s |

Lecture :

- À 2 000 fichiers, la croissance vivante par fichier baisse d'environ 32 % et le pic de heap occupé de 294 à 110 MiB.
- La croissance vivante **reste proportionnelle au nombre de fichiers** (9,7 MiB pour 2 000 fichiers, 25,4 MiB pour 6 000, soit environ 4,4 à 5 Kio par fichier) : la mise en attente borne la rétention des *analyses*, pas celle de tout le pipeline. Le résidu n'a pas été attribué par mesure ; les structures qui restent de l'ordre du nombre de fichiers sont les listes du scan initial et du scan final (`ScannedFile`), la table des fichiers existants et l'ensemble des chemins, que la tâche ne bornait pas. Une mesure à 20 000 fichiers (profil `full`) n'a pas été relancée : les valeurs ci-dessus ne permettent pas de conclure que la rétention est désormais indépendante du nombre de fichiers.
- Le profil `ci` à 6 000 fichiers a été obtenu avec `-Dnexus.scale.benchmark.indexing.files=6000`.

## 2. Transaction d'écriture

La transaction de publication (`SqliteIndexWriteSession.publish`) applique les analyses aux tables canoniques en une transaction ; la mesure `singleWriteTransactionMs` du benchmark reste celle d'un `applyChanges` unique des mêmes volumes (1,4 s à 2 000 fichiers, 6,2 s à 6 000, soit 1,6 % et 2,0 % de la durée d'indexation). Les transactions de mise en attente sont courtes (un lot de 128 fichiers au plus) ; le scénario de contention de [sqlite-write-contention.md](../../developer/sqlite-write-contention.md) vérifie qu'un autre projet écrit entre deux lots (`ProjectIndexingPublicationTest`).

## 3. Commits Lucene

`ProjectIndexingPublicationTest#indexingMoreThanOneDefaultBatchCommitsEachLuceneIndexOnce` indexe 300 fichiers (plus de deux lots de 128) : chaque index, lexical et sémantique, est au commit de génération 1, soit un seul commit au lieu d'un par lot.

## 4. Reproduction

```powershell
./mvnw -B -ntp -pl core test -Dtest=IndexingHeapPeakBenchmarkTest -Dnexus.scale.benchmark.enabled=true -Dnexus.scale.benchmark.profile=ci -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true
./mvnw -B -ntp -pl core test -Dtest=IndexingHeapPeakBenchmarkTest -Dnexus.scale.benchmark.enabled=true -Dnexus.scale.benchmark.profile=ci -Dnexus.scale.benchmark.indexing.files=6000 -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true
```
