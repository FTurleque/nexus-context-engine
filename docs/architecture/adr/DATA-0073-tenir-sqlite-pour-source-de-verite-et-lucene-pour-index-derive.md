---
id: DATA-0073
title: "Tenir SQLite pour source de vérité locale et Lucene pour index de recherche dérivé et reconstructible"
domain: data
rules: true
---

# Tenir SQLite pour source de vérité locale et Lucene pour index de recherche dérivé et reconstructible

- **Statut** : Accepted
- **Date** : 2026-11-08
- **Origine** : consolidation du registre ADR (audit du 2026-11-08)
- **Remplace** : [DATA-0006](archive/DATA-0006-utiliser-sqlite-comme-source-de-verite-structurelle.md), [DATA-0007](archive/DATA-0007-utiliser-lucene-comme-index-de-recherche-local.md), [DATA-0022](archive/DATA-0022-traiter-lucene-comme-un-index-derive-reconstructible-de-sqlite.md)
- **Remplacé par** : —

## Context

NEXUS persiste localement des données structurées : projets enregistrés, fichiers indexés et leurs empreintes, symboles, relations et leur provenance, état d'indexation, métadonnées des sources de contexte. Ce stockage doit permettre une réindexation incrémentale, des mises à jour transactionnelles, des relations entre entités et une inspection simple, sans serveur de base de données.

NEXUS doit aussi rechercher vite, dans un dépôt local, des chemins, packages, noms de symboles, signatures, contenus, commentaires et documentation, en combinant correspondances exactes, recherche textuelle, pondération par champ, filtres et ranking lexical (BM25). Utiliser SQLite comme moteur de recherche principal limiterait le ranking ; un moteur serveur serait disproportionné pour un outil local.

Une indexation modifie alors deux stockages qui ne partagent aucune transaction distribuée. Garantir une atomicité parfaite entre eux serait très complexe ; ignorer le risque de divergence produirait des résultats incohérents après une interruption ou une erreur disque.

Question : quel stockage fait autorité pour l'état d'un projet, quel moteur sert la recherche locale, et comment les garder cohérents sans transaction distribuée ?

## Options envisagées

Source de vérité structurelle :

- **Fichiers JSON/YAML** : simples, mais mises à jour partielles, transactions et relations difficiles.
- **H2 embarqué** : SQL complet, mais moins inspectable qu'un fichier SQLite, sans besoin propre à H2.
- **SQLite** : embarquée, fichier unique, transactions, outillage ; limitée pour le distribué et la forte concurrence.
- **Lucene aussi source de vérité** : pas de modèle relationnel ni de transactions métier, responsabilités mélangées.
- **Base serveur** : infrastructure obligatoire.

Moteur de recherche local :

- **SQLite FTS comme moteur principal** : ranking multi-champs limité, persistance canonique mêlée à l'index.
- **Apache Lucene embarqué** : Java pur, BM25, boosts, sans serveur ; demande synchronisation et tuning.
- **Zoekt ou OpenGrok** : intégration lourde, surdimensionnés pour un dépôt local.
- **Elasticsearch / OpenSearch** : serveur obligatoire.
- **Moteur lexical maison** : réimplémentation coûteuse sans valeur différenciante.

Cohérence entre les deux stockages :

- **Deux sources de vérité égales** : conflits difficiles, état ambigu après incident.
- **Écrire Lucene avant SQLite** : Lucene pourrait référencer des données jamais validées.
- **Transaction distribuée ou journal complexe** : complexité disproportionnée pour deux stockages embarqués.
- **SQLite canonique, Lucene dérivé reconstructible** : récupération simple, au prix d'une courte fenêtre de divergence et d'un mécanisme de reconstruction.

## Decision

**Options retenues : SQLite est la source de vérité structurelle locale ; Apache Lucene est l'index de recherche local, dérivé, jetable et reconstructible depuis SQLite et les sources du projet.**

### SQLite, source de vérité locale

SQLite stocke le registre des projets, les fichiers indexés, leurs empreintes de contenu, leurs dates et états d'indexation, les symboles, les relations structurelles et leur provenance, les métadonnées des sources de contexte et les paramètres locaux nécessaires à la cohérence de l'index. Les opérations d'indexation critiques s'y font dans des transactions adaptées, sans service de base de données externe.

Le cœur métier n'expose pas de types JDBC ou SQLite : les accès passent par des repositories ou ports de persistance. SQLite est l'implémentation locale de ces ports, pas un contrat métier : la remplacer ne modifie pas les contrats métier de persistance.

### Lucene, index de recherche local

Lucene indexe des documents dérivés du dépôt et des données structurées NEXUS. Les champs candidats sont `projectId`, `path`, `module`, `packageName`, `language`, `symbolName`, `symbolKind`, `qualifiedName`, `content`, `comments`, `documentation` et `sourceType`. La recherche exploite BM25, des boosts par champ, les correspondances exactes de symboles, la recherche approximative, des filtres par projet, langage, type ou source, et se combine avec les autres stratégies de recherche NEXUS.

La recherche lexicale passe par le port `SearchIndex` (l'index sémantique par `SemanticSearchIndex`). Le code métier ne dépend pas directement des classes Lucene, et aucune API Lucene n'apparaît dans `ContextRequest`, `ContextBundle` ou les modèles métier. Les scores de recherche sont mesurés sur un corpus de référence. Aucun serveur de recherche externe n'est requis.

### Index dérivé, jetable et reconstructible

- SQLite fait autorité pour l'état du projet ; Lucene n'est jamais une seconde source de vérité.
- L'état d'index généré est jetable : supprimer le répertoire Lucene d'un projet ne détruit aucune donnée canonique, et une reconstruction le recrée à partir des fichiers et métadonnées persistés dans SQLite. Une corruption de l'index de recherche ne remet en cause ni l'identité du projet ni la cohérence structurelle stockée dans SQLite.
- L'état d'indexation du projet est enregistré dans SQLite avant toute écriture Lucene. Un échec Lucene n'annule ni ne corrompt silencieusement l'état SQLite déjà validé : il laisse le projet hors `READY`, ce qui retient qu'une reconstruction complète est nécessaire au passage suivant.
- Les documents Lucene ont une clé stable dérivée de `(projectId, relativePath)`, qui rend `updateDocument` et `deleteDocuments` idempotents ; un fichier supprimé du projet disparaît aussi de Lucene.
- L'ordre des écritures d'une indexation, la publication des index dérivés et la cohérence des lectures relèvent de [DATA-0074](DATA-0074-garantir-le-cycle-de-vie-et-la-coherence-des-index-derives.md).

Hors décision : une atomicité stricte entre SQLite et Lucene, et une cohérence de recherche stricte en temps réel.

## Do's and Don'ts

### Do

- Persister dans SQLite le registre des projets, les fichiers indexés, leurs empreintes, leurs dates et états d'indexation, les symboles, les relations structurelles et leur provenance, les métadonnées des sources de contexte et les paramètres locaux nécessaires à la cohérence de l'index.
- Faire passer les accès à SQLite par des repositories ou des ports de persistance, et les opérations d'indexation critiques par des transactions adaptées.
- Utiliser Apache Lucene comme index de recherche local, derrière le port `SearchIndex` (ou `SemanticSearchIndex`), et mesurer ses scores sur un corpus de référence.
- Garder l'index Lucene reconstructible à partir des données canoniques et des sources du projet : pouvoir supprimer l'index d'un projet puis le recréer.
- Enregistrer l'état d'indexation dans SQLite avant d'écrire Lucene, et laisser le projet hors `READY` quand la mise à jour Lucene échoue.
- Identifier les documents Lucene par une clé stable dérivée de `(projectId, relativePath)` et faire disparaître de Lucene les fichiers supprimés.

### Don't

- Ne pas exposer de types JDBC ou SQLite dans le cœur métier.
- Ne pas faire dépendre le code métier des classes Lucene, ni faire apparaître d'API Lucene dans `ContextRequest`, `ContextBundle` ou les modèles métier.
- Ne pas traiter Lucene comme une source de vérité.
- Ne pas laisser un échec ou une corruption Lucene annuler ou corrompre l'état SQLite déjà validé, l'identité du projet ou sa cohérence structurelle.
- Ne pas exiger de service de base de données ni de serveur de recherche externe.

## Consequences

### Positives

- Aucun serveur à installer : stockage et recherche locaux, cohérents avec le fonctionnement local-first.
- Transactions adaptées aux mises à jour incrémentales ; fichier de base facile à sauvegarder ou inspecter, sans Lucene.
- Ranking lexical plus riche que des requêtes SQL : plusieurs champs, boosts, BM25.
- Modèle de cohérence simple, récupération sans transaction distribuée : Lucene peut être supprimé, reconstruit et ses mises à jour rejouées.

### Négatives

- SQLite ne vise ni une architecture distribuée multi-nœuds ni des accès concurrents massifs ; les migrations de schéma doivent être gérées.
- Deux stockages locaux à synchroniser, avec une fenêtre de divergence temporaire entre SQLite et Lucene ; un index obsolète doit être détecté.
- Une reconstruction peut être coûteuse sur un gros projet ; certaines migrations ou corruptions l'imposent.
- Lucene ajoute une dépendance et une API technique ; le schéma d'index et les analyzers demandent des mesures sur corpus réel.
- Une couche d'abstraction est nécessaire pour éviter le couplage du code au SQL et à Lucene.

### Risques

| Risque | Impact | Mesure de maîtrise |
|---|---|---|
| Divergence entre SQLite et Lucene (crash entre les deux écritures) | Élevé | État d'indexation dans SQLite, projet hors `READY` reconstruit, commande de reconstruction complète |
| Document Lucene orphelin après suppression d'un fichier | Élevé | Suppression explicite par clé stable lors de la réindexation |
| Migration de schéma incorrecte | Élevé | Schéma versionné, migrations testées |
| Mauvais ranking par défaut | Élevé | Corpus de référence, `precision@K`, `recall@K`, tuning mesuré |
| Couplage métier au SQL ou à Lucene | Moyen | Ports de persistance, `SearchIndex` et `SearchStrategy` ; règles Archgate de confinement |
| Base ou index trop volumineux | Moyen | Mesurer la taille ; ne pas dupliquer les contenus ni multiplier les champs stockés sans mesure |
| Reconstruction trop fréquente | Faible à moyen | Réserver la reconstruction complète aux échecs, corruptions et migrations nécessaires |
| Besoin futur de stockage distribué ou de moteur externe | Moyen | SQLite et Lucene restent des implémentations locales derrière des ports, pas des contrats métier |

## Compliance and Enforcement

- Règle Archgate `DATA-0073-tenir-sqlite-pour-source-de-verite-et-lucene-pour-index-derive.rules.ts` / `core-jdbc-confined-to-sqlite-adapter` : dans `core/src/main/java`, `java.sql`, `javax.sql` et `org.sqlite` ne sont référencés (commentaires et littéraux effacés) que sous `com/nexus/persistence/sqlite/` ; seule tolérance, `java.sql.SQLException` dans `NexusApplication`, `NexusApplicationFactory` et `NexusCli`. Au 2026-11-08, seule `NexusApplicationFactory` l'utilise, pour l'envelopper dans `PersistenceException` (`core/src/main/java/com/nexus/application/NexusApplicationFactory.java:250`).
- Règle Archgate `DATA-0073-tenir-sqlite-pour-source-de-verite-et-lucene-pour-index-derive.rules.ts` / `lucene-confined-to-search-adapters` : dans les sources de production (`**/src/main/java`), `org.apache.lucene` n'est référencé que sous `core/src/main/java/com/nexus/search/lucene/` et `core/src/main/java/com/nexus/search/semantic/lucene/`.
- `CoreArchitectureTest` (`core/src/test/java/com/nexus/architecture/`) : `sqliteAndJdbcAreConfinedToPersistence` et `luceneIsConfinedToItsAdapters` sur les sources du cœur.
- Tests `ProjectRegistryTest` (`core/src/test/java/com/nexus/project/`, enregistrement qui survit à la réouverture), `SqliteIndexedSubstringSearchTest.rollbackRestoresCanonicalRowsAndFlushedProjection` et `SchemaMigratorTest` (`core/src/test/java/com/nexus/persistence/sqlite/`, transactions et rollback), `ProjectIndexingServiceTest.indexesIncrementallyAndPropagatesChangesAndDeletions` et `ProjectIndexingBatchingTest.rebuildsDerivedIndexInBoundedDocumentBatches` (`core/src/test/java/com/nexus/index/`, propagation des suppressions et reconstruction), `LuceneSearchIndexTest` et `LuceneFileSearchStrategyLocaleTest` (`core/src/test/java/com/nexus/search/lucene/`), `LuceneSemanticSearchIndexTest.appliesUpdatesAndRemovalsByRelativePath` (`core/src/test/java/com/nexus/search/semantic/lucene/`), `FederatedGoldenSearchCorpusTest` (`core/src/test/java/com/nexus/search/`, classement sur un corpus de référence), lancés par `mvnw clean install` (workflow `.github/workflows/ci.yml` : job Linux reactor, et job Windows gate par `scripts/validate-phase-6.ps1`).
- Revue : un nouvel accès aux données passe par un port de persistance du cœur et son implémentation sous `persistence/sqlite` ; l'ordre « état SQLite, puis Lucene » ne se lit pas sur le texte du dépôt.

## Conditions de réexamen

La décision est réévaluée si :

- NEXUS doit servir simultanément de nombreux utilisateurs sur un service centralisé, ou plusieurs nœuds doivent écrire dans le même registre ;
- la concurrence ou le volume dépassent objectivement les capacités mesurées de SQLite ou de Lucene ;
- un moteur externe spécialisé apporte un gain mesurable suffisant, ou les coûts de synchronisation dépassent les bénéfices ;
- une cohérence de recherche stricte en temps réel devient nécessaire, ou un stockage unique satisfait les besoins relationnels et de recherche sans perte de qualité.

Lucene reste le moteur local par défaut tant qu'il satisfait les critères mesurés. Une modification substantielle donne lieu à un nouvel ADR qui remplace explicitement celui-ci.

## Mise en œuvre

- Persistance : ports dans `core/src/main/java/com/nexus/index/IndexRepository.java` et `core/src/main/java/com/nexus/project/ProjectRepository.java`, implémentations sous `core/src/main/java/com/nexus/persistence/sqlite/`, migrations sous `core/src/main/resources/db/migration/`.
- Recherche : ports `core/src/main/java/com/nexus/search/SearchIndex.java` et `core/src/main/java/com/nexus/search/semantic/SemanticSearchIndex.java` ; adaptateurs `core/src/main/java/com/nexus/search/lucene/` (clé `documentKey(projectId, relativePath)` dans `LuceneSearchIndex`) et `core/src/main/java/com/nexus/search/semantic/lucene/`.
- Reprise : `core/src/main/java/com/nexus/index/service/ProjectIndexingService.java` enregistre `INDEXING` avant d'écrire les dérivés et reconstruit tout projet hors `READY` ou dont l'index lexical est absent.

## References

- [ARCH-0005](ARCH-0005-adopter-un-fonctionnement-local-first-et-opt-in.md) : fonctionnement local-first et intégrations externes opt-in.
- [DATA-0074](DATA-0074-garantir-le-cycle-de-vie-et-la-coherence-des-index-derives.md) : cycle de vie et cohérence des index dérivés.
- [BE-0071](BE-0071-rechercher-et-classer-les-candidats-par-un-score-compose-explicable.md) : ranking des candidats.
- [BE-0084](BE-0084-garder-la-recherche-semantique-optionnelle-et-qualifiee-hors-release.md) : recherche sémantique optionnelle.
- [docs/developer/index-provenance.md](../../developer/index-provenance.md) : provenance et fraîcheur des index.
