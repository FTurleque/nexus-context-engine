# Proposal

## Why

Les imports `com.nexus.*` du cœur forment une composante fortement connexe de 12 packages : `context`, `context.source`, `context.source.git`, `context.source.skill`, `index`, `index.scan`, `ranking`, `ranking.graph`, `search`, `search.semantic`, `search.semantic.ollama`, `security`. Indexation, recherche, ranking, contexte et sécurité ne sont donc pas séparables : modifier un type de modèle touche les 12 packages, et l'extraction de modules prévue par ADR-0004, qui rend « obligatoire » la séparation logique par capacité, est impossible tant que ces cycles existent.

Les cycles viennent de types de modèle rangés dans le package qui orchestre (`SearchCandidate` dans `search`, `ContextFragment` dans `context`, `ResultLimitPolicy` dans `search`), d'une orchestration rangée avec le modèle (`ProjectIndexingService` dans `index`) et d'un port qui construit son adaptateur (`SemanticSearchConfiguration` instancie `OllamaEmbeddingProvider`). Ce changement porte le sprint 7 de l'audit de code du 17/10/2026 : il casse chaque cycle sous la protection du test d'architecture livré au sprint 3, puis rend le graphe acyclique obligatoire.

## What Changes

Constats traités, dans l'ordre interne du sprint (tous actifs sur `origin/develop` @ `17a00866`, imports vérifiés) :

- `AUD-ARC-02` — cycles `index` ↔ `search`, `index` ↔ `search.semantic` et `index` ↔ `index.scan` : l'orchestration de l'indexation (`ProjectIndexingService`, `IndexDocumentPublisher`, `ExternalCodeIntelligenceRefresher`) sort de `index` vers un package d'orchestration ; `ResultLimitPolicy` et `SearchDocument` passent dans un package feuille ; le service d'indexation dépend d'un port d'indexation sémantique, plus de la classe `SemanticIndexingService` (ni de `null` pour « désactivé »).
- `AUD-ARC-04` — cycles `context` ↔ `context.source`, `.git`, `.skill` : `ContextFragment`, `ContextItem` et `ContextSelectionResult` passent dans un package modèle dont dépendent `context` et `context.source.*`.
- `AUD-ARC-05` — cycles `search` ↔ `ranking` et `search` ↔ `ranking.graph` : `SearchCandidate`, `SearchSignals`, `CandidateType`, `CandidateEnricher` et `CandidateMerger` passent dans un package modèle de candidats ; `search` dépend de `ranking`, jamais l'inverse.
- `AUD-ARC-06` — cycle `search.semantic` ↔ `search.semantic.ollama` : la lecture d'environnement et la construction d'`OllamaEmbeddingProvider` quittent `SemanticSearchConfiguration` pour le composition root.
- `AUD-ARC-01` (constat chapeau) — le graphe des packages du cœur est acyclique et le test d'architecture l'impose avec une baseline de cycles vide.

Prérequis traités ailleurs : `AUD-ARC-07` (test d'architecture, sprint 3) et `AUD-ARC-03` (cycle `context` ↔ `security`, sprint 4). Aucun n'est fermé sur `origin/develop` à la rédaction : aucun test d'architecture n'existe (`git grep -il "archunit\|ArchitectureTest" origin/develop` vide) et `PublicContextPolicy` est toujours dans `security`.

Un nouvel ADR complète ADR-0004 : graphe de packages du cœur acyclique, packages de modèle feuilles, règle vérifiée par le test d'architecture.

## Capabilities

### New Capabilities
- `core-package-boundaries`: règles de dépendance entre packages du cœur, vérifiées par le test d'architecture (graphe acyclique, sens autorisés entre modèle, ports, orchestration et adaptateurs).

### Modified Capabilities

(aucune : `openspec/specs/` est vide)

## Impact

- Code : déplacements de types dans `core/src/main/java/com/nexus/{index,search,search.semantic,ranking,context}` ; mises à jour d'imports dans le cœur, `adapters/rest-quarkus` (`ApiMapper`, `NexusResource`, `FederatedResource`, `NexusApiApplicationService`) et `adapters/mcp-java` (`McpResultMapper`, `McpToolArguments`, `NexusMcpTools`) ; `NexusApplicationFactory` construit le fournisseur d'embeddings.
- Comportement observable : aucun. Les réponses CLI, REST et MCP, les formats persistants SQLite et Lucene et les noms de champs publics sont inchangés.
- Tests : test d'architecture (baseline de cycles vide), tests déplacés avec leurs types.
- Documentation : nouvel ADR, vue arc42 des building blocks, `docs/architecture/` ; contrats documentaires.
- Hors périmètre : découpage en modules Maven, `module-info`, réduction du nombre de types publics.
