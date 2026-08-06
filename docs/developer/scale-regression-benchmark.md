# Benchmark de régression scale

Ce benchmark complète les portfolios réels de l'Itération 16 avec un corpus **synthétique, hermétique et reproductible** destiné aux limites suivies par l'issue #23.

Il ne remplace pas les baselines réelles I16 : il mesure spécifiquement les courbes qui ne peuvent pas être extrapolées de manière fiable depuis ~10k symboles et sept repositories.

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
src/test/java/com/nexus/benchmark/ScaleRegressionBenchmarkTest.java
```

Runner Windows :

```text
scripts/measure-scale-regression.ps1
```

Workflow GitHub :

```text
.github/workflows/scale-benchmark.yml
```

Rapport :

```text
target/scale-benchmark.json
```

## Profils

### `ci`

Profil court pour diagnostic rapide :

- SQLite : 10k et 100k symboles/relations ;
- portfolio : 10 et 25 projets ;
- concurrence : 25k symboles par projet ;
- sémantique : 5k documents.

### `full`

Profil de décision #23 :

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
- substring absent, pire cas de scan : `DefinitelyAbsentScaleToken` ;
- relation substring : `TargetNeedle` ;
- chargement ciblé de 100 chemins via `findFiles(projectId, paths)`.

Les requêtes de production `searchSymbols` / `searchRelations` utilisent actuellement `LOWER(...) LIKE '%...%'`. Les index B-tree existants restent utiles pour d'autres accès mais ne suppriment pas le coût du pire cas substring.

Le benchmark enregistre population, p50/p95/mean/max et taille SQLite pour objectiver la pente 10k → 1M.

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

Règle de décision initiale :

> ne proposer WAL que si plusieurs exécutions comparables montrent au moins **25 % d'amélioration du p95 lecteur**, sans régression significative writer/recovery.

Une seule exécution favorable ne suffit pas à modifier `SqliteDatabase`.

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

## Budgets de régression

Les budgets définitifs de #23 **ne sont pas inventés avant mesure**.

Processus :

1. exécuter le profil `full` sur le head de la PR #23 ;
2. récupérer `scale-benchmark.json` ;
3. observer la pente et la variance ;
4. fixer des budgets explicites avec marge pour le bruit des runners ;
5. réexécuter le même head avec les budgets ;
6. seulement ensuite décider si WAL, FTS5, trigram ou une autre complexité est justifiée.

Aucun nouveau backend n'est introduit uniquement parce que la requête SQL est théoriquement O(n) : il faut une limite mesurée sur les corpus cibles.

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

Voir [`iteration-16-extended-portfolio-results.md`](iteration-16-extended-portfolio-results.md).
