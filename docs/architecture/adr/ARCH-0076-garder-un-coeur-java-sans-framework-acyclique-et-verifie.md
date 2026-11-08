---
id: ARCH-0076
title: "Garder un cœur Java sans framework, aux packages acycliques vérifiés par un test d'architecture, et n'extraire un module Maven que sur besoin réel"
domain: architecture
rules: true
---

# Garder un cœur Java sans framework, aux packages acycliques vérifiés par un test d'architecture, et n'extraire un module Maven que sur besoin réel

- **Statut** : Accepted
- **Date** : 2026-11-08
- **Origine** : consolidation du registre ADR (audit du 2026-11-08)
- **Remplace** : [ARCH-0003](archive/ARCH-0003-conserver-un-coeur-java-sans-framework-applicatif.md), [ARCH-0004](archive/ARCH-0004-demarrer-avec-un-seul-module-maven.md), [ARCH-0059](archive/ARCH-0059-verifier-les-frontieres-du-coeur-par-un-test-d-architecture.md), [ARCH-0063](archive/ARCH-0063-casser-les-cycles-de-packages-du-coeur-par-des-packages-de-modele-feuilles.md)
- **Remplacé par** : —

## Context

NEXUS s'utilise en CLI, en API REST, en serveur MCP, en bibliothèque ou depuis un orchestrateur, surfaces aux besoins et dépendances différents. Un framework applicatif apporte injection, configuration et observabilité, mais ferait dépendre les contrats métier d'un runtime inutile à la plupart des usages.

Un module Maven unique ne donne aucune barrière de compilation entre packages : les frontières (Lucene derrière `search.lucene`, SQLite derrière `persistence.sqlite`, feuille `project`, sens des dépendances) ne tiennent que par la revue. L'audit du 2026-10-17 a mesuré un bloc fortement connexe de packages (`context`, `context.source.*`, `index`, `index.scan`, `ranking`, `search`, `search.semantic`…), venu de types de modèle rangés avec leur orchestration, d'une orchestration rangée avec le modèle, d'une dépendance concrète à `search.semantic` passée à `null` et d'un port qui construisait son adaptateur Ollama.

La question est : **comment garder le cœur indépendant des frameworks, structuré par packages aux dépendances acycliques et contrôlées automatiquement, sans multiplier prématurément les modules Maven ?**

## Options envisagées

- **Socle du cœur** : Quarkus dans tout le projet (couplage du moteur à un runtime, annotations dans le domaine) ; autre framework léger global (même couplage, sans besoin) ; **Java simple dans le cœur et frameworks dans les adaptateurs** (portabilité, câblage explicite) ; aucun framework, API comprise (réimplémentation inutile du HTTP et de l'observabilité).
- **Découpage Maven** : tous les modules envisagés tout de suite (`nexus-core`, `nexus-indexer`, `nexus-search`, `nexus-api`, `nexus-cli`, `nexus-mcp`…, modules artificiels, frontières figées trop tôt) ; un petit ensemble de modules principaux (critères non validés) ; **extraction sur besoin réel** (complexité minimale, discipline de packages nécessaire).
- **Contrôle des frontières** : **test JUnit sur les sources, sans dépendance** (parseur à maintenir) ; ArchUnit (dépendance de test et graphe transitif) ; modules Maven ou `module-info.java` dans le cœur (impossible tant que des cycles existent).
- **Suppression des cycles** : **packages de modèle feuilles et orchestration séparée** (déplacements et ports, sans changement de comportement) ; inverser les cycles en déplaçant `CodeSymbol` vers `search` ou `SearchService` vers `ranking` (le cycle change de sens sans disparaître).

## Decision

**Options retenues : Java simple dans le cœur et frameworks dans les adaptateurs ; un module Maven extrait seulement sur besoin réel ; test JUnit d'architecture sur les sources ; packages de modèle feuilles et orchestration séparée.**

### Cœur sans framework applicatif

- Le domaine et les services principaux dépendent de contrats Java propres à NEXUS.
- Le module cœur ne déclare et n'importe aucun framework applicatif (Quarkus, CDI/Jakarta, Spring, Micronaut, Guice) : les frameworks ne vivent que dans les adaptateurs qui en ont besoin ([ARCH-0075](ARCH-0075-exposer-le-coeur-par-des-adaptateurs-minces-isoles.md)).
- Un framework n'impose ni ses annotations ni ses types aux contrats du cœur ; les DTO REST n'entrent pas dans les signatures métier. Un mécanisme d'injection reste permis dans un adaptateur, sans contaminer les contrats métier.
- Un port n'est introduit que lorsqu'un besoin réel apparaît.

### Modules Maven

- Le cœur est un seul module Maven, `core`, organisé par responsabilités. La séparation logique par packages et par dépendances internes est obligatoire : l'absence de module distinct ne signifie pas l'absence d'architecture.
- Un module Maven distinct n'est extrait que si au moins un critère est réuni : runtime distinct, packaging distinct, isolation d'une dépendance lourde ou native, cycle de livraison indépendant, dépendance indésirable à empêcher structurellement, gain mesurable pour le build ou l'intégration. Toute création de module est motivée par une contrainte documentée ou un nouvel ADR.
- L'ordre d'extraction envisagé (`nexus-core`, `nexus-cli`, `nexus-api`, `nexus-mcp`, adaptateurs optionnels lourds) est indicatif et n'oblige à créer aucun module.

### Frontières vérifiées par un test d'architecture

`core/src/test/java/com/nexus/architecture/CoreArchitectureTest.java` fait partie de la suite par défaut du module `core` et vérifie, sur le code de production, des règles nommées dont chaque échec cite la règle, la classe et le nom importé :

- `core-sans-framework` : aucun type de `io.quarkus`, `jakarta`, `javax.ws`, `org.springframework` ni `io.modelcontextprotocol` ;
- `lucene-confine` : `org.apache.lucene` seulement dans `com.nexus.search.lucene` et `com.nexus.search.semantic.lucene` ;
- `sqlite-confine` : `org.sqlite` et `java.sql` seulement dans `com.nexus.persistence.sqlite`, plus une liste fermée, versionnée dans le test, de couples classe et nom importé ;
- `project-feuille` : `com.nexus.project` n'importe aucun autre package `com.nexus` ;
- `cycles-baseline` : les arêtes du graphe des packages internes à une composante fortement connexe sont égales à la baseline `core/src/test/resources/architecture/package-cycles.txt` (une arête par ligne, triée). Une arête nouvelle fait échouer le test, une entrée qui ne forme plus de cycle aussi ;
- règles de sens `index-sans-search`, `context-source-sans-context`, `ranking-sans-search`, `search-semantic-sans-ollama`.

Les imports et les noms pleinement qualifiés des préfixes surveillés comptent ; commentaires, chaînes et blocs de texte sont ignorés. Chaque règle a un cas négatif sur une fixture de sources en répertoire temporaire.

### Graphe des packages acyclique

Le graphe des dépendances entre packages du code de production du cœur est acyclique et le reste : la baseline de `CoreArchitectureTest` est vide et tout cycle fait échouer le build.

- `com.nexus.index.service` porte l'orchestration de l'indexation (`ProjectIndexingService`, `IndexDocumentPublisher`, `IndexPublication`, `ExternalCodeIntelligenceRefresher`) ; `com.nexus.index` garde le modèle et les ports, dont `SearchDocument`. `ResultLimitPolicy` est dans `com.nexus.config`.
- L'indexation sémantique est un port, `com.nexus.index.SemanticIndexing`, implémenté par `SemanticIndexingService` ; `NoopSemanticIndexing` (`enabled() == false`) représente la capacité désactivée.
- `com.nexus.search.candidate` porte `SearchCandidate`, `SearchSignals`, `CandidateType`, `CandidateEnricher`, `CandidateMerger` ; `com.nexus.context.model` porte `ContextFragment`, `ContextItem`, `ContextSelectionResult`.
- `SemanticSearchConfiguration` ne porte que des valeurs et le port `EmbeddingProvider` ; `NexusApplicationFactory` construit `OllamaEmbeddingProvider` et les autres adaptateurs.

Sens de dépendance autorisés (du dépendant vers sa dépendance) :

```text
application ──► tous les packages (composition root, seul à construire les adaptateurs)
index.service ─► index, search, index.scan, security, project
search ─► ranking, ranking.graph, search.candidate, index, config
ranking, ranking.graph ─► search.candidate, index, config
search.semantic ─► search, search.candidate, index
search.semantic.ollama ─► search.semantic
context ─► context.model, context.source.*, search, ranking
context.source.* ─► context.model, search.candidate
index.scan ─► index
index ─► config, paths, project, security   (jamais search, ranking ni context)
```

### Hors décision

Règles de score, formats SQLite et Lucene et champs publics restent inchangés. La décision ne réduit pas la visibilité des types publics et `CoreArchitectureTest` ne contrôle pas les imports des adaptateurs (contrôlés par les règles d'[ARCH-0075](ARCH-0075-exposer-le-coeur-par-des-adaptateurs-minces-isoles.md)).

## Do's and Don'ts

### Do

- Faire dépendre le domaine et les services de contrats Java NEXUS ; réserver les frameworks aux adaptateurs.
- Motiver toute extraction d'un module Maven par l'un des critères listés, documenté, ou par un nouvel ADR.
- Ranger un type partagé entre orchestration et ranking, sources ou stockage dans un package de modèle que ces packages importent, et que lui n'importe pas en retour.
- Construire les adaptateurs (Ollama, Lucene, SQLite) dans `NexusApplicationFactory` et faire dépendre l'orchestration d'un port.
- Représenter une capacité désactivée par une implémentation sans effet du port, pas par `null`.
- Ajouter une règle de frontière dans `CoreArchitectureRules` avec un nom stable et un cas négatif.
- Ajouter une exception à `sqlite-confine` sous forme de couple classe et nom importé exact, justifié dans la PR.

### Don't

- Ne pas déclarer ni importer Quarkus, CDI/Jakarta, Spring, Micronaut ou Guice dans le module cœur.
- Ne pas faire entrer les DTO REST dans les signatures métier.
- Ne pas créer de module Maven sans contrainte documentée ou nouvel ADR.
- Ne pas ajouter d'arête à la baseline de cycles ; elle est vide.
- Ne pas importer `com.nexus.search` depuis `com.nexus.index`, `com.nexus.context` depuis `com.nexus.context.source`, `com.nexus.search` depuis `com.nexus.ranking`, ni `com.nexus.search.semantic.ollama` depuis `com.nexus.search.semantic`.
- Ne pas déplacer un type de modèle vers le package qui l'orchestre.
- Ne pas élargir une exception à un package entier ou à un préfixe.
- Ne pas désactiver ni filtrer `CoreArchitectureTest` hors de la suite par défaut.

## Consequences

### Positives

- Le moteur s'embarque comme bibliothèque ; ses tests unitaires instancient les services sans conteneur.
- Un changement de framework ne touche pas la logique métier ; les couches de transport restent remplaçables.
- Une dérive de frontière ou un cycle échoue au build local et en CI, avec la règle et l'import fautif.
- Indexation, recherche, ranking et contexte sont séparables : une extraction ultérieure en module n'est pas bloquée par des cycles.
- Aucune dépendance de test nouvelle.

### Négatives

- Câblage du cœur explicite ; les services offerts par un framework sont introduits dans les adaptateurs.
- Maven ne protège pas physiquement les frontières internes du cœur : le test et la revue les portent.
- Le parseur ne voit que les imports et les noms qualifiés des préfixes surveillés : une dépendance par réflexion ou par chaîne lui échappe, et un nom `com.nexus.x.Type` sans import n'ajoute pas d'arête.
- `SearchDocument` reste dans un package nommé « index » ; `index.service` et `search.candidate` ajoutent deux packages.

### Risques

| Risque | Impact | Mesure de maîtrise |
|---|---|---|
| Annotations ou types de framework dans le cœur | Élevé | Règles Archgate compagnon et règle `core-sans-framework` du test |
| Le module `core` devient un monolithe non structuré | Élevé | Packages par capacité, ports clairs, règles de sens du test |
| Le parseur manque une forme de référence | Moyen | Test du parseur (import statique, joker, nom qualifié, commentaire, chaîne, bloc de texte) |

## Compliance and Enforcement

- Règle Archgate compagnon `ARCH-0076-garder-un-coeur-java-sans-framework-acyclique-et-verifie.rules.ts` : `core-imports-no-application-framework` refuse dans `core/src/main/java`, commentaires et littéraux effacés, toute référence à Quarkus, CDI/Jakarta (`inject`, `enterprise`, `ws.rs`, `interceptor`), Spring, Micronaut ou Guice ; `core-pom-declares-no-application-framework` refuse dans `core/pom.xml` tout `groupId` de ces frameworks.
- `CoreArchitectureTest` (règles listées ci-dessus, baseline vide exigée par `cycleBaselineIsEmpty`), exécuté par `./mvnw test` dans le module `core` (Windows gate et reactor Linux), filtrable par `-Dtest='*Architecture*'`.
- Les tests unitaires de `core/src/test/java` instancient les services du cœur sans conteneur applicatif ; lancés par `./mvnw -B clean install` (job « Linux reactor Maven build » de `.github/workflows/ci.yml`).
- Revue : toute modification de `package-cycles.txt` ou de `SQLITE_EXCEPTIONS` est justifiée dans la PR ; toute nouvelle dépendance entre packages respecte le tableau de sens ; tout nouveau module Maven cite son critère d'extraction.

## Écarts constatés

Constat du 2026-10-29 (la décision ci-dessus est inchangée) :

- Le `pom.xml` racine est un réacteur (`<packaging>pom</packaging>`, `pom.xml:10`) qui déclare quatre modules Maven : `core`, `adapters/rest-quarkus`, `adapters/mcp-java` et `adapters/assistant-clients` (`pom.xml:24-27`), depuis le commit `cf235632`. Les trois adaptateurs sont décidés par ARCH-0075 ; le passage à un réacteur parent commun n'est décidé par aucun ADR du registre.

## Conditions de réexamen

La décision est réévaluée si : le cœur devient exclusivement destiné à un runtime unique ; le coût du câblage indépendant devient objectivement supérieur à ses bénéfices ; une fonctionnalité fondamentale ne peut être fournie qu'à travers un framework partagé ; un provider exige un processus ou runtime spécifique, ou des temps de build ou conflits de dépendances deviennent significatifs ; le cœur est découpé en modules Maven ou reçoit un `module-info.java` ; une extraction rend un nom de package inadapté ; une règle doit porter sur le bytecode. Une modification substantielle donne lieu à un nouvel ADR qui remplace explicitement celui-ci.

## Mise en œuvre

- Modules : `pom.xml:23-28` (réacteur `core` et trois adaptateurs) ; `core/pom.xml`.
- Test d'architecture : `core/src/test/java/com/nexus/architecture/CoreArchitectureTest.java`, `CoreArchitectureRules.java` (noms de règles `:18-26`, liste fermée `SQLITE_EXCEPTIONS` `:64-65`, au HEAD réduite à `java.sql.SQLException` dans `NexusApplicationFactory`), `JavaSourceGraph.java` ; baseline vide `core/src/test/resources/architecture/package-cycles.txt`.
- Packages : `core/src/main/java/com/nexus/index/service/`, `index/SemanticIndexing.java`, `index/NoopSemanticIndexing.java`, `search/candidate/`, `context/model/`, `config/ResultLimitPolicy.java` ; construction d'`OllamaEmbeddingProvider` dans `application/NexusApplicationFactory.java`.
- Description technique : [architecture d'implémentation](../../developer/architecture-implementation.md), section « Frontières vérifiées du cœur ».

## References

- [ARCH-0075](ARCH-0075-exposer-le-coeur-par-des-adaptateurs-minces-isoles.md), [ARCH-0077](ARCH-0077-positionner-nexus-comme-moteur-de-contexte-independant.md), [DATA-0073](DATA-0073-tenir-sqlite-pour-source-de-verite-et-lucene-pour-index-derive.md), [BE-0071](BE-0071-rechercher-et-classer-les-candidats-par-un-score-compose-explicable.md), [ARCH-0086](ARCH-0086-resoudre-la-configuration-et-nexus-home-dans-la-composition-root.md) (règle `getenv-composition-root` du même test).
- Changements OpenSpec `openspec/changes/archive/2026-10-31-sprint-03-filets-de-test-et-traces/`, `openspec/changes/archive/2026-11-07-sprint-07-casser-le-bloc-cyclique-du-coeur/` ; spécification `openspec/specs/core-package-boundaries/spec.md`.
