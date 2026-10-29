# Proposal

## Why

Le sprint 3 de l'audit du 17/10/2026 (« Filets de test et traces ») précède les sprints 4 à 8, qui modifient la sortie publique, l'indexation et les frontières de packages. Son critère de sortie : le test d'architecture échoue sur un import interdit ; `NexusResourceTest` et `NexusMcpServerIntegrationTest` ont un `@Test` par contrat ; `GitRecencyCandidateEnricher` journalise ce qu'il attrape ; `ProjectPathGuard` a sa classe de test.

Les sept constats du sprint sont actifs sur `origin/develop` (vérifiés le 2026-10-29, après les PR #265 à #270) :

- `AUD-ARC-07` : aucun test d'architecture (`git grep -il 'archunit\|ArchitectureTest'` sur le code et les tests : rien), aucun `module-info.java`. Les imports réels sont aujourd'hui conformes : `org.apache.lucene` n'apparaît que dans `search.lucene` et `search.semantic.lucene`, `java.sql`/`org.sqlite` dans `persistence.sqlite` plus `java.sql.SQLException` dans `application` et `cli`, aucun framework applicatif dans le cœur, `project` n'importe aucun autre package `com.nexus`. Rien ne l'impose.
- `AUD-TST-07` : `NexusResourceTest` (200 lignes, 11 endpoints REST et 2 endpoints Quarkus) et `NexusMcpServerIntegrationTest` (360 lignes, 9 outils MCP) ont chacun un seul `@Test`.
- `AUD-QUA-07` : `GitRecencyCandidateEnricher.java:99-102` attrape `Exception` et renvoie les candidats inchangés, sans `System.Logger` ni diagnostic.
- `AUD-TST-02` : `ProjectPathGuard` (`core/.../security/`) n'a pas de classe de test ; `MinosProjectPathGuardTest` et `ScipProjectPathGuardTest` testent d'autres gardes. `requireDirectory`, `containsSymbolicLink` et `repositoryPath` ne sont appelés directement par aucun test.
- `AUD-TST-06` : cinq assertions de durée murale restent dans la suite par défaut (`ExternalTaskRunnerTest:34` < 750 ms, `JdtLanguageServerCodeIntelligenceProviderTest:182` 500 ms, `ProjectIndexingServiceTimeoutTest:74` < 1 500 ms, `SqliteWriteContentionIntegrationTest:244` < 1 000 ms, `SensitiveContentRedactorTest:57` 2 s), alors que `.claude/rules/tests.md` interdit les assertions sur l'horloge.
- `AUD-TST-10` : `IndexRepository` déclare neuf méthodes `default` ; `SqliteIndexRepository` est sa seule implémentation de production et les surcharge ; le fake anonyme de `ProjectGraphBuilderProjectionTest` est la seule autre implémentation.
- `AUD-TST-09` : `MinosRealIntegrationTest`, `LargeScaleSearchBaselineTest`, `SmallDeltaIndexingBaselineTest`, `RealSemanticRetrievalDiagnosticTest` et `SemanticSearchBenchmarkTest` ne sont lancés que par des scripts PowerShell (`validate-minos-integration.ps1`, `measure-iteration-16-*.ps1`, `measure-iteration-17-*.ps1`), par aucun workflow.

## What Changes

- Un test d'architecture JUnit dans `core` fige les frontières du cœur et une baseline de cycles qui ne peut que décroître.
- Les deux tests E2E sont découpés en un `@Test` par endpoint REST ou par outil MCP, sur une fixture partagée.
- `GitRecencyCandidateEnricher` journalise l'incident qu'il absorbe et reste fail-open.
- `ProjectPathGuardTest` couvre chaque méthode publique sur les cas de confinement.
- Les assertions de durée murale sont remplacées par des assertions de comportement ou des bornes dérivées du délai testé.
- Les méthodes `default` non exécutées d'`IndexRepository` sont supprimées ou testées.
- Chaque test opt-in a un workflow qui le lance, ou sort de `src/test` avec un statut d'outillage documenté.

## Capabilities

### New Capabilities
- `adapter-contract-tests` : granularité des tests de contrat REST et MCP.
- `git-recency-diagnostics` : traçabilité des incidents du signal de récence Git.
- `project-path-confinement` : comportement vérifié de `ProjectPathGuard`.
- `test-suite-reliability` : suite par défaut indépendante de l'horloge, sans code de port non exécuté, tests opt-in exécutés.

### Modified Capabilities

(aucune : `openspec/specs/` est vide)

`core-package-boundaries` est aussi une capability du changement `sprint-07-casser-le-bloc-cyclique-du-coeur`. Ce changement y ajoute les exigences du test d'architecture (frontières technologiques, baseline décroissante) ; le sprint 7 y ajoute le graphe acyclique et les règles de sens, sous des noms d'exigence distincts.

## Impact

- Tests : nouveau `core/src/test/java/com/nexus/architecture/CoreArchitectureTest.java`, nouveau `core/src/test/java/com/nexus/security/ProjectPathGuardTest.java`, `adapters/rest-quarkus/src/test/java/com/nexus/api/NexusResourceTest.java`, `adapters/mcp-java/src/test/java/com/nexus/mcp/NexusMcpServerIntegrationTest.java`, les cinq tests de durée, `ProjectGraphBuilderProjectionTest`.
- Code : `core/src/main/java/com/nexus/context/source/git/GitRecencyCandidateEnricher.java`, `core/src/main/java/com/nexus/index/IndexRepository.java`.
- Workflows : nouveau workflow manuel ou planifié pour les tests opt-in, ou déplacement de ces tests.
- Documentation : `docs/developer/architecture-implementation.md`, `docs/developer/ci-and-supply-chain.md`, `docs/developer/large-scale-baseline-runbook.md`.
- Hors périmètre : `module-info.java` et réduction de la visibilité publique des types (160/219), extraction de modules Maven.
