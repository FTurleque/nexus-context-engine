# Proposition

## Why

Le sprint 6 de l'audit a scopé la recherche de symboles et mis en attente les analyses d'indexation. La revérification du 09/11 relève quatre suites sur ce chemin : la publication du staging pagine sans index adapté et tient le verrou d'écriture SQLite plusieurs secondes au plafond de 100 000 fichiers ; la migration V015, qui scope le préfiltre approché, ralentit un gros projet seul dans sa base ; les benchmarks de réindexation mesurent `applyChanges`, que la production n'appelle plus ; et la clé du vecteur de requête partagé ignore le texte de la requête.

Source : `docs/project/audits/2026-10-17/findings.json` (sprint 6, constats ajoutés les 07/11 et 09/11), `etat-2026-11-09.md`.

## What Changes

- `AUD-PRF-27` (Moyenne)
  - [Existant vérifié] `SqliteFileWriter.STAGED_FILES_PAGE_SQL` pagine `WHERE project_id = ? AND id > ? ORDER BY id LIMIT ?` (`core/src/main/java/com/nexus/persistence/sqlite/SqliteFileWriter.java:56-63`) dans la transaction de publication ; le seul index de `index_staged_files` est `UNIQUE (project_id, relative_path)` (`core/src/main/resources/db/migration/V014__index_staged_analyses.sql:10-22`). Aucun `ANALYZE` en production.
  - [Existant vérifié] Plan relevé par l'audit (SQLite 3.45.1) : `USING INDEX sqlite_autoindex_index_staged_files_1 (project_id=?)` puis `USE TEMP B-TREE FOR ORDER BY` ; pagination en mémoire 10 000 fichiers 0,06 s, 40 000 fichiers 0,91 s. Plan non vérifié sur sqlite-jdbc 3.54.1.0.
  - [Exigence] La page de staging se lit par un index, sans tri temporaire.
- `AUD-PRF-29` (Moyenne)
  - [Existant vérifié] V015 remplace l'index de préfiltre approché global par `idx_symbols_file_fuzzy_prefilter (file_id, …)` (`core/src/main/resources/db/migration/V015__index_symbols_by_file_for_fuzzy_search.sql:12-15`) ; `FUZZY_BATCH_SQL` et `FUZZY_COUNT_ARM_SQL` le forcent par `INDEXED BY` (`core/src/main/java/com/nexus/persistence/sqlite/SqliteSymbolSearch.java:155-166`, `:185-194`).
  - [Existant vérifié] Mesure de l'audit (SQLite 3.45.1, en mémoire, un projet, 100 000 fichiers) : comptage 0,097 → 0,256 s, récupération 0,099 → 0,358 s entre V014 et V015. Aucun benchmark mono-projet ne compare les deux.
  - [Exigence] Un scénario mono-projet du benchmark d'échelle mesure la recherche approchée.
  - [Proposition] La correction (index supplémentaire ou choix du parcours selon la part du projet dans la base) se décide sur la mesure ; voir [Question].
- `AUD-PRF-28`
  - [Existant vérifié] `IndexRepository.applyChanges` (`core/src/main/java/com/nexus/index/IndexRepository.java:62`) n'a aucun appelant de production ; `ScaleRegressionBenchmarkTest.java:243`, `:248` et `IndexingHeapPeakBenchmarkTest.java:220` le mesurent ; 20 fichiers de test l'appellent.
  - [Écart] `project-indexing-pipeline › La réindexation sur base peuplée est mesurée` exige une mesure « par le chemin d'écriture de l'indexation » ; le benchmark mesure un chemin que l'indexation n'emprunte plus.
  - [Exigence] Le port n'expose que la session d'écriture ; les benchmarks mesurent `openWriteSession` → `stage` → `publish`.
- `AUD-QUA-23`
  - [Existant vérifié] `SemanticSearchStrategy.queryVectorKey()` vaut `semantic-query-vector|<provider>|<modèle>` (`core/src/main/java/com/nexus/search/semantic/SemanticSearchStrategy.java:188-190`) ; `SearchQueryContext.shared` rend la première valeur calculée pour une clé (`core/src/main/java/com/nexus/search/SearchQueryContext.java:59-71`). Chaque contexte ne sert aujourd'hui qu'à une requête (`FederatedSearchService.java:80`, `FederatedContextService.java:148`).
  - [Exigence] Un vecteur partagé n'est rendu que pour le même texte de requête.

## Type de changement

fonctionnel (schéma SQLite, port d'indexation, recherche sémantique) : profil complet.

## Périmètre et exclusions

**Dans le périmètre :**
- Une migration SQLite d'index pour `index_staged_files` et le test de plan associé.
- Un scénario mono-projet de recherche approchée dans `ScaleRegressionBenchmarkTest`, et la correction que sa mesure justifie.
- Le retrait de `applyChanges` du port `IndexRepository` et de `SqliteIndexRepository`, un support de test équivalent, le rebranchement des benchmarks.
- La clé du vecteur de requête partagé.

**Hors périmètre :**
- `ANALYZE` ou `PRAGMA optimize` en production : changerait les plans de toutes les requêtes ; non demandé par l'audit.
- Les `applyChanges` des index dérivés (`SearchIndexSession`, `SemanticIndexing.Session`) : chemin de production actif (`IndexPublication.java:44`, `:49`).
- La réécriture de la recherche approchée au-delà de ce que la mesure de `AUD-PRF-29` justifie.

## Capabilities

### New Capabilities

### Modified Capabilities
- `sqlite-index-writes`: ajout d'une exigence sur le plan de la page de staging.
- `symbol-search-scoping`: ajout d'une exigence de mesure mono-projet de la recherche approchée.
- `project-indexing-pipeline`: ajout d'une exigence (le port n'écrit que par session) ; réécriture de « La réindexation sur base peuplée est mesurée » pour nommer la session.
- `federated-search-bounds`: ajout d'une exigence sur la clé du vecteur de requête partagé.

## Impact

- Schéma : nouvelle migration `core/src/main/resources/db/migration/V016__index_staged_files_by_project.sql` (numéro suivant libre à l'implémentation ; `sprint-08-suite-borne-sk` ajoute aussi une migration).
- Code : `SqliteFileWriter`, `SqliteIndexRepository`, `IndexRepository`, `SqliteSymbolSearch` (selon la mesure), `SemanticSearchStrategy`, `SearchQueryContext` (Javadoc).
- Tests : `SqliteGraphQueryPlanTest`, `SchemaMigratorChecksumTest`, un test d'upgrade V015 → V016, `ScaleRegressionBenchmarkTest`, `IndexingHeapPeakBenchmarkTest`, les 20 tests qui appellent `applyChanges`, `SemanticSearchStrategyTest` ou équivalent.
- Documentation : `docs/developer/sqlite-write-contention.md`, `docs/developer/large-scale-search.md`, `docs/developer/scale-regression-benchmark.md`, `docs/developer/indexing-pipeline.md`, `docs/developer/release-and-recovery.md` (section de mise à niveau V016), `docs/developer/openspec-traceability.md`.

## Questions ouvertes

- [Question] `AUD-PRF-29` : quel écart entre V014 et V015 sur un projet seul de 100 000 fichiers justifie une correction ? Aucun seuil n'est sourcé ; le changement propose de trancher sur la mesure de la tâche 2.1 (sqlite-jdbc 3.54.1.0, profil `full`) et de consigner la décision dans `docs/project/history/`.
