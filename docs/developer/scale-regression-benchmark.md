# Benchmark de régression scale

Ce benchmark complète les portfolios réels de l'Itération 16 avec un corpus **synthétique, hermétique et reproductible**. Il couvre les limites historiques de #23 et la qualification de la recherche SQLite indexée de #216. Il ne remplace pas les baselines réelles I16.

## Harness et rapports

Harness principal :

```text
core/src/test/java/com/nexus/benchmark/ScaleRegressionBenchmarkTest.java
```

Workflow :

```text
.github/workflows/scale-benchmark.yml
```

Version du protocole :

```text
config/scale-benchmark-protocol
```

Le rapport général est écrit dans `target/scale-benchmark.json`. Sur une pull request scale-sensitive exécutée avec le profil général `ci`, le workflow lance en plus `ScaleRegressionBenchmarkTest` avec le profil `full` et conserve `target/scale-benchmark-sqlite-full.json` comme preuve dédiée de la courbe SQLite 10k / 100k / 500k / 1M. Le reste des gates graph, fédération, concurrence et sémantique reste sur le profil PR `ci`; une qualification `full` globale reste disponible par déclenchement explicite.

Cette séparation évite qu'un changement SQLite soit confondu avec une dérive indépendante du graphe à 1M, tout en imposant réellement les quatre paliers demandés par #216.

Pour une pull request dont la base déclare **la même version de protocole**, le workflow peut aussi conserver `target/scale-benchmark-base.json` et comparer la population base/candidat sur le même runner. Si le protocole diffère, cette comparaison relative est ignorée, mais tous les plafonds absolus du candidat restent obligatoires.

Le job possède un timeout de 45 minutes et les tests scale un timeout JUnit de 20 minutes.

## Profils

### `ci`

Profil général des pull requests :

- SQLite général : 10k et 100k ;
- portfolio : 10 et 25 projets ;
- concurrence : 25k symboles par projet ;
- sémantique : 5k documents ;
- graphe : 100k symboles/relations.

En complément, une PR scale-sensitive exécute la courbe SQLite dédiée en `full` : 10k, 100k, 500k et 1M symboles/relations.

### `full`

Profil global de décision/régression :

- SQLite : 10k, 100k, 500k et 1M ;
- portfolio : 10, 25, 50 et 100 projets ;
- concurrence : 100k symboles par projet ;
- sémantique : 20k documents ;
- graphe : 1M symboles/relations.

Exécution locale Windows :

```powershell
.\scripts\measure-scale-regression.ps1 -Profile full
```

## Qualification SQLite #216

Pour chaque palier, `ScaleRegressionBenchmarkTest` mesure :

- symbole exact : `BenchSymbol00000010` ;
- substring présent : `ScaleNeedle` ;
- substring absent : `DefinitelyAbsentScaleToken` ;
- relation substring : `TargetNeedle` ;
- chargement ciblé de 100 chemins via `findFiles(projectId, paths)` ;
- population et taille des fichiers SQLite.

Depuis V008, les recherches de trois points de code Unicode ou plus passent par deux projections dérivées FTS5/trigram :

```text
symbol_search_fts
relation_search_fts
```

Les projections sont **contentless**, utilisent `detail=none` et `columnsize=0`. Elles ne dupliquent donc ni le contenu canonique, ni les positions, ni la table FTS `docsize`; elles conservent uniquement les posting lists nécessaires à la découverte de candidats. Les valeurs de référence restent dans `symbols` et `symbol_relations`.

Les mutations canoniques sont placées par triggers dans `symbol_search_pending` / `relation_search_pending`. Chaque entrée pending mémorise le premier texte ancien lorsqu'une suppression de tokens est nécessaire ; le texte à insérer est relu depuis les tables canoniques au flush. Les suppressions sont appliquées avec la commande FTS5 spéciale `delete`, puis les insertions sont projetées par `INSERT ... SELECT`. Le repository regroupe les écritures canoniques par lots SQL bornés de 128 lignes. Le benchmark vide le lot SQL résiduel toutes les 5 000 lignes et commit toutes les 50 000 lignes ; la projection FTS est mise à jour au bump de `project_index_generations`.

Le repository transforme une requête en intersection de trigrams de trois points de code. Comme `detail=none` ne conserve pas les positions, les candidats FTS sont ensuite revalidés contre les valeurs canoniques avec la sémantique `contains`. Cette étape élimine les faux positifs possibles d'une simple intersection de trigrams sans réintroduire un scan complet de la table.

Le préfiltre fuzzy reste compatible avec la sémantique historique (premier caractère + longueur ±3), mais son jeu de candidats est borné **avant** son union avec les candidats FTS. `idx_symbols_fuzzy_prefilter` est ordonné pour permettre cette sélection déterministe sans matérialiser toute une famille de noms à forte cardinalité.

Les requêtes de un ou deux points de code gardent volontairement le fallback `LIKE`, car un trigram ne peut pas indexer ces chaînes.

`SqliteIndexedSubstringSearchTest` qualifie explicitement :

- SQLite embarqué >= 3.34 ;
- `ENABLE_FTS5` ;
- tokenizer `trigram` ;
- projection `content=''`, `detail=none`, `columnsize=0` ;
- commande FTS5 `delete` avec l'ancien texte ;
- plan `VIRTUAL TABLE INDEX` ;
- flush de génération ;
- maintien de 5 000 lignes en attente jusqu'au flush de génération ;
- fallback des requêtes très courtes.

Le scénario de recovery vérifie qu'une ancienne table `schema_migrations` sans `script_sha256` est complétée additivement avant la reprise du migrateur. Les FTS sont dérivés : une reconstruction commence par `delete-all` puis les reconstruit depuis les tables canoniques.

## Protocole 4

Le regroupement des écritures canoniques en lots SQL bornés complète l'adoption FTS5/trigram. `config/scale-benchmark-protocol` vaut donc **4**. Une mesure protocole 3 ou antérieure ne doit pas être utilisée comme baseline relative homogène du protocole 4.

Les plafonds absolus de latence et de population restent inchangés. Le stockage est comparé à la base de la PR sur le même runner.

### Budgets SQLite p95

| Palier | Exact | Contains | Miss | Relation |
|---:|---:|---:|---:|---:|
| 10k | 50 ms | 30 ms | 30 ms | 25 ms |
| 100k | 250 ms | 150 ms | 150 ms | 100 ms |
| 500k | 1 000 ms | 600 ms | 600 ms | 400 ms |
| 1M | 2 000 ms | 1 200 ms | 1 200 ms | 800 ms |

Autres plafonds SQLite :

- lookup de 100 fichiers : <= 30 ms p95 ;
- population : <= 1,4 s / 5 s / 20 s / 40 s aux paliers 10k / 100k / 500k / 1M ;
- base à 1M : taille candidat <= taille de la base de la PR × 1,20, mesurées sur le même runner ;
- lorsque base et candidat partagent le même protocole, population candidat <= `max(base × 1,20, base + jitter)` avec jitters 0,2 / 0,5 / 1,5 / 3 s.

Le gate dédié PR exige exactement les quatre paliers SQLite dans `scale-benchmark-sqlite-full.json`.

## Autres scénarios scale

Le benchmark général conserve les scénarios #23 : recherche/contexte fédérés, comparaison DELETE/WAL, recovery sémantique déterministe et mesure de graphe. Le mode SQLite de production n'est pas changé : WAL n'est adopté que si des mesures répétées montrent un gain reader p95 >=25 % sans régression writer/recovery.

Les budgets graph/fédération/sémantique restent ceux du workflow. Une anomalie d'un de ces domaines doit être traitée dans son chantier propre et ne doit pas être masquée par une modification des plafonds SQLite.

## Décision #216

FTS5/trigram est retenu comme index secondaire **local, dérivé et reconstructible** pour les recherches substring >=3 caractères. SQLite (`symbols`, `symbol_relations`) reste l'autorité canonique. Aucun moteur externe ni service réseau n'est ajouté.

La qualification de #216 n'est acquise que si le HEAD exact passe le reactor Linux/Windows, les tests de migration/replay, CodeQL/OSV/SonarCloud et la courbe SQLite 10k→1M avec les plafonds absolus ci-dessus.

Les résultats historiques du protocole 2 restent disponibles dans [`scale-regression-results.md`](scale-regression-results.md) comme référence pré-FTS, et non comme baseline relative homogène.
