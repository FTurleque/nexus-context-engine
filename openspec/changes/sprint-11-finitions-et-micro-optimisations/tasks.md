# Tasks

## 1. Graphe dérivé : documentation, code mort et décision

- [ ] 1.1 `AUD-QUA-02` — Réécrire la section « Graphe dérivé par génération » de `docs/developer/search-ranking.md` (projection bornée `findGraphNeighbors`, `MAX_NEIGHBOR_EDGES`, aucun cache par génération) ; ajouter au contrat documentaire le refus de « ProjectGraph cache » ; vérifier par `bash .claude/scripts/contracts.sh`
- [ ] 1.2 `AUD-QUA-02` — Supprimer `core/src/main/java/com/nexus/ranking/graph/ProjectGraph.java` et renommer `ProjectGraphBuilder` en `GraphNeighborhoodProjection` (appelants, `NexusApplication`, test `ProjectGraphBuilderProjectionTest` renommé) ; vérifier par `git grep -n "ProjectGraph\b\|ProjectGraphBuilder" -- '*.java'` vide puis `./mvnw -B -ntp -pl core test -Dtest='GraphNeighborhoodProjection*,GraphCandidateEnricher*,SqliteGraphNeighborhoodProjectionTest' -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
- [ ] 1.3 `AUD-QUA-02` — Nouvel ADR (skill `nexus-adr`) qui complète ADR-0026 : voisinage projeté à la demande, borné, sans matérialisation ni cache ; index du registre et vue arc42 des décisions mis à jour ; vérifier par `bash .claude/scripts/contracts.sh`

## 2. Watch item FTS5 trigram

- [ ] 2.1 `AUD-QUA-13` — Reformuler dans `docs/developer/current-limitations.md` le watch item en « évolution au-delà du FTS5 trigram livré par V008 » (ou le retirer) ; ajouter au contrat le refus de « nouveau moteur FTS/trigram » ; vérifier par `bash .claude/scripts/contracts.sh`

## 3. Instruction primaire des agents

- [ ] 3.1 `AUD-QUA-03` — Remplacer dans `AGENTS.md` `mvn clean install` par `./mvnw clean install` (`.\mvnw.cmd clean install` sous Windows) ; ajouter au contrat le refus d'une commande `mvn ` hors wrapper dans `AGENTS.md` ; vérifier par `bash .claude/scripts/contracts.sh` puis `pwsh -NoProfile -File scripts/self-smoke.ps1` (étape 9 : `AGENTS.md` retenu)

## 4. Journal SQLite

- [ ] 4.1 `AUD-PRF-10` — Nouvel ADR (skill `nexus-adr`) qui complète ADR-0052 : journal rollback conservé, mesures DELETE/WAL de `docs/developer/scale-regression-results.md`, condition de réévaluation de `docs/developer/scale-regression-benchmark.md` ; renvoyer ces deux documents à l'ADR ; vérifier par `bash .claude/scripts/contracts.sh`
- [ ] 4.2 `AUD-PRF-10` — Ajouter à `SqliteDatabaseHardeningTest` une assertion `PRAGMA journal_mode` = `delete` sur une base ouverte par `SqliteDatabase`, avec message qui cite l'ADR de 4.1 ; vérifier par `./mvnw -B -ntp -pl core test -Dtest=SqliteDatabaseHardeningTest -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`

## 5. Langue des messages d'erreur

- [ ] 5.1 `AUD-QUA-11` — Écrire dans `.claude/rules/java-core.md` la frontière de D4 (messages atteignables depuis CLI, REST, MCP ou configuration en français ; préconditions internes dans la langue du fichier) ; vérifier par `bash .claude/scripts/contracts.sh`
- [ ] 5.2 `AUD-QUA-11` — Traduire les messages concernés (requêtes et budgets de `context`, `search` et `FederatedContextService`, configuration et provider Ollama, limites, chemins) et mettre à jour les tests qui les comparent ; vérifier par `./mvnw -B -ntp clean install`
- [ ] 5.3 `AUD-QUA-11` — Ajouter un test qui vérifie, pour une requête vide et une dimension Ollama hors bornes, un message en français via la façade ; vérifier par `./mvnw -B -ntp -pl core test -Dtest='FederatedContextService*,OllamaEmbeddingProvider*' -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`

## 6. Troncature de fragment

- [ ] 6.1 `AUD-PRF-13` — Remplacer dans `BudgetedContextSelector.truncate` la boucle de reconstruction par une dichotomie sur le nombre de lignes (D5) ; documenter dans `TokenEstimator` l'exigence de croissance ; vérifier par `./mvnw -B -ntp -pl core test -Dtest='BudgetedContextSelector*' -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
- [ ] 6.2 `AUD-PRF-13` — Ajouter un test qui compare, sur des fragments multilignes Unicode et des budgets variés, le résultat de la dichotomie à celui de la boucle linéaire de référence ; vérifier par la même commande

## 7. Voisinage graphe sortant

- [ ] 7.1 `AUD-PRF-16` — Paginer `OUTGOING_GRAPH_SQL` par clé `(r.source_ref, r.target_ref) > (?, ?)` et reprendre la boucle depuis le dernier couple lu ; couvrir un cas de plusieurs pages d'imports non résolus ; vérifier par `./mvnw -B -ntp -pl core test -Dtest='SqliteGraphNeighborhoodProjectionTest,SqliteGraphQueryPlanTest' -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true` et `git grep -n "OFFSET" -- core/src/main/java/com/nexus/persistence/sqlite/SqliteGraphQueries.java` vide

## 8. Motifs compilés

- [ ] 8.1 `AUD-PRF-17` — Remplacer `slashPath.matches("^[A-Za-z]:.*")` dans `RepositoryPath.fromProvider` par un test de caractères ; vérifier par `./mvnw -B -ntp -pl core test -Dtest=RepositoryPathTest -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true` et `git grep -n "\.matches(\"" -- core/src/main/java/com/nexus/paths/RepositoryPath.java` vide
- [ ] 8.2 `AUD-PRF-17` — Ajouter à `RepositoryGlobMatcher` un cache borné des motifs compilés (D7) et un test de borne et de réutilisation ; vérifier par `./mvnw -B -ntp -pl core test -Dtest='InstructionDiscoverySupportTest,RepositoryGlobMatcher*' -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`

## 9. Clôture

- [ ] 9.1 Mettre à jour le statut des constats du sprint 11 dans `docs/audits/2026-10-17/findings.json` (fermés avec la PR) ; vérifier par `/nx-validate` (contrats, `./mvnw clean install`, `scripts/self-smoke.ps1`)

## Workflow follow-up

- Une PR vers `develop` (skill `nexus-pr-workflow`), à ouvrir après la fusion du sprint 7 ; merge exact-head (`gh pr merge --merge --match-head-commit`).
- Archiver le changement OpenSpec (`openspec archive sprint-11-finitions-et-micro-optimisations`) une fois la PR fusionnée.
