---
id: DATA-0074
title: "Garantir le cycle de vie et la cohérence des index dérivés : verrous par projet, writers par opération, readers en cache, publication atomique"
domain: data
rules: true
---

# Garantir le cycle de vie et la cohérence des index dérivés : verrous par projet, writers par opération, readers en cache, publication atomique

- **Statut** : Accepted
- **Date** : 2026-11-08
- **Origine** : consolidation du registre ADR (audit du 2026-11-08)
- **Complète** : [DATA-0073](DATA-0073-tenir-sqlite-pour-source-de-verite-et-lucene-pour-index-derive.md)
- **Remplace** : [DATA-0045](archive/DATA-0045-conserver-les-writers-operation-scoped-et-mettre-en-cache-les-readers-lucene.md), [DATA-0052](archive/DATA-0052-garantir-la-coherence-des-lectures-et-des-index-derives.md), [DATA-0055](archive/DATA-0055-preserver-les-lecteurs-lors-des-reconstructions-semantiques.md), [DATA-0062](archive/DATA-0062-mettre-en-attente-les-analyses-d-indexation-dans-sqlite.md)
- **Remplacé par** : —

## Context

SQLite est la source de vérité et les index Lucene lexical et sémantique sont des dérivés reconstructibles ([DATA-0073](DATA-0073-tenir-sqlite-pour-source-de-verite-et-lucene-pour-index-derive.md)). Leur cycle de vie pose quatre problèmes liés :

- **Coût des lectures.** Rouvrir les readers à chaque recherche est coûteux : des readers persistants gagnent de 79 à 92 % sur le p95 de recherche (benchmark ABBA de la PR #159, Linux et Windows). Mais un `IndexWriter` gardé ouvert par un processus REST ou MCP tiendrait le `write.lock` Lucene pendant toute sa vie, alors que deux processus partageant un `NEXUS_HOME` doivent pouvoir muter le même projet l'un après l'autre.
- **Lectures pendant un rebuild.** Une lecture qui vérifie `READY` puis ouvre Lucene sans coordination peut traverser un rebuild et observer un index partiellement remplacé ; un dérivé perdu, altéré ou construit avec une autre politique de redaction peut être pris pour un index valide.
- **Rebuild avec des lecteurs ouverts.** Purger les fichiers avant un rebuild échoue sous Windows (segments mappés par une autre instance) et peut, sous Linux, laisser un lecteur servir l'ancien corpus. Un commit corrompu ne se rouvre pas en mode `CREATE`.
- **Rétention et commits d'une indexation.** Retenir les analyses de tous les fichiers modifiés jusqu'à l'écriture SQLite fait croître le heap linéairement (5 à 7 Kio par fichier) ; committer Lucene à chaque lot multiplie les fsync. Une transaction SQLite ouverte pendant toute l'indexation multiplierait par environ 21 le temps de verrou d'écriture, au-delà de la politique de reprise (`busy_timeout` de 5 s, deux tentatives).

Question : comment servir des lectures rapides et cohérentes, reconstruire et publier les index dérivés sans exposer d'état partiel, sans verrou propriétaire de longue durée et sans transaction d'écriture SQLite longue ?

## Options envisagées

Cycle de vie Lucene :

- **Tout operation-scoped** : simple et isolé, mais coût d'ouverture des readers confirmé comme matériel.
- **Readers et writers persistants** : meilleur résultat brut, mais le writer garde le verrou Lucene et concurrence le verrou NEXUS par projet. Rejetée.
- **Readers persistants bornés, writers operation-scoped** : cible le gain mesuré, préserve le verrouillage inter-processus, s'appuie sur `SearcherManager`. Retenue.
- **Service de recherche externe** : contraire au local-first. Rejetée.

Rebuild sémantique : purge préalable (invalide les lecteurs, rejetée) ; répertoires versionnés pour tout rebuild (politique de collecte inutile sur la voie normale, rejetée) ; mode `CREATE` en place, et répertoire distinct publié atomiquement pour les seuls commits illisibles (retenue).

Écriture d'une indexation : transaction SQLite unique pendant toute l'indexation (verrou trop long, rejetée) ; faits canoniques écrits par courtes transactions au fil des lots (faits partiels visibles en cas d'échec, rejetée) ; table de mise en attente alimentée par courtes transactions et vidée par la transaction finale (retenue).

## Decision

**Options retenues : verrous par projet partagés entre lectures et mutations ; readers Lucene en cache borné et writers operation-scoped ; rebuild sémantique sans purge avec publication atomique de récupération ; analyses mises en attente dans SQLite et publiées avec un commit Lucene unique.**

### Verrous par projet et lectures cohérentes

Les mutations et les lectures d'un projet partagent une identité de verrou par chemin de lock (`ProjectIndexLockManager`), autorité de sérialisation des mutations inter-processus. Une mutation prend le verrou d'écriture inter-processus ; une lecture prend un verrou partagé inter-processus et le conserve pendant toute la façade de recherche, de contexte ou d'inspection. Les lecteurs d'un même processus mutualisent le `FileLock` partagé. Les lectures fédérées acquièrent leurs verrous dans l'ordre global des UUID. `READY` n'est pas présenté comme un snapshot atomique du filesystem vivant.

Chaque commit Lucene porte la version de la politique de redaction. `isPresent` vérifie l'existence, l'intégrité minimale et cette version avant d'autoriser une indexation incrémentale. Le profil sémantique inclut la version de la redaction ; un dérivé ancien ou absent force une reconstruction.

### Readers en cache, writers par opération

Les implémentations de production mettent en cache, par projet chaud, un `SearcherManager` et son `Directory` ; les implémentations operation-scoped restent la baseline et le repli. Le `SearcherManager` est rafraîchi avant chaque recherche servie par le cache, pour observer un commit local ou externe, et après une mutation locale. Un rebuild invalide le reader du projet avant la reconstruction. Le cache ferme et remplace son lecteur quand un même UUID désigne un autre répertoire. Le cache est borné à 100 projets par index, cardinalité maximale d'une portée fédérée ; au-delà, la recherche retombe sur l'implémentation operation-scoped sans accumuler de handles.

Les writers restent operation-scoped : ouverts le temps d'une indexation, d'un `applyChanges` ou d'un `rebuild`, puis fermés, jamais partagés entre indexations ni projets ; aucune classe du cache de readers ne référence `IndexWriter`.

La persistance des readers est activée uniquement par la composition `NexusApplication.createLongLived(...)`, destinée aux processus REST et MCP ; `NexusApplication.create(...)` reste operation-scoped pour la CLI, les commandes one-shot et les tests. La façade longue durée est `AutoCloseable` et possède ses index : REST la ferme à la destruction du bean, MCP dans son shutdown hook.

### Rebuild sémantique et récupération

Un rebuild normal utilise directement `IndexWriter` en mode `CREATE`, sans purge préalable ; Lucene conserve la progression des générations. Le nouveau corpus n'est validé qu'après l'ajout de tous les documents ; une fermeture après exception ne publie pas de corpus partiel.

Si le commit est corrompu ou illisible, le rebuild produit un index complet dans `semantic-lucene/recovery-<uuid>`, puis, une fois son commit réussi, le publie par remplacement atomique du fichier `current-generation`. Ce pointeur est borné, lu sans suivre de lien et limité à un nom de génération local validé. Un stockage sans remplacement atomique provoque un échec, sans publication partielle. En l'absence de pointeur, l'index est lu à son emplacement historique. Les lecteurs résolvent le répertoire actif avant chaque opération. Les fichiers d'une génération corrompue sont conservés ; leur nettoyage suit la procédure de quarantaine, après arrêt de tous les processus partageant `NEXUS_HOME`.

### Écriture et publication d'une indexation

- La migration V014 crée les tables transitoires `index_staged_files`, `index_staged_symbols` et `index_staged_relations`, sans fait canonique et vides hors indexation.
- Une indexation ouvre une `IndexWriteSession` (`IndexRepository.openWriteSession`) qui écarte d'abord les analyses laissées par une indexation interrompue. `stage` met les analyses en attente par lots (128 fichiers ou 16 MiB de sources au plus, comme les documents), chaque lot dans une courte transaction `writeTransaction`. `publish` applique en une transaction les analyses aux tables canoniques par pages d'au plus 128 fichiers, supprime les chemins retirés, vide la mise en attente et incrémente la génération. Fermer la session sans publier écarte la mise en attente : la génération précédente reste servie.
- `LuceneSearchIndex` et `LuceneSemanticSearchIndex` gardent un `IndexWriter` par indexation (`openSession`), alimenté à chaque lot sans commit, committé une fois à la barrière de publication et annulé (`rollback`) en cas d'échec.
- Ordre de la barrière : commit Lucene, publication canonique (génération), contrôle de cohérence, puis `READY`. Le commit Lucene précède la génération pour qu'un lecteur qui la suit voie un Lucene committé. Un échec ou un arrêt brutal après ce commit laisse le projet hors `READY`, donc reconstruit ; rien n'est servi avant `READY`.

Hors décision : la granularité de publication de la génération, le scan final de cohérence et les contrats CLI, REST ou MCP ne changent pas ; aucun verrou global entre JVM ni thread de rafraîchissement n'est ajouté.

## Do's and Don'ts

### Do

- Prendre le verrou d'écriture inter-processus du projet pour une mutation, et pour une lecture publique un verrou partagé conservé pendant toute la façade ; acquérir les verrous fédérés dans l'ordre global des UUID.
- Porter la version de la redaction dans chaque commit Lucene et dans le profil sémantique, la vérifier par `isPresent` et reconstruire un dérivé absent, altéré ou d'une autre version.
- Rafraîchir le `SearcherManager` avant chaque recherche servie par le cache et après une mutation locale ; invalider le reader avant un rebuild.
- Borner le cache à 100 projets par index et retomber au-delà sur l'implémentation operation-scoped.
- N'activer les readers persistants que par `NexusApplication.createLongLived(...)` (REST, MCP) et fermer explicitement cette façade.
- Reconstruire en mode `CREATE` sans purge ; publier une récupération par remplacement atomique de `current-generation` une fois son commit réussi.
- Mettre en attente les analyses par lots bornés dans de courtes transactions, puis publier faits canoniques et génération en une transaction, après le commit unique de chaque index dérivé.

### Don't

- Ne pas ouvrir Lucene pour une lecture publique sans le verrou partagé du projet, ni présenter `READY` comme un snapshot atomique du filesystem vivant.
- Ne pas garder d'`IndexWriter` ouvert pendant la vie d'un processus REST ou MCP, ni partager un writer entre indexations ou projets.
- Ne pas retirer à `ProjectIndexLockManager` son rôle d'autorité de sérialisation des mutations inter-processus.
- Ne pas purger les fichiers Lucene avant un rebuild normal, ni publier un corpus partiel.
- Ne pas tenir une transaction d'écriture SQLite ouverte pendant toute l'indexation, ni retenir en mémoire les analyses de tous les fichiers modifiés, ni committer un index Lucene à chaque lot.
- Ne pas lire les tables de mise en attente comme une vérité.

## Consequences

### Positives

- L'essentiel du gain de recherche mesuré est conservé pour les serveurs longue durée, sans write-lock Lucene permanent ni nouvelle dépendance.
- Une lecture publique ne traverse pas un remplacement de dérivé ; un dérivé absent ou d'une autre version de redaction n'est jamais pris pour valide.
- Les lecteurs indépendants suivent les rebuilds et les récupérations, y compris sous Windows.
- Heap d'indexation borné par lot ; verrou d'écriture SQLite tenu par transactions courtes ; un seul fsync par index dérivé et par indexation ; un échec avant la barrière laisse génération, faits canoniques et dérivés précédents intacts.

### Négatives

- La composition longue durée a un cycle de fermeture explicite ; un projet au-delà de la capacité du cache n'a pas de reader persistant ; lectures et rafraîchissements paient un faible coût de verrou et de vérification de commit.
- Des corruptions répétées accumulent des générations conservées ; revenir à un binaire sans `current-generation` après une récupération impose de mettre l'index sémantique en quarantaine et de le reconstruire.
- Les analyses sont écrites deux fois (mise en attente puis tables canoniques) ; trois tables transitoires de plus ; un `IndexWriter` reste ouvert pendant l'indexation, sous le verrou d'indexation du projet.

### Risques

| Risque | Impact | Mesure de maîtrise |
|---|---|---|
| Reader périmé après une mutation externe | Élevé | `SearcherManager.maybeRefreshBlocking()` avant recherche |
| Handle Windows empêchant un rebuild ou fermeture concurrente | Élevé | Invalidation du reader avant rebuild, pas de purge, verrou lecture/écriture par ressource |
| Régression multi-processus | Élevé | Aucun writer persistant ; tests de verrous inter-JVM et qualification Windows |
| Échec entre le commit Lucene et la publication canonique | Moyen | Projet hors `READY`, rebuild complet |
| Régression de performance | Moyen | Workflows `lucene-lifecycle-benchmark.yml` et `scale-benchmark.yml` |

## Compliance and Enforcement

- Règle Archgate `DATA-0074-garantir-le-cycle-de-vie-et-la-coherence-des-index-derives.rules.ts` / `reader-cache-holds-no-index-writer` : `BoundedLuceneSearcherCache`, `PersistentLuceneReaderSupport`, `PersistentLuceneSearchIndex` et `PersistentLuceneSemanticSearchIndex` ne référencent pas `IndexWriter`.
- Même règle / `reader-cache-bounded-to-100-projects` : `PersistentLuceneSearchIndex` et `PersistentLuceneSemanticSearchIndex` déclarent `MAX_CACHED_PROJECTS = 100`.
- Même règle / `long-lived-composition-only-in-rest-and-mcp` : `createLongLived(` n'est appelé que sous `adapters/rest-quarkus/src/main/java` et `adapters/mcp-java/src/main/java` ; `NexusApplication` ne demande des readers persistants à `NexusApplicationFactory.create` que depuis `createLongLived` ; les index persistants ne sont instanciés que par `NexusApplicationFactory`.
- Tests, lancés par `mvnw clean install` (workflow `.github/workflows/ci.yml` : job Linux reactor, et job Windows gate par `scripts/validate-phase-6.ps1`) : `ProjectIndexLockManagerTest` (`allowsConcurrentReadersButExcludesWriters`, `enforcesReadWriteSemanticsAcrossDistinctJvmProcesses`), `NexusApplicationFederatedScopePolicyTest.federatedReadsReleaseLocksAfterSuccessAndPartialAcquisitionFailure`, `NexusApplicationLongLivedLifecycleTest`, `LuceneSearchIndexTest.closesAllHandlesAfterRebuildAndSearch`, `SemanticIndexProvenanceIntegrationTest`, `LuceneSemanticSearchIndexTest` (`closesAllHandlesAfterRebuildUpdateAndSearch`, `independentPersistentReaderFollowsRepeatedRebuildsAndEmptyCommits`, `independentReaderFollowsRecoveryFromCorruptCommitAndCanThenWrite`, `rebuildWithNewDimensionsDoesNotServeThePreviousVectorSpace`, `failedRebuildDoesNotPublishPartialDocuments`, `refusesRecoveryPointersOutsideTheSemanticDirectory`, `explicitRebuildRecoversPhysicallyCorruptIndex`), `SqliteIndexWriteSessionTest`, `ProjectIndexingPublicationTest`, `LuceneSearchIndexSessionTest`, `LuceneSemanticSearchIndexSessionTest`.
- Workflows : `.github/workflows/lucene-lifecycle-benchmark.yml` (benchmark ABBA `LuceneLifecycleQualificationBenchmarkTest`, Linux et Windows), `.github/workflows/filesystem-semantics-qualification.yml` (`ProjectIndexLockManagerTest` sur les systèmes de fichiers qualifiés), `.github/workflows/scale-benchmark.yml` (`ScaleRegressionBenchmarkTest`, coût de la double écriture).
- Revue : la prise des verrous autour des façades, le mode d'ouverture du rebuild, la publication du pointeur et le passage des écritures d'indexation par la session ne se lisent pas sur le texte du dépôt.

## Conditions de réexamen

La décision est réévaluée si : le rafraîchissement avant recherche annule le gain sur des corpus représentatifs ; la limite de 100 projets chauds devient insuffisante ; un besoin justifié de writers persistants apparaît avec un protocole multi-processus compatible ; Lucene change substantiellement le cycle de vie de `SearcherManager` ; la transaction de publication devient une fraction significative de l'indexation d'un très gros dépôt ; le `busy_timeout` ou la politique de reprise SQLite changent ; SQLite permet plusieurs écrivains ; un service de recherche externe devient nécessaire pour un changement d'échelle prouvé. Une modification substantielle donne lieu à un nouvel ADR qui remplace explicitement celui-ci.

## Mise en œuvre

- Verrous : `core/src/main/java/com/nexus/index/ProjectIndexLockManager.java` ; lecture cohérente dans `core/src/main/java/com/nexus/application/NexusApplication.java`.
- Readers en cache : `core/src/main/java/com/nexus/search/lucene/` (`BoundedLuceneSearcherCache`, `PersistentLuceneReaderSupport`, `PersistentLuceneSearchIndex`) et `core/src/main/java/com/nexus/search/semantic/lucene/PersistentLuceneSemanticSearchIndex.java` ; composition dans `core/src/main/java/com/nexus/application/NexusApplicationFactory.java`.
- Rebuild et récupération sémantiques : `core/src/main/java/com/nexus/search/semantic/lucene/LuceneSemanticSearchIndex.java`.
- Publication : `core/src/main/java/com/nexus/index/service/ProjectIndexingService.java`, `IndexPublication.java`, `core/src/main/java/com/nexus/persistence/sqlite/SqliteIndexWriteSession.java`, `SqliteIndexStaging.java`, migration `core/src/main/resources/db/migration/V014__index_staged_analyses.sql`.

## References

- [DATA-0073](DATA-0073-tenir-sqlite-pour-source-de-verite-et-lucene-pour-index-derive.md) : SQLite source de vérité, Lucene index dérivé.
- [BE-0081](BE-0081-federer-la-recherche-locale-par-projet-sous-echeance-globale.md) : recherche fédérée par projet.
- [BE-0084](BE-0084-garder-la-recherche-semantique-optionnelle-et-qualifiee-hors-release.md) : recherche sémantique optionnelle.
- Documentation développeur : [indexing-pipeline.md](../../developer/indexing-pipeline.md), [sqlite-write-contention.md](../../developer/sqlite-write-contention.md), [lucene-lifecycle-qualification.md](../../developer/lucene-lifecycle-qualification.md), [semantic-search.md](../../developer/semantic-search.md) (procédure de quarantaine).
- Issue #50, PR #159, PR #161 ; changement OpenSpec `sprint-05-indexation-et-ecriture-sqlite` ; [relevé des mesures du 2026-11-07](../../project/history/indexing-heap-and-hashing-measures-2026-11-07.md).
