# Design

## Context

Le graphe des imports `com.nexus.*` du cœur (niveau package) contient une composante fortement connexe de 12 packages. Ses cycles bilatéraux, vérifiés sur `origin/develop` :

| Cycle | Arêtes qui le ferment | Constat |
|---|---|---|
| `index` ↔ `search` | `IndexRepository` → `search.ResultLimitPolicy` ; `ProjectIndexingService`, `IndexDocumentPublisher` → `search.SearchIndex`, `search.SearchDocument` ; `search.SearchDocument`, `SymbolSearchStrategy` → `index.*` | `AUD-ARC-02` |
| `index` ↔ `search.semantic` | `ProjectIndexingService`, `IndexDocumentPublisher` → `SemanticIndexingService` (classe concrète) | `AUD-ARC-02` |
| `index` ↔ `index.scan` | `ProjectIndexingService` → `index.scan.ProjectScanner` ; `ProjectScanner` → `index.FileCategory`, `ScannedFile`… | `AUD-ARC-02` (même déplacement) |
| `context` ↔ `context.source`, `.git`, `.skill` | `ContextSourceFragmentFactory`, `LocalGitContextSourceProvider` → `context.ContextFragment` ; `SkillContextSelector` → `context.ContextItem`, `ContextSelectionResult` | `AUD-ARC-04` |
| `search` ↔ `ranking`, `ranking.graph` | `ContextRanker`, `DeterministicContextRanker` → `search.SearchCandidate`… ; `GraphCandidateEnricher` → `search.CandidateEnricher`, `CandidateMerger` ; `ProjectGraphBuilder` → `search.ResultLimitPolicy` ; `SearchService` → `ranking.*` | `AUD-ARC-05` |
| `search.semantic` ↔ `search.semantic.ollama` | `SemanticSearchConfiguration` → `ollama.OllamaEmbeddingProvider` (instanciation l. 109) | `AUD-ARC-06` |
| `context` ↔ `security` | `PublicContextPolicy` → `context.*` | `AUD-ARC-03` (sprint 4) |

Le test d'architecture du sprint 3 (`AUD-ARC-07`) calcule ce graphe et porte une baseline de cycles qui ne peut que décroître. Chaque tâche de ce sprint retire les cycles qu'elle casse de la baseline ; le test échoue si un cycle retiré réapparaît.

## Goals / Non-Goals

**Goals:**
- Un graphe de packages du cœur acyclique, imposé par le test d'architecture.
- Des packages de modèle feuilles, dont dépendent orchestration, ports et adaptateurs.
- Aucun changement de comportement observable ni de format persistant.

**Non-Goals:**
- Découper le cœur en modules Maven ou ajouter des `module-info`.
- Réduire la visibilité des types publics.
- Renommer des champs publics des réponses CLI, REST ou MCP.

## Decisions

**D1 — Un cycle par tâche, baseline réduite à chaque pas.** L'ordre suit le sprint : `AUD-ARC-02`, `AUD-ARC-04`, `AUD-ARC-05`, `AUD-ARC-06`, puis `AUD-ARC-01`. Après chaque tâche, la baseline du test d'architecture perd les cycles cassés ; la tâche n'est terminée que lorsque le test passe avec la baseline réduite. Une tâche qui ne peut pas réduire la baseline est arrêtée et documentée plutôt que contournée par un ajout à la baseline.

**D2 — Orchestration de l'indexation hors du modèle (`AUD-ARC-02`).** `ProjectIndexingService`, `IndexDocumentPublisher` et `ExternalCodeIntelligenceRefresher` passent dans un package d'orchestration (`index.service`, nom confirmé par l'ADR). `index` garde le modèle et les ports (`IndexRepository`, `CodeSymbol`, `FileCategory`, `ScannedFile`…). `ResultLimitPolicy` et `SearchDocument` passent dans un package feuille partagé. Le service d'indexation dépend d'une interface d'indexation sémantique ; « désactivé » est une implémentation sans effet, plus `null`. Alternative écartée : déplacer `CodeSymbol` et `FileCategory` vers `search`, qui inverserait le cycle sans le casser.

**D3 — Modèle de contexte en package feuille (`AUD-ARC-04`).** `ContextFragment`, `ContextItem` et `ContextSelectionResult` passent dans `context.model`. `context` (builder) et `context.source.*` (providers) en dépendent ; `context.source.*` n'importe plus `context`.

**D4 — Modèle de candidats en package feuille (`AUD-ARC-05`).** `SearchCandidate`, `SearchSignals`, `CandidateType`, `CandidateEnricher` et `CandidateMerger` passent dans un package de candidats (`search.candidate`, nom confirmé par l'ADR). `ranking` et `ranking.graph` en dépendent et n'importent plus `search` ; `search` dépend de `ranking`. Les règles de score (ADR-0010, ADR-0025) sont inchangées, seules les déclarations de package bougent.

**D5 — Construction du fournisseur d'embeddings au composition root (`AUD-ARC-06`).** `SemanticSearchConfiguration` ne garde que des valeurs et le port `EmbeddingProvider` ; `fromEnvironment` et l'instanciation d'`OllamaEmbeddingProvider` passent dans `application` (`NexusApplicationFactory`). `search.semantic` n'importe plus `search.semantic.ollama`. Les bornes de configuration restent validées au même endroit fonctionnel (`SemanticSearchConfigurationBoundsTest` reste vert).

**D6 — Graphe acyclique imposé (`AUD-ARC-01`).** Une fois les cycles bilatéraux cassés, les cycles transitifs restants (s'il y en a) sont cassés par le même procédé, puis la baseline du test d'architecture devient vide : tout cycle de packages du cœur fait échouer le build. Les règles de sens (D2 à D5) sont ajoutées au test comme règles nommées, pour qu'un échec désigne le package fautif.

**D7 — ADR complémentaire.** Un nouvel ADR complète ADR-0004 (séparation logique par capacité) : graphe acyclique, packages de modèle feuilles, noms retenus, vérification par le test d'architecture. ADR-0004 n'est pas modifié.

## Dépendances envers d'autres sprints

- **Sprint 3, `AUD-ARC-07`** : le test d'architecture et sa baseline de cycles décroissante doivent être dans `develop` avant la première tâche. Sans lui, aucune tâche ne peut prouver la disparition d'un cycle.
- **Sprint 4, `AUD-ARC-03`** : le cycle `context` ↔ `security` est cassé au sprint 4 (déplacement de `PublicContextPolicy`, après la projection publique unique d'`AUD-QUA-09`). `AUD-ARC-01` ne peut aboutir qu'après lui.
- **Sprints 4, 5 et 6** : ils modifient `context`, `security`, `search` et l'indexation. Ce sprint démarre après leur fusion pour éviter des déplacements de fichiers concurrents.
- **Sprint 8, `AUD-ARC-12`** : la lecture de configuration par `System.getenv` dans le cœur y est traitée ; D5 déplace déjà la lecture sémantique vers le composition root, ce qui réduit ce périmètre.

## Risks / Trade-offs

- [Déplacements massifs, conflits avec des branches ouvertes] → une PR par constat ou un commit par constat, après fusion des sprints 4 à 6.
- [Les adaptateurs REST et MCP importent des types déplacés] → mise à jour des imports seulement ; le build du reactor (`./mvnw clean install`) et les tests de contrat REST/MCP vérifient l'absence de changement observable.
- [Les noms de packages proposés ne conviennent pas] → ils sont fixés par l'ADR avant le premier déplacement.
- [Un cycle transitif apparaît après les cassures bilatérales] → D6 le traite ; le test d'architecture le signale.
