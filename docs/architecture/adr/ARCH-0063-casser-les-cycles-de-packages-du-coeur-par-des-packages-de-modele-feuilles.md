---
id: ARCH-0063
title: "Casser les cycles de packages du cœur par des packages de modèle feuilles"
domain: architecture
rules: false
---

# Casser les cycles de packages du cœur par des packages de modèle feuilles

- **Statut** : Proposed
- **Date** : 2026-11-07
- **Origine** : audit du 2026-10-17, constats AUD-ARC-01, AUD-ARC-02, AUD-ARC-04, AUD-ARC-05 et AUD-ARC-06 ; changement OpenSpec `sprint-07-casser-le-bloc-cyclique-du-coeur`
- **Complète** : [ARCH-0004](ARCH-0004-demarrer-avec-un-seul-module-maven.md), [ARCH-0059](ARCH-0059-verifier-les-frontieres-du-coeur-par-un-test-d-architecture.md)

## Context

[ARCH-0004](ARCH-0004-demarrer-avec-un-seul-module-maven.md) garde le cœur dans un seul module Maven et rend obligatoire la séparation logique par packages. [ARCH-0059](ARCH-0059-verifier-les-frontieres-du-coeur-par-un-test-d-architecture.md) a livré le test d'architecture (`CoreArchitectureTest`) et une baseline de cycles décroissante, `core/src/test/resources/architecture/package-cycles.txt`.

Au départ de ce changement, cette baseline compte 22 arêtes internes à une composante fortement connexe de 12 packages : `context`, `context.source`, `context.source.git`, `context.source.skill`, `index`, `index.scan`, `ranking`, `ranking.graph`, `search`, `search.semantic`, `search.semantic.ollama`. `security` n'en fait plus partie : `PublicContextPolicy` est dans `exposure` (`core/src/main/java/com/nexus/exposure/PublicContextPolicy.java`). Les cycles viennent de quatre causes vérifiées par les imports :

- des types de modèle rangés dans le package qui orchestre : `SearchCandidate`, `SearchSignals`, `CandidateType`, `CandidateEnricher`, `CandidateMerger` dans `search`, `ContextFragment`, `ContextItem`, `ContextSelectionResult` dans `context`, `ResultLimitPolicy` et `SearchDocument` dans `search` ;
- une orchestration rangée avec le modèle : `ProjectIndexingService`, `IndexDocumentPublisher`, `IndexPublication` et `ExternalCodeIntelligenceRefresher` dans `index`, qui importent `search.SearchIndex` et `search.semantic.SemanticIndexingService` alors que `search` importe `index` ;
- un service d'indexation qui dépend d'une classe concrète de `search.semantic` et reçoit `null` pour « sémantique désactivé » ;
- un port qui construit son adaptateur : `SemanticSearchConfiguration.fromEnvironment` instancie `OllamaEmbeddingProvider`.

La question est : **quels packages, et quel sens de dépendance, rendent le graphe des packages du cœur acyclique sans extraire de module Maven ni changer un comportement observable ?**

## Options envisagées

- **Option A — packages de modèle feuilles et orchestration séparée** : déplacer les types partagés dans des packages dont dépendent à la fois l'orchestration et le ranking ou les sources, extraire l'orchestration de l'indexation, inverser la dépendance vers la sémantique par un port, construire l'adaptateur Ollama au composition root. Déplacements de types et changements d'imports ; aucun changement de comportement.
- **Option B — inverser les cycles** : déplacer `CodeSymbol` et `FileCategory` vers `search`, ou `SearchService` vers `ranking`. Le cycle change de sens sans disparaître et le modèle d'indexation devient dépendant de la recherche.
- **Option C — modules Maven ou `module-info.java`** : barrière de compilation réelle. Impossible tant que les cycles existent, et contraire à [ARCH-0004](ARCH-0004-demarrer-avec-un-seul-module-maven.md) sans besoin d'extraction observé.

## Decision

**Option retenue : Option A.**

Le graphe des dépendances entre packages du code de production du cœur est acyclique et le reste : la baseline de `CoreArchitectureTest` est vide et tout cycle fait échouer le build. Packages et noms retenus :

- `com.nexus.index.service` : orchestration de l'indexation (`ProjectIndexingService`, `IndexDocumentPublisher`, `IndexPublication`, `ExternalCodeIntelligenceRefresher`). `com.nexus.index` garde le modèle et les ports.
- `SearchDocument` passe dans `com.nexus.index` : il dépend de `CodeSymbol` et `FileCategory`, donc il ne peut pas résider dans un package feuille que `index` importerait. `ResultLimitPolicy` passe dans `com.nexus.config`, déjà feuille.
- Port `com.nexus.index.SemanticIndexing`, implémenté par `SemanticIndexingService` ; `NoopSemanticIndexing` répond `enabled() == false` et remplace `null`.
- `com.nexus.search.candidate` : `SearchCandidate`, `SearchSignals`, `CandidateType`, `CandidateEnricher`, `CandidateMerger`.
- `com.nexus.context.model` : `ContextFragment`, `ContextItem`, `ContextSelectionResult`.
- `SemanticSearchConfiguration` ne porte que des valeurs et le port `EmbeddingProvider` ; `NexusApplicationFactory` lit l'environnement et construit `OllamaEmbeddingProvider`.

Règles de score, formats SQLite et Lucene et champs publics CLI, REST et MCP restent inchangés. La décision ne crée pas de module Maven, ne réduit pas la visibilité des types publics et ne contrôle pas les imports des adaptateurs.

## Sens de dépendance autorisés

Une flèche va du dépendant vers sa dépendance.

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

## Do's and Don'ts

### Do

- Ranger un type partagé entre orchestration et ranking, sources ou stockage dans un package de modèle que ces packages importent, et que lui n'importe pas en retour.
- Construire les adaptateurs (Ollama, Lucene, SQLite) dans `NexusApplicationFactory`, et faire dépendre l'orchestration d'un port.
- Représenter une capacité désactivée par une implémentation sans effet du port, pas par `null`.

### Don't

- Ne pas ajouter d'arête à la baseline de cycles ; elle est vide.
- Ne pas importer `com.nexus.search` depuis `com.nexus.index`, `com.nexus.context` depuis `com.nexus.context.source`, `com.nexus.search` depuis `com.nexus.ranking`, ni `com.nexus.search.semantic.ollama` depuis `com.nexus.search.semantic`.
- Ne pas déplacer un type de modèle vers le package qui l'orchestre.

## Consequences

### Positives

- Indexation, recherche, ranking et contexte deviennent séparables : modifier un type de modèle ne touche plus tous les packages, et une extraction ultérieure en module ([ARCH-0004](ARCH-0004-demarrer-avec-un-seul-module-maven.md)) n'est plus bloquée par les cycles.
- Un cycle réintroduit échoue au build avec les packages fautifs.
- L'indexation sémantique désactivée n'est plus un `null` à tester partout.

### Négatives

- Déplacements massifs : les imports de nombreux fichiers du cœur, des adaptateurs REST et MCP et des tests changent ; les branches ouvertes qui touchent ces types conflictuent.
- `SearchDocument` reste dans un package nommé « index » alors que ses consommateurs sont surtout des index de recherche.
- `com.nexus.index.service` et `com.nexus.search.candidate` ajoutent deux packages au cœur.

### Risques

| Risque | Impact | Mesure de maîtrise |
|---|---|---|
| Conflit avec un travail parallèle sur `ProjectIndexingService` ou `FederatedContextService` | Moyen | Déplacements par `git mv`, aucun refactoring du corps des méthodes |
| Un changement de comportement passe inaperçu dans un déplacement | Élevé | `./mvnw clean install` (tests de contrat REST et MCP) et `scripts/self-smoke.ps1` sur le HEAD final |
| Une visibilité package-private casse à l'extraction d'un type | Faible | Erreur de compilation ; ouvrir le minimum nécessaire |

## Compliance and Enforcement

- Contrôle automatique : `CoreArchitectureTest` (règle `cycles-baseline` avec baseline vide, règles de sens nommées `index-sans-search`, `context-source-sans-context`, `ranking-sans-search`, `search-semantic-sans-ollama`), exécuté par `./mvnw test` dans le module `core`.
- Revue : toute nouvelle dépendance entre packages du cœur respecte le tableau de sens ci-dessus.

## Conditions de réexamen

La décision est réévaluée si le cœur est découpé en modules Maven ou reçoit un `module-info.java`, si une extraction rend un nom de package inadapté, ou si une règle doit porter sur le bytecode. Une modification substantielle donne lieu à un nouvel ADR qui remplace explicitement celui-ci.

## Mise en œuvre

(Complété à l'implémentation : écarts à la conception, preuves.)

## References

- [ARCH-0004](ARCH-0004-demarrer-avec-un-seul-module-maven.md), [ARCH-0059](ARCH-0059-verifier-les-frontieres-du-coeur-par-un-test-d-architecture.md), [BE-0010](BE-0010-adopter-un-ranking-hybride-deterministe-et-explicable.md), [BE-0025](BE-0025-normaliser-les-signaux-et-calculer-un-score-compose-explicable.md)
- Changement OpenSpec `openspec/changes/archive/2026-11-07-sprint-07-casser-le-bloc-cyclique-du-coeur/` et spécification `openspec/specs/core-package-boundaries/spec.md`
- [Architecture d'implémentation](../../developer/architecture-implementation.md), section « Frontières vérifiées du cœur »
