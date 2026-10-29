# Tasks

Commande de test ciblé du cœur : `./mvnw -B -ntp -pl core test -Dtest=<Classe> -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`.

## 1. Suppression par fichier indexée

- [ ] 1.1 AUD-PRF-01 — Ajouter dans `SqliteGraphQueryPlanTest` deux tests `EXPLAIN QUERY PLAN` (suppression `DELETE FROM symbol_relations WHERE file_id = ? AND source_provider = ?` et sonde de cascade `SELECT 1 FROM symbol_relations WHERE file_id = ?`) qui exigent un `SEARCH` par index ; vérifier qu'ils échouent sur le schéma courant par `-Dtest=SqliteGraphQueryPlanTest`
- [ ] 1.2 AUD-PRF-01 — Ajouter la migration suivante `Vnnn__index_symbol_relations_by_file.sql` (skill `nexus-schema-migration`) qui crée `idx_symbol_relations_file_provider ON symbol_relations(file_id, source_provider)`, avec commentaire SQL en français et test d'upgrade ; vérifier par `-Dtest=SqliteGraphQueryPlanTest,SchemaMigrator*`
- [ ] 1.3 AUD-PRF-01 — Ajouter à `ScaleRegressionBenchmarkTest` un scénario « réindexation de fichiers existants » qui passe par `applyChanges` sur une base peuplée (incrémental et rebuild) ; vérifier par `./mvnw -B -ntp -pl core test -Dtest=ScaleRegressionBenchmarkTest -Dnexus.scale.benchmark.enabled=true -Dnexus.scale.benchmark.profile=ci -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`

## 2. Rétention des analyses

- [ ] 2.1 AUD-PRF-12 — Mesurer le pic de heap d'un rebuild complet sur le profil `full` du benchmark d'échelle (design D2) et consigner la mesure ; vérifier par le même benchmark avec `-Dnexus.scale.benchmark.profile=full`
- [ ] 2.2 AUD-PRF-12 — Faire écrire les analyses (`IndexedFileUpdate`) au rythme des flushs de `IndexDocumentPublisher` dans la transaction d'indexation, la génération restant publiée à la fin ; ajouter un test qui vérifie qu'un échec en cours d'indexation laisse la génération précédente intacte ; vérifier par `-Dtest=ProjectIndexingServiceTest,IndexDocumentPublisher*` puis le scénario de contention de `docs/developer/sqlite-write-contention.md`

## 3. Double hachage

- [ ] 3.1 AUD-PRF-08 — Faire rapporter par le benchmark scanner la part du hachage dans le scan initial et dans le scan final ; vérifier par le workflow `scanner-corpus-benchmark.yml` (déclenchement manuel) ou `-Dtest=ProjectScannerCorpusBudgetBenchmarkTest` avec ses propriétés d'activation
- [ ] 3.2 AUD-PRF-08 — Selon la mesure : soit consigner le résultat dans `docs/developer/indexing-pipeline.md` sans changement de code, soit introduire le raccourci par taille et date de modification avec un nouvel ADR (skill `nexus-adr`) qui complète ADR-0023 et fixe le seuil ; vérifier par `bash .claude/scripts/contracts.sh` et `-Dtest=ProjectScannerTest,ProjectIndexingServiceTest`

## 4. Writers Lucene

- [ ] 4.1 AUD-PRF-11 — Garder un `IndexWriter` par indexation dans `LuceneSearchIndex` et `LuceneSemanticSearchIndex`, alimenté à chaque flush, committé à la barrière de publication et annulé (`rollback`) en cas d'échec (design D4) ; ajouter un test qui compte un seul commit pour une indexation de plus de 128 fichiers et un test d'échec qui conserve l'index précédent ; vérifier par `-Dtest=LuceneSearchIndexTest,LuceneSemanticSearchIndex*,ProjectIndexingServiceTest`

## 5. Écriture par fichier

- [ ] 5.1 AUD-PRF-14 — Préparer une fois par transaction les instructions de `SqliteFileWriter`, remplacer le `SELECT id` qui suit l'upsert par `RETURNING id`, mettre en cache par taille l'instruction de fin de lot de `SqliteBatchInsert` ; vérifier par `-Dtest=Sqlite*` et `git grep -n "SELECT id FROM indexed_files WHERE project_id = ? AND relative_path = ?" core/src/main/java/com/nexus/persistence/sqlite/SqliteFileWriter.java` sans résultat

## 6. Index redondants

- [ ] 6.1 AUD-PRF-15 — Ajouter dans `SqliteGraphQueryPlanTest` les plans des requêtes servies aujourd'hui par `idx_symbol_relations_project`, `idx_indexed_files_project` et `idx_symbols_file`, et vérifier qu'un index plus large les sert une fois ces index supprimés ; puis ajouter la migration suivante `Vnnn__drop_redundant_prefix_indexes.sql` (`DROP INDEX IF EXISTS`) et son test d'upgrade ; vérifier par `-Dtest=SqliteGraphQueryPlanTest,SchemaMigrator*` et le benchmark d'échelle (profil `ci`)
- [ ] 6.2 AUD-PRF-01, AUD-PRF-15 — Documenter les deux migrations dans `docs/developer/release-and-recovery.md` (aucun rebuild imposé) ; vérifier par `bash .claude/scripts/contracts.sh`

## 7. Import MINOS

- [ ] 7.1 AUD-ARC-10 — Déplacer l'import MINOS de `NexusApplication` vers `ProjectIndexingService` (`LockSlot` single-flight puis verrou inter-processus, mêmes diagnostics), composer `MinosCodeIndexImporter` dans `NexusApplicationFactory`, exposer la lecture bornée du payload par la façade et la faire utiliser par `NexusCli` ; ajouter un test d'import MINOS concurrent d'une indexation du même projet ; vérifier par `-Dtest=Minos*,ProjectIndexingServiceTest,NexusCli*` et `git grep -n "MinosCodeIndexImporter" core/src/main/java/com/nexus/cli/ core/src/main/java/com/nexus/application/NexusApplication.java` sans résultat

## 8. Construction de ProjectIndexingService

- [ ] 8.1 AUD-ARC-13 — Réduire `ProjectIndexingService` à un constructeur public complet sans valeur par défaut de `ProjectIndexLockManager`, déplacer les variantes de commodité dans une fabrique de test et migrer les classes de test qui les utilisent ; vérifier par `git grep -c "public ProjectIndexingService(" core/src/main/java/com/nexus/index/ProjectIndexingService.java` égal à 1 et `./mvnw -B -ntp -pl core test -Djacoco.skip=true`

## 9. Validation

- [ ] 9.1 Mettre à jour `docs/developer/indexing-pipeline.md` (writer Lucene par indexation, import MINOS, écriture par lots) et valider l'itération (`/nx-validate full` : contrats, `./mvnw -B -ntp clean install`, `scripts/self-smoke.ps1`) ; faire relire par l'agent `nexus-reviewer`

## Workflow follow-up

- Ouvrir la PR vers `develop` (skill `nexus-pr-workflow`), merge exact-head en merge commit ; fusionner avant le sprint 6, qui réutilise `ProjectIndexingService` et `LuceneSearchIndex`.
- Archiver le changement OpenSpec une fois la PR fusionnée.
