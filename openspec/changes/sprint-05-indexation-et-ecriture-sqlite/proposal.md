# Proposal

## Why

L'écriture d'un index NEXUS paie des coûts qui croissent avec la base plutôt qu'avec le changement. Le `DELETE FROM symbol_relations WHERE file_id = ? AND source_provider = ?` de `SqliteFileWriter`, comme la cascade de clé étrangère depuis `indexed_files`, parcourt toute la table `symbol_relations`, tous projets confondus : aucun de ses six index ne commence par `file_id`. Trois index sont des préfixes stricts d'autres index et alourdissent chaque écriture sans servir de plan. Chaque fichier écrit prépare quatre instructions et relit son identifiant par un `SELECT` séparé. Les analyses de tous les fichiers modifiés restent en mémoire jusqu'à l'écriture SQLite finale, et l'index Lucene ouvre un `IndexWriter` et committe (fsync) à chaque lot de 128 fichiers. Chaque indexation, même sans changement, hache deux fois tous les fichiers du projet.

L'écriture d'intelligence de code MINOS est orchestrée par la façade et la CLI, hors de `ProjectIndexingService`, avec un verrouillage différent ; et `ProjectIndexingService` expose cinq constructeurs publics dont deux retombent silencieusement sur un verrou process-local. Ces classes sont aussi celles que réutilise la fédération (sprint 6) : elles se corrigent avant.

## What Changes

- Un index `symbol_relations(file_id, source_provider)` sert la suppression par fichier et la cascade de clé étrangère ; un test de plan le garantit, un scénario de benchmark couvre la réindexation sur base peuplée (AUD-PRF-01).
- Les analyses (symboles, relations) ne s'accumulent plus pour l'ensemble des fichiers modifiés : leur rétention est bornée par lot, la génération restant publiée à la fin (AUD-PRF-12).
- La part du double hachage est mesurée sur le corpus du benchmark scanner ; un raccourci par métadonnées n'est introduit que si la mesure le justifie, avec un ADR qui complète ADR-0023 (AUD-PRF-08).
- L'index Lucene lexical et l'index sémantique gardent un `IndexWriter` pour la durée d'une indexation et ne committent qu'à la barrière de publication (AUD-PRF-11).
- `SqliteFileWriter` prépare ses instructions une fois par transaction et récupère l'identifiant du fichier par `RETURNING id` (AUD-PRF-14).
- Les index préfixes redondants (`idx_symbol_relations_project`, `idx_indexed_files_project`, `idx_symbols_file`) sont supprimés après vérification des plans (AUD-PRF-15).
- L'import MINOS passe par `ProjectIndexingService` (même verrouillage single-flight et inter-processus, mêmes diagnostics) ; `MinosCodeIndexImporter` est composé dans `NexusApplicationFactory` et la lecture bornée du payload passe par la façade (AUD-ARC-10).
- `ProjectIndexingService` n'expose qu'un constructeur public complet, qui exige le gestionnaire de verrou ; les variantes de commodité vivent dans un support de test (AUD-ARC-13).

Constats du sprint déjà fermés : aucun (vérifiés sur `origin/develop` à `17a00866`).

## Capabilities

### New Capabilities
- `sqlite-index-writes` : index SQLite et chemin d'écriture des faits d'indexation (plans de requête, index utiles, préparation des instructions, rétention des analyses).
- `project-indexing-pipeline` : orchestration de l'indexation d'un projet (point d'entrée unique d'écriture, verrouillage, cycle de vie des writers Lucene, coût du scan).

### Modified Capabilities

(aucune : `openspec/specs/` est vide)

## Impact

- Code : `core/src/main/java/com/nexus/persistence/sqlite/SqliteFileWriter.java`, `SqliteBatchInsert.java`, `core/src/main/java/com/nexus/index/ProjectIndexingService.java`, `IndexDocumentPublisher.java`, `core/src/main/java/com/nexus/index/scan/ProjectScanner.java`, `core/src/main/java/com/nexus/search/lucene/LuceneSearchIndex.java`, `core/src/main/java/com/nexus/search/semantic/LuceneSemanticSearchIndex.java`, `core/src/main/java/com/nexus/application/NexusApplication.java`, `NexusApplicationFactory.java`, `core/src/main/java/com/nexus/cli/NexusCli.java`.
- Schéma : deux migrations d'évolution `Vnnn` (création de l'index par fichier, suppression des index préfixes), numéros suivants disponibles au moment de l'implémentation ; aucune invalidation d'index dérivés.
- Tests : `SqliteGraphQueryPlanTest`, `ScaleRegressionBenchmarkTest`, `ProjectScannerCorpusBudgetBenchmarkTest`, `ProjectIndexingServiceTest` et les 18 classes de test qui construisent `ProjectIndexingService`, tests MINOS.
- Documentation : `docs/developer/indexing-pipeline.md`, `docs/developer/sqlite-write-contention.md`, `docs/developer/release-and-recovery.md`.
- Comportement observable : aucun changement de contrat CLI/REST/MCP ; l'import MINOS refuse une indexation concurrente du même projet comme les autres écritures.
