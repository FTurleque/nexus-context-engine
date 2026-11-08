---
id: DATA-0062
title: "Mettre en attente les analyses d'indexation dans SQLite et committer Lucene une fois"
domain: data
rules: false
---

# Mettre en attente les analyses d'indexation dans SQLite et committer Lucene une fois

> **Archivé le 2026-11-08** : remplacé par [DATA-0074](../DATA-0074-garantir-le-cycle-de-vie-et-la-coherence-des-index-derives.md) (cycle de vie et cohérence des index dérivés). Ce document est conservé pour l'historique ; il n'est plus lu par Archgate.

- **Statut** : Superseded
- **Date** : 2026-11-07
- **Origine** : changement OpenSpec `sprint-05-indexation-et-ecriture-sqlite`, constats AUD-PRF-12 et AUD-PRF-11, décisions D2 et D4
- **Complète** : ADR-0045, ADR-0051 et ADR-0052
- **Remplacé par** : [DATA-0074](../DATA-0074-garantir-le-cycle-de-vie-et-la-coherence-des-index-derives.md)

## Context

`IndexDocumentPublisher.publish` bornait les documents retenus (128 fichiers ou 16 MiB par lot) mais accumulait les `IndexedFileUpdate` de **tous** les fichiers modifiés jusqu'à une écriture SQLite unique : le heap vivant d'un rebuild croissait linéairement avec le nombre de fichiers modifiés (de l'ordre de 5 à 7 Kio par fichier, mesure du [relevé du 2026-11-07](../../../project/history/indexing-heap-and-hashing-measures-2026-11-07.md)). Chaque lot ouvrait en outre un `IndexWriter` Lucene et le committait (fsync).

Le design D2 proposait d'écrire les analyses dans la transaction d'indexation au rythme des lots, avec la table de mise en attente comme alternative si la contention SQLite l'interdisait. La mesure tranche contre la transaction unique : la transaction d'écriture finale dure 4,5 s pour 94,6 s d'indexation (4,8 %), et la tenir ouverte pendant toute l'indexation multiplierait par environ 21 le temps de verrou d'écriture, alors que la politique de reprise est de 5 s de `busy_timeout` et deux tentatives (voir [sqlite-write-contention.md](../../../developer/sqlite-write-contention.md)) : tout autre écrivain, y compris l'indexation d'un autre projet, échouerait en `SQLITE_BUSY`.

Question : comment borner la rétention des analyses par lot sans tenir un verrou d'écriture SQLite pendant toute l'indexation, tout en gardant la génération précédente intacte jusqu'à la publication ?

## Options envisagées

- **Option A : transaction d'indexation unique ouverte pendant toute l'indexation.** Rétention bornée, génération atomique, mais verrou d'écriture tenu pendant toute l'indexation : rejetée par la mesure.
- **Option B : écrire les faits canoniques par courtes transactions au rythme des lots.** Verrou court, mais des faits partiels seraient visibles dans les tables canoniques si l'indexation échoue : rejetée.
- **Option C : table de mise en attente alimentée par courtes transactions, vidée dans les tables canoniques par la transaction finale.** Rétention bornée, verrou court, aucun fait canonique ni génération modifiés avant la publication.

## Decision

**Option retenue : Option C.**

- Une migration dédiée (V014) crée trois tables transitoires, `index_staged_files`, `index_staged_symbols` et `index_staged_relations`, sans fait canonique et vides hors indexation.
- Une indexation ouvre une `IndexWriteSession` (`IndexRepository.openWriteSession`) qui écarte d'abord les analyses laissées par une indexation interrompue. `stage` met ensuite les analyses en attente par lots (128 fichiers ou 16 MiB de sources au plus, comme les documents), chaque lot dans une courte transaction `writeTransaction`. `publish` applique en **une** transaction les analyses aux tables canoniques par pages d'au plus 128 fichiers, supprime les chemins retirés, vide la mise en attente et incrémente la génération. Les lots de `symbols` et `symbol_relations` restent de 128 lignes au plus (ADR-0051).
- Fermer la session sans publier écarte les analyses en attente : la génération précédente reste servie, sans fait partiel.
- `LuceneSearchIndex` et `LuceneSemanticSearchIndex` gardent **un `IndexWriter` par indexation** (`openSession`), alimenté à chaque lot sans commit, committé une fois à la barrière de publication et annulé (`rollback`) en cas d'échec. Il reste operation-scoped au sens d'ADR-0045 : jamais partagé entre indexations ni projets, jamais référencé par les classes de cache de readers.
- Ordre de la barrière, laissé ouvert par le design : commit Lucene, publication canonique (génération), contrôle de cohérence, puis `READY`. Le commit Lucene précède la génération pour qu'un lecteur qui la suit voie un Lucene committé. Un échec ou un arrêt brutal après le commit laisse le projet non `READY`, donc reconstruit ; rien n'est servi avant `READY` (ADR-0052).

Cette décision ne change ni la granularité de publication de la génération, ni le scan final de cohérence, ni les contrats CLI, REST ou MCP.

## Do's and Don'ts

### Do

- Mettre en attente les analyses des fichiers modifiés par lots bornés, dans de courtes transactions, pendant l'indexation.
- Publier faits canoniques et génération en une seule transaction, après le commit des index Lucene.
- Utiliser un seul `IndexWriter` par index dérivé et par indexation, committé une fois, annulé en cas d'échec.

### Don't

- Ne pas tenir une transaction d'écriture SQLite ouverte pendant toute l'indexation.
- Ne pas retenir en mémoire les analyses de tous les fichiers modifiés.
- Ne pas committer un index Lucene à chaque lot.
- Ne pas lire les tables de mise en attente comme une vérité : elles sont transitoires et écartées à l'ouverture d'une indexation.

## Consequences

### Positives

- Heap d'indexation borné par lot, non par nombre de fichiers modifiés.
- Verrou d'écriture SQLite tenu pendant des transactions courtes : un autre projet peut écrire entre deux lots.
- Un seul commit (fsync) par index dérivé et par indexation au lieu d'un par lot de 128 fichiers.
- Un échec avant la barrière laisse la génération, les faits canoniques et les index dérivés précédents intacts.

### Négatives

- Les analyses sont écrites deux fois (mise en attente puis tables canoniques) ; ce coût est mesuré par le benchmark de réindexation et la transaction de publication reste une fraction de l'indexation.
- Un `IndexWriter` reste ouvert pendant toute l'indexation (verrou `write.lock` du répertoire du projet), ce que le verrou d'indexation par projet garantit déjà exclusif.
- Un schéma de plus (trois tables transitoires).

### Risques

| Risque | Impact | Mesure de maîtrise |
|---|---|---|
| Échec après le commit Lucene et avant la publication canonique | Moyen | Projet `FAILED` ou `INDEXING`, donc rebuild complet ; rien n'est servi avant `READY`. |
| Analyses laissées par un arrêt brutal | Faible | Écartées à l'ouverture de la session suivante ; tables absentes des sauvegardes utiles. |
| Écriture à double du volume d'analyses | Faible | Benchmark de réindexation sur base peuplée (`ScaleRegressionBenchmarkTest`). |

## Compliance and Enforcement

- Test `SqliteIndexWriteSessionTest` : analyses invisibles avant la publication, faits publiés égaux à ceux de `applyChanges`, fermeture sans publication qui écarte la mise en attente, reprise après indexation interrompue.
- Test `ProjectIndexingPublicationTest` : lots mis en attente bornés, génération publiée une seule fois et à la fin, échec en cours d'indexation qui laisse génération et dérivés précédents intacts, écriture concurrente d'un autre projet entre deux lots, un seul commit lexical et sémantique pour plus de 128 fichiers.
- Tests `LuceneSearchIndexSessionTest` et `LuceneSemanticSearchIndexSessionTest` : commit unique, rollback conservant l'index et la provenance précédents.
- Règle Archgate de DATA-0045 (`reader-cache-holds-no-index-writer`) : les classes de cache de readers ne référencent toujours pas `IndexWriter`.
- Revue : une écriture canonique d'indexation passe par la session, jamais par une transaction ouverte pendant l'analyse.

## Conditions de réexamen

La décision est réévaluée si : la transaction de publication dépasse une fraction mesurée significative de l'indexation d'un très gros dépôt, si le `busy_timeout` ou la politique de reprise changent, ou si SQLite permet plusieurs écrivains. Une modification substantielle donne lieu à un nouvel ADR qui remplace explicitement celui-ci.

## Mise en œuvre

Migration `V014__index_staged_analyses.sql`, `IndexWriteSession`, `SqliteIndexWriteSession`, `SqliteIndexStaging`, `SearchIndexSession`, `SemanticIndexSession`. Mesures et ordre de publication : [indexing-pipeline.md](../../../developer/indexing-pipeline.md#9-coûts-mesurés-du-pipeline).

## References

- Changement OpenSpec `sprint-05-indexation-et-ecriture-sqlite` (design D2 et D4).
- [DATA-0045](DATA-0045-conserver-les-writers-operation-scoped-et-mettre-en-cache-les-readers-lucene.md), [DATA-0051](DATA-0051-borner-les-lots-d-ecriture-sqlite.md), [DATA-0052](DATA-0052-garantir-la-coherence-des-lectures-et-des-index-derives.md).
- [Relevé des mesures du 2026-11-07](../../../project/history/indexing-heap-and-hashing-measures-2026-11-07.md).
