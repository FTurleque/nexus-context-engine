# Benchmark de régression scale

Ce benchmark complète les portfolios réels de l'Itération 16 avec un corpus **synthétique, hermétique et reproductible** destiné aux limites suivies historiquement par l'issue #23 et à la qualification de la recherche SQLite indexée suivie par #216.

Il ne remplace pas les baselines réelles I16 : il mesure spécifiquement les courbes qui ne peuvent pas être extrapolées de manière fiable depuis ~10k symboles et sept repositories.

Les résultats de calibration et décisions sont conservés dans [`scale-regression-results.md`](scale-regression-results.md).

## Objectifs

Le protocole mesure :

1. les requêtes SQLite ciblées et substring à 10k, 100k, 500k et 1M symboles/relations ;
2. la recherche et la construction de contexte fédérées à 10, 25, 50 et 100 projets ;
3. les lectures SQLite concurrentes avec des transactions d'écriture, en journal `DELETE` puis `WAL` ;
4. le coût d'un rebuild sémantique et d'un recovery après incompatibilité de provenance sans dépendre d'Ollama ;
5. les tailles de base/index, la mémoire JVM observée et les p50/p95.

Le benchmark ne contacte aucun repository distant et aucun service externe.

## Implémentation

Harness :

```text
core/src/test/java/com/nexus/benchmark/ScaleRegressionBenchmarkTest.java
```

Runner Windows :

```text
scripts/measure-scale-regression.ps1
```

Workflow GitHub :

```text
.github/workflows/scale-benchmark.yml
```

Version de protocole utilisée pour décider si une comparaison relative base/candidat est homogène :

```text
config/scale-benchmark-protocol
```

Rapport :

```text
target/scale-benchmark.json
```

Pour une pull request dont le commit de base déclare **la même version de protocole**, le workflow conserve aussi le rapport SQLite du commit de base :

```text
target/scale-benchmark-base.json
```

Le test JUnit est opt-in. Le workflow possède un timeout de job de 45 minutes et impose aussi un timeout JUnit de 20 minutes au test afin qu'une anomalie du scénario concurrent ne puisse pas bloquer indéfiniment la qualification.

## Profils

### `ci`

Profil court pour diagnostic rapide :

- SQLite : 10k et 100k symboles/relations ;
- portfolio : 10 et 25 projets ;
- concurrence : 25k symboles par projet ;
- sémantique : 5k documents.

### `full`

Profil de décision/régression #23/#216 :

- SQLite : 10k, 100k, 500k et 1M symboles/relations ;
- portfolio : 10, 25, 50 et 100 projets ;
- concurrence : 100k symboles par projet ;
- sémantique : 20k documents.

Les PR qui modifient le harness ou les zones de scale déclenchent le profil `full`. Le workflow peut aussi être déclenché manuellement avec le profil choisi.

## Exécution locale Windows

Depuis la racine :

```powershell
.\scripts\measure-scale-regression.ps1 -Profile full
```

Pour un diagnostic rapide :

```powershell
.\scripts\measure-scale-regression.ps1 -Profile ci
```

Le benchmark utilise un `NEXUS_HOME` et des repositories synthétiques temporaires JUnit. Il ne modifie pas les projets enregistrés de l'utilisateur.

## SQLite

### Requêtes mesurées

Pour chaque palier, le harness mesure :

- symbole exact : `BenchSymbol00000010` ;
- substring avec résultats : `ScaleNeedle` ;
- substring absent : `DefinitelyAbsentScaleToken` ;
- relation substring : `TargetNeedle` ;
- chargement ciblé de 100 chemins via `findFiles(projectId, paths)`.

Depuis la migration V008, `searchSymbols` et `searchRelations` n'exécutent plus `LOWER(...) LIKE '%...%'` sur la table canonique pour les requêtes de trois points de code Unicode ou plus. Deux tables FTS5 dérivées utilisent le tokenizer `trigram` :

```text
symbol_search_fts
relation_search_fts
```

Elles sont backfillées pendant la migration puis maintenues par triggers `INSERT` / `UPDATE` / `DELETE`. Le préfiltre fuzzy des symboles utilise en parallèle `idx_symbols_fuzzy_prefilter`. `SqliteIndexedSubstringSearchTest` qualifie la version SQLite embarquée, `ENABLE_FTS5`, le tokenizer trigram et le plan `VIRTUAL TABLE INDEX`.

Les requêtes de un ou deux points de code conservent volontairement le fallback `LIKE` afin de préserver la sémantique `contains`; un trigram ne peut pas indexer ces sous-chaînes. Ce cas très court est donc la limite résiduelle explicitement documentée.

Le benchmark enregistre population, p50/p95/mean/max et taille SQLite pour objectiver à la fois le gain de lecture et le coût d'écriture/stockage des index dérivés sur la pente 10k → 1M.

L'adoption FTS5/trigram modifie matériellement la composition de la base et le coût de population. Le marqueur `config/scale-benchmark-protocol` passe donc à **3** : les résultats du protocole 2 ne doivent pas servir de baseline relative au candidat protocole 3. Les plafonds absolus restent appliqués.

## DELETE vs WAL

La production ne force actuellement aucun `journal_mode`; SQLite utilise donc son mode par défaut, généralement `DELETE`.

Le benchmark **ne change pas la configuration de production**. Il crée deux bases isolées :

- `DELETE` ;
- `WAL`.

Dans chacune :

- un projet reçoit 40 transactions d'écriture ;
- un autre projet subit simultanément des recherches substring absentes ;
- toute erreur de lecture fait échouer le benchmark ;
- p95 lecture, durée totale writer et taille des fichiers SQLite sont enregistrés.

Règle de décision :

> ne proposer WAL que si plusieurs exécutions comparables montrent au moins **25 % d'amélioration du p95 lecteur**, sans régression significative writer/recovery.

Calibration #23 : amélioration reader p95 de **30,4 %** au premier run, mais seulement **0,3 %** au second. Le gain writer est important dans les deux runs, mais le critère lecteur n'est pas répétable.

**Décision : le mode de production reste inchangé ; WAL n'est pas adopté.**

## Portfolio fédéré

Chaque projet synthétique contient :

- une classe Java `SharedScaleNeedleService` ;
- un document Markdown associé.

Le benchmark indexe jusqu'à 100 projets avec la composition réelle `NexusApplication`, puis mesure :

- `searchAcrossProjects` ;
- `contextAcrossProjects` ;
- p50/p95 ;
- nombre de résultats et nombre de projets représentés.

Le protocole complète le palier réel I16 à sept repositories sans le remplacer.

## Rebuild sémantique / recovery

Le benchmark utilise un `EmbeddingProvider` déterministe local de 32 dimensions. Aucun Ollama n'est nécessaire.

Scénario :

1. rebuild avec fingerprint synthétique v1 ;
2. vérification de compatibilité ;
3. fingerprint v2 déclaré incompatible ;
4. rebuild complet de recovery ;
5. vérification de compatibilité v2.

Sont enregistrés :

- nombre de documents ;
- dimensions et batch size ;
- durée du rebuild initial ;
- durée du rebuild de recovery ;
- taille de l'index sémantique ;
- nombre total de vecteurs générés.

## Environnement et comparaison

Chaque rapport inclut :

- version/vendor Java ;
- OS et architecture ;
- nombre de processeurs disponibles ;
- heap maximal ;
- heap observé avant/après ;
- durée totale.

Les latences GitHub-hosted runners sont des mesures de régression, pas un benchmark matériel absolu. Une décision d'architecture doit comparer plusieurs runs du même protocole et tenir compte de l'environnement enregistré.

La population SQLite est particulièrement sensible au débit et à la contention du stockage du runner. Pour une pull request, le workflow compare donc le candidat au commit de base **sur le même runner uniquement lorsque les deux commits déclarent la même valeur dans `config/scale-benchmark-protocol`**. Cette version explicite signifie que le harness, la composition de production qu'il mesure et les hypothèses de calibration sont jugés comparables.

Si le marqueur est absent sur la base ou si sa version diffère, la comparaison relative est volontairement ignorée et le workflow l'annonce explicitement. Le candidat reste néanmoins soumis à tous les plafonds absolus de population, aux budgets p95 SQLite, aux budgets graph/fédération/native, aux limites de taille et aux limites mémoire. Une promotion historique ne peut donc pas être rejetée sur une comparaison de protocoles hétérogènes, sans pour autant désactiver les garde-fous de performance.

Toute modification qui rend les mesures de population non comparables avec la génération précédente doit incrémenter `config/scale-benchmark-protocol` dans le même changement.

## Budgets de régression

Les budgets historiques restent les plafonds absolus tant que la calibration du protocole 3 ne démontre pas qu'un plafond de stockage ou de population doit être ajusté explicitement. Ils ne sont pas relâchés préventivement pour l'introduction du trigram.

### SQLite p95

| Palier | Exact | Contains | Miss worst-case | Relation |
|---:|---:|---:|---:|---:|
| 10k | 50 ms | 30 ms | 30 ms | 25 ms |
| 100k | 250 ms | 150 ms | 150 ms | 100 ms |
| 500k | 1 000 ms | 600 ms | 600 ms | 400 ms |
| 1M | 2 000 ms | 1 200 ms | 1 200 ms | 800 ms |

Autres budgets SQLite :

- 100 fichiers ciblés : <= 30 ms p95 ;
- population sur PR **à protocole identique** : le candidat ne doit pas dépasser le maximum entre `base × 1,20` et `base + jitter`, avec des jitters de 0,2 s / 0,5 s / 1,5 s / 3 s selon le palier ;
- plafond de sûreté population, y compris lorsque la base n'est pas comparable : 1,4 s / 5 s / 20 s / 40 s selon le palier ;
- base 1M : <= 650 MiB.

La comparaison relative ne remplace pas le plafond absolu : une dérive extrême du candidat reste donc bloquée même sans baseline relative comparable. Les budgets p95 de requête restent strictement absolus.

Calibration NXA10 du gate relatif, sur un même runner et en alternance base/candidat à 100k :

- base : 1832 ms puis 1826 ms ;
- candidat : 1892 ms puis 1878 ms.

Cette calibration a confirmé un écart applicatif d'environ 3 %, alors que des exécutions isolées sur des runners hébergés différents avaient varié jusqu'à ~3,4 s. Le gate relatif vise précisément à séparer ces deux effets. NXA12 ajoute la condition de compatibilité de protocole afin que ce même mécanisme ne compare pas deux générations historiques matériellement différentes du système mesuré.

### Fédération

| Projets | Search p95 | Context p95 |
|---:|---:|
| 10 | 120 ms | 160 ms |
| 25 | 160 ms | 260 ms |
| 50 | 250 ms | 450 ms |
| 100 | 400 ms | 800 ms |

Indexation des 100 projets synthétiques : <= 6 s.

### Sémantique / ressources

- rebuild initial 20k : <= 12 s ;
- recovery incompatible 20k : <= 12 s ;
- index sémantique : <= 8 MiB ;
- delta heap observé fin de run : <= 256 MiB ;
- durée full : <= 180 s ;
- aucune erreur de lecture concurrente sous DELETE ou WAL.

Les budgets sont volontairement au-dessus des maxima de calibration pour absorber le bruit des runners, tout en détectant une régression algorithmique matérielle. Pour la population SQLite, cette marge est combinée à une comparaison base/candidat sur le même runner lorsque le protocole est identique.

## Décision FTS5 / trigram

Les mesures historiques du protocole 2 avaient matérialisé une pente linéaire : à 1M, les recherches exactes/substring approchaient les plafonds interactifs alors que les index B-tree ne pouvaient pas accélérer un motif à wildcard initial. L'audit du 10 septembre 2026 a donc transformé ce watch item en chantier #216.

**Décision #216 : adopter FTS5/trigram comme index secondaire local et dérivé pour les recherches substring >= 3 caractères.**

La décision reste bornée :

1. SQLite demeure la source canonique ; les tables FTS sont reconstructibles ;
2. aucun moteur externe ni service réseau n'est ajouté ;
3. la sémantique exact/contains/fuzzy et l'ordre déterministe restent du ressort du repository/ranking existant ;
4. les recherches très courtes gardent le fallback compatible ;
5. le coût write amplification / stockage doit rester sous les plafonds absolus du workflow `Scale Benchmark` ;
6. une évolution de la version SQLite doit continuer à passer la qualification FTS5/trigram dédiée.

Les chiffres du protocole 2 ci-dessous sont conservés uniquement comme historique pré-FTS et ne constituent plus une baseline relative homogène :

- 500k : exact ~675 ms, substring ~374 ms ;
- 1M : exact ~1,34–1,37 s, substring ~0,75–0,81 s ;
- portfolio 100 : search p95 <= 222 ms, contexte p95 <= 538 ms.

Le premier run `full` protocole 3 attaché à la PR d'adoption devient la preuve de qualification de la nouvelle composition. Les résultats chiffrés consolidés doivent être reportés dans [`scale-regression-results.md`](scale-regression-results.md) après qualification.

## Baseline réelle historique à conserver

Le corpus hermétique I16 de sept repositories reste la référence fonctionnelle réelle :

- 2 104 fichiers ;
- 10 878 symboles ;
- 10 087 relations ;
- indexation complète cumulée : 8 818 ms ;
- recherche fédérée p50/p95 : 133/304 ms ;
- contexte p50/p95 : 48/206 ms ;
- hit@3 : 1,0 ;
- MRR@3 : 1,0.

Voir [`iteration-16-extended-portfolio-results.md`](iteration-16-extended-portfolio-results.md) et [`scale-regression-results.md`](scale-regression-results.md).
