# Proposition

## Why

Le sprint 7 a cassé le bloc cyclique du cœur « sous la protection du test d'architecture ». Ce test ne lit que les `import` : une dépendance écrite par nom pleinement qualifié (`com.nexus.x.Y` sans import) n'entre pas dans son graphe, et ce style se propage dans le nouveau code. Un cycle ou une remontée depuis `project` peut donc revenir sans échec. Par ailleurs, l'atomicité promise par DATA-0062 (« SQLite canonique, Lucene dérivé ») repose, entre le commit Lucene et la publication SQLite, sur le statut `FAILED` et la garde `READY`, sans test d'échec sur ce chemin.

Source : `docs/project/audits/2026-10-17/findings.json` (sprint 7, constats ajoutés le 07/11), `etat-2026-11-09.md`.

## What Changes

- `AUD-ARC-20`
  - [Existant vérifié] `JavaSourceGraph.packageGraph` ne parcourt que `file.imports()` (`core/src/test/java/com/nexus/architecture/JavaSourceGraph.java:103-120`) ; `CoreArchitectureRules.WATCHED_QUALIFIED_PREFIXES` ne contient que les préfixes framework, Lucene, SQLite et `getenv` (`core/src/test/java/com/nexus/architecture/CoreArchitectureRules.java:40-42`), donc les règles de sens (`projectLeaf` et suivantes) ne voient pas `com.nexus.*` qualifié.
  - [Existant vérifié] Le nouveau code ajoute des références qualifiées : `FederatedSearchService.java:73` (`com.nexus.project.ProjectReadiness::requireReady`), `DefaultContextBuilder.java:188`. L'arête `cli → security` n'existe que par `com.nexus.security.PublicDiagnosticPolicy` dans `core/src/main/java/com/nexus/cli/CliRenderer.java:257`.
  - [Existant vérifié] L'audit a recalculé le graphe imports + références qualifiées : aucune arête de cycle nouvelle (baseline vide, `core/src/test/resources/architecture/package-cycles.txt`). `security` n'importe rien de `cli` (`grep com.nexus.cli core/src/main/java/com/nexus/security` vide).
  - [Exigence] Le graphe et les règles de sens lisent aussi les références `com.nexus.*` pleinement qualifiées ; un cas négatif le prouve.
- `AUD-TST-16`
  - [Existant vérifié] `publication.commitDerivedIndexes(); publication.publishCanonical(...)` (`core/src/main/java/com/nexus/index/service/ProjectIndexingService.java:296-297`) ; un échec passe par `markFailed` (`:237-239`, `:390-400`) ; le passage suivant reconstruit tout car `project.indexStatus() != IndexStatus.READY` (`:208`).
  - [Existant vérifié] `ProjectIndexingPublicationTest` (4 tests) n'injecte l'échec que pendant l'analyse (`aFailureAfterSeveralBatchesLeavesThePreviousGenerationAndDerivedIndexesIntact`) ; aucun test ne fait lever `publish` après le commit Lucene, ni le commit sémantique après le commit lexical.
  - [Exigence] Le comportement de reprise sur ces deux échecs est spécifié et testé.

## Type de changement

architectural (garde-fou du cœur) et tests : profil complet.

## Périmètre et exclusions

**Dans le périmètre :**
- `JavaSourceGraph`, `CoreArchitectureRules`, `CoreArchitectureTest` et le traitement de l'arête `cli → security`.
- Tests d'échec de `ProjectIndexingPublicationTest` (ou d'un test voisin) sur les deux points de la barrière de publication.

**Hors périmètre :**
- Réécrire en `import` les 39 références qualifiées existantes : le test les lira ; seule celle de `CliRenderer.java:257` est traitée (voir design).
- Changer l'ordre de la barrière de publication ou rendre la publication atomique entre Lucene et SQLite : le comportement de reprise existant est contractualisé, pas modifié.

## Capabilities

### New Capabilities

### Modified Capabilities
- `core-package-boundaries`: ajout d'une exigence sur la lecture des références pleinement qualifiées par le test d'architecture.
- `project-indexing-pipeline`: ajout d'une exigence sur la reprise après un échec entre le commit des index dérivés et la publication canonique.

## Impact

- Tests : `core/src/test/java/com/nexus/architecture/JavaSourceGraph.java`, `CoreArchitectureRules.java`, `CoreArchitectureTest.java`, `core/src/test/java/com/nexus/index/ProjectIndexingPublicationTest.java`.
- Code de production : `core/src/main/java/com/nexus/cli/CliRenderer.java` (forme de la référence, si le design retient l'import).
- Documentation : `docs/developer/architecture-implementation.md`, `docs/developer/indexing-pipeline.md`, `docs/developer/openspec-traceability.md`.
- Aucune migration, aucun format persisté.
