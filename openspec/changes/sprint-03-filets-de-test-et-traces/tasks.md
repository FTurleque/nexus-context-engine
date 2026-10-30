# Tasks

## 1. Test d'architecture

- [x] 1.1 `AUD-ARC-07` — Créer `core/src/test/java/com/nexus/architecture/CoreArchitectureTest.java` (lecture des sources de `core/src/main/java`, graphe des packages, sans dépendance nouvelle) avec les règles nommées `core-sans-framework`, `lucene-confine`, `sqlite-confine` (exceptions fermées `java.sql.SQLException` dans `NexusApplication`, `NexusApplicationFactory`, `NexusCli`) et `project-feuille`, plus un test du parseur (import statique, nom pleinement qualifié, import en commentaire) ; vérifier par `./mvnw -B -ntp -pl core test -Dtest='*Architecture*' -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
- [x] 1.2 `AUD-ARC-07` — Ajouter la baseline `core/src/test/resources/architecture/package-cycles.txt` (arêtes de cycle actuelles, triées) et la règle qui échoue sur une arête de cycle nouvelle comme sur une ligne de baseline devenue fausse ; prouver chaque règle par un cas négatif sur une fixture de sources dans `@TempDir` ; vérifier par la même commande `-Dtest='*Architecture*'`
- [x] 1.3 `AUD-ARC-07` — Nouvel ADR (skill `nexus-adr`) qui complète ADR-0004 : frontières du cœur vérifiées par `CoreArchitectureTest`, règles nommées, baseline de cycles décroissante ; décrire le test dans `docs/developer/architecture-implementation.md` ; vérifier par `bash .claude/scripts/contracts.sh`

## 2. Tests de contrat REST et MCP

- [x] 2.1 `AUD-TST-07` — Découper `NexusResourceTest` en un `@Test` par endpoint (`projects`, `index` POST et GET, `search`, `context`, `explain/search`, `explain/context`, `federated/search`, `federated/context`, `/q/health/ready`, `/q/metrics`) sur une fixture de classe, chaque test gardant ses assertions de non-fuite ; vérifier par `./mvnw -B -ntp -pl adapters/rest-quarkus -am test -Dtest=NexusResourceTest -Dsurefire.failIfNoSpecifiedTests=false` et constater dans le rapport Surefire au moins 11 tests
- [x] 2.2 `AUD-TST-07` — Découper `NexusMcpServerIntegrationTest` en un `@Test` par outil (`list_projects`, `search_code`, `build_context`, `explain_context`, `find_symbol`, `find_usages`, `search_across_projects`, `build_context_across_projects`, `explain_context_across_projects`) sur une fixture de classe ; vérifier par `./mvnw -B -ntp -pl adapters/mcp-java -am test -Dtest=NexusMcpServerIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false` et constater au moins 9 tests

## 3. Traces de la récence Git

- [x] 3.1 `AUD-QUA-07` — Ajouter un `System.Logger` à `GitRecencyCandidateEnricher`, journaliser au niveau `WARNING` le type d'exception et un message sans chemin absolu dans la branche `catch (Exception)`, garder `RepositoryNotFoundException` silencieux et ajouter la raison « récence Git indisponible » quand une explication est demandée ; couvrir par un test qui provoque un incident JGit et vérifie : candidats inchangés, un enregistrement de log, aucun chemin absolu dans le message ; vérifier par `./mvnw -B -ntp -pl core test -Dtest='GitRecencyCandidateEnricher*' -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`

## 4. Confinement des chemins

- [x] 4.1 `AUD-TST-02` — Créer `core/src/test/java/com/nexus/security/ProjectPathGuardTest.java` : chaque méthode publique (`resolve(Path)`, `resolve(RepositoryPath)`, `requireRegularFile`, `requireDirectory`, `containsSymbolicLink`, `repositoryPath`) × traversal `..`, chemin absolu, symlink final, symlink intermédiaire, racine non répertoire, cible hors racine ; symlinks via `SymbolicLinks.createOrSkip` ; vérifier par `./mvnw -B -ntp -pl core test -Dtest=ProjectPathGuardTest -Dsurefire.failIfNoSpecifiedTests=false` puis la couverture de branches de `ProjectPathGuard` dans `core/target/site/jacoco/jacoco.csv`, qui passe au-dessus de 54,2 %

## 5. Fiabilité de la suite par défaut

- [ ] 5.1 `AUD-TST-06` — Remplacer les assertions de durée murale de `ExternalTaskRunnerTest:34`, `ProjectIndexingServiceTimeoutTest:74` et `SqliteWriteContentionIntegrationTest:244` par une assertion d'état (tâche abandonnée, compteur de timeouts) ou une borne égale au délai du worker récalcitrant ; porter `JdtLanguageServerCodeIntelligenceProviderTest:182` et `SensitiveContentRedactorTest:57` à une marge d'au moins ×5 sur la durée nominale, sans abaisser de seuil ; vérifier par `./mvnw -B -ntp -pl core test -Dtest='ExternalTaskRunnerTest,ProjectIndexingServiceTimeoutTest,SqliteWriteContentionIntegrationTest,JdtLanguageServerCodeIntelligenceProviderTest,SensitiveContentRedactorTest' -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true` et par le check `Windows gate`
- [x] 5.2 `AUD-TST-10` — Rendre abstraites les méthodes `default` d'`IndexRepository` que seule `SqliteIndexRepository` implémente, adapter le fake de `ProjectGraphBuilderProjectionTest`, et tester par un fake minimal toute méthode `default` conservée ; vérifier par `./mvnw -B -ntp -pl core test -Dtest='ProjectGraphBuilderProjectionTest,SqliteIndexRepository*' -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true` puis `./mvnw -B -ntp clean install` et la ligne `IndexRepository` de `core/target/site/jacoco/jacoco.csv` sans ligne manquée
- [ ] 5.3 `AUD-TST-09` — Pour chacun de `MinosRealIntegrationTest`, `LargeScaleSearchBaselineTest`, `SmallDeltaIndexingBaselineTest`, `RealSemanticRetrievalDiagnosticTest` et `SemanticSearchBenchmarkTest` : soit l'ajouter à un workflow `workflow_dispatch` (planifié si son prérequis est installable sur le runner), soit le déplacer hors de `src/test` vers un outillage de mesure ; consigner le statut de chacun dans `docs/developer/ci-and-supply-chain.md` en tenant compte de l'archivage des scripts au sprint 2 ; vérifier par `bash .claude/scripts/contracts.sh` et un `workflow_dispatch` du workflow ajouté

## 6. Validation

- [ ] 6.1 Lancer `/nx-validate` (contrats, `./mvnw clean install`, `scripts/self-smoke.ps1`) sur la branche avant la PR

## Workflow follow-up

- Ouvrir la PR vers `develop` (skill `nexus-pr-workflow`), merge exact-head en merge commit.
- Archiver le changement OpenSpec une fois la PR fusionnée (`openspec archive sprint-03-filets-de-test-et-traces`).
