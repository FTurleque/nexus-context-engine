# Recherche approchée d'un projet seul et pagination de la mise en attente (2026-11-10)

Relevé figé du changement `openspec/changes/sprint-06-suite-performance-sqlite` (tâches 1.4, 2.1, 2.2, 3.3 ; constats AUD-PRF-29 et AUD-PRF-27). Il consigne la mesure qui tranche la question ouverte de AUD-PRF-29 et la décision prise. Les conclusions durables sont dans [large-scale-search.md](../../developer/large-scale-search.md#sqlite-et-symboles), [sqlite-write-contention.md](../../developer/sqlite-write-contention.md#openspec-sqlite-index-writes) et [scale-regression-benchmark.md](../../developer/scale-regression-benchmark.md).

## Environnement et réserves

- Poste : Windows 10 Pro (10.0.19045), AMD Ryzen 7 5700G (8 cœurs, 16 fils), 48 Gio de mémoire, base SQLite sur disque local, journal `DELETE`.
- Java 21.0.12.1 (Microsoft), heap maximal de 12 Gio.
- Pilote : sqlite-jdbc 3.54.1.0 (propriété `sqlite-jdbc.version` du `pom.xml`), sans `ANALYZE`.
- Harnais : `ScaleRegressionBenchmarkTest`, profil `full`, scénarios sélectionnés par `-Dnexus.scale.benchmark.scenarios`. Une exécution par configuration ; 2 échauffements et 20 échantillons par requête. Le poste exécutait d'autres sessions de travail pendant les relevés : les durées absolues ne se comparent pas à un runner CI.
- Exécution « avant » : 2026-11-10, 07:48 UTC, schéma V015 (code de `develop` pour la recherche, harnais du changement). Exécution « après » : 2026-11-10, 07:57 UTC, schéma V017 et choix d'index de ce changement.

## 1. Recherche approchée d'un projet seul (`singleProjectFuzzy`)

Corpus : un seul projet dans la base, 100 000 fichiers, 5 symboles par fichier (500 000 symboles), noms répartis sur 26 premiers caractères et 20 longueurs. Termes : `parseconfigfile`, `parse`, `config`, `file`, `renderer`, `loadindex`, `graphnode`, `tokenize`, `mergerank` (requête entière et 8 termes, comme une recherche de symboles). Candidats par terme : 1 924 à 6 734, sous le seuil de 10 000 du parcours ordonné : la récupération emprunte le bras de l'index du préfiltre.

Requêtes du préfiltre, index forcé, en microsecondes (p50 / p95) :

| Exécution | Index | Comptage plafonné | Récupération |
|---|---|---:|---:|
| avant | scopé par fichier `idx_symbols_file_fuzzy_prefilter` (V015) | 422 501 / 894 683 | 775 163 / 1 235 893 |
| avant | global `idx_symbols_fuzzy_prefilter` (schéma d'avant V015) | 186 414 / 304 477 | 138 817 / 159 358 |
| après | scopé par fichier (V015) | 396 182 / 450 532 | 454 214 / 507 937 |
| après | global (V017) | 48 936 / 54 030 | 71 623 / 81 025 |

Rapport scopé sur global (médianes) : avant, comptage ×2,27 et récupération ×5,58 ; après, ×8,10 et ×6,34. Les plans relevés : scopé, `SEARCH f USING COVERING INDEX idx_indexed_files_hash (project_id=?)` puis `SEARCH s USING COVERING INDEX idx_symbols_file_fuzzy_prefilter (file_id=? AND …)`, un sondage par fichier du projet ; global, `SEARCH s USING INDEX idx_symbols_fuzzy_prefilter (<expr>=? AND <expr>>? AND <expr><?)` puis `SEARCH f USING INTEGER PRIMARY KEY (rowid=?)`. Les deux index rendent les mêmes candidats et les mêmes lignes (vérifié par le scénario).

Recherche de production (`IndexRepository.searchSymbols` des 9 termes : plein texte trigram, comptage et récupération du préfiltre) :

| Exécution | p50 | p95 | moyenne |
|---|---:|---:|---:|
| avant (V015, index scopé) | 1 038,8 ms | 1 277,5 ms | 1 094,4 ms |
| après (V017, index global choisi) | 287,4 ms | 374,3 ms | 302,0 ms |

## 2. Décision AUD-PRF-29

Règle fixée par le mainteneur : corriger si la recherche avec V015 est plus de 2 fois plus lente que sur le schéma d'avant V015. Le comptage est 2,27 fois et la récupération 5,58 fois plus lents : la correction est appliquée.

Correction retenue : choix du parcours selon la part du projet dans la base. La migration V017 recrée l'index global `idx_symbols_fuzzy_prefilter` à côté de l'index scopé ; `SqliteSymbolSearch` force l'index global quand le projet ciblé possède au moins la moitié des fichiers indexés (`PROJECT_FILE_SHARE_SQL`), l'index scopé sinon. Un projet minoritaire garde un coût indépendant des autres projets (gain fédéré de V015, `SqliteSymbolSearchScopingTest` à 25 et 100 projets) ; un projet seul retrouve l'intervalle de l'index global. L'autre option, un index réordonné unique, aurait fait dépendre le coût d'un petit projet du nombre de candidats de toute la base. Coût de la correction : un index d'expression de plus maintenu à chaque écriture de symbole, et une requête de deux comptages de fichiers par recherche approchée, comprise dans la mesure de production ci-dessus mais non mesurée séparément.

Après correction, la recherche de production d'un projet seul de 100 000 fichiers passe de 1 039 à 287 ms (p50). Le scénario `singleProjectFuzzy` reste dans le benchmark comme garde.

## 3. Pagination de la mise en attente (`stagedPublication`, AUD-PRF-27)

Indexation complète d'un projet neuf de 100 000 fichiers (un symbole et une relation par fichier) par une session d'écriture, lots de 128 fichiers :

| Exécution | Mise en attente | Publication | Total |
|---|---:|---:|---:|
| avant (V015, sans `idx_index_staged_files_project_id`) | 9 746 ms | 33 513 ms | 43 836 ms |
| après (V016) | 9 447 ms | 15 312 ms | 25 192 ms |

La publication tient le verrou d'écriture SQLite d'un seul tenant ; V016 en réduit la durée de 54 % sur ce corpus. Le plan de la page (`SqliteGraphQueryPlanTest#stagedFilesPageSeeksTheProjectAndPaginationKeyWithoutTemporarySort`) est `SEARCH index_staged_files USING INDEX idx_index_staged_files_project_id (project_id=? AND id>?)`, sans `USE TEMP B-TREE`.

## 4. Réindexation sur base peuplée par session (`reindexExistingFiles`)

Après le rebranchement du scénario sur la session d'écriture (base de 100 000 symboles et relations, 1 000 fichiers), exécution « après » : réindexation de 50 fichiers, session 846 ms dont publication 799 ms (médianes) ; rebuild des 1 000 fichiers, session 12 168 ms dont publication 11 449 ms. Aucune mesure « avant » comparable : le scénario mesurait un autre chemin d'écriture.

## 5. Reproduction

```bash
./mvnw -B -ntp -pl core test -Dtest=ScaleRegressionBenchmarkTest -Dnexus.scale.benchmark.enabled=true -Dnexus.scale.benchmark.profile=full -Dnexus.scale.benchmark.scenarios=singleProjectFuzzy,stagedPublication,reindexExistingFiles -Dnexus.scale.benchmark.output=target/scale-benchmark.json -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true
```

La mesure « avant » s'obtient avec le même harnais sur un code dont le schéma s'arrête à V015.
