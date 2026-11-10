# Tasks

## 1. Références pleinement qualifiées dans le test d'architecture

- [ ] 1.1 `AUD-ARC-20` — Ajouter à `JavaSourceGraph` l'extraction des références `com.nexus.<package>.<Type>` hors commentaires et chaînes, les injecter dans `packageGraph` (`:103-120`) et dans les règles de sens via `WATCHED_QUALIFIED_PREFIXES` (`CoreArchitectureRules.java:40-42`) ; couvre `core-package-boundaries › Le test d'architecture lit les références pleinement qualifiées du cœur` ; vérifier : `./mvnw -B -ntp -pl core test -Dtest=CoreArchitectureTest -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
- [ ] 1.2 `AUD-ARC-20` — Remplacer `com.nexus.security.PublicDiagnosticPolicy` qualifié par un `import` dans `core/src/main/java/com/nexus/cli/CliRenderer.java:257` et vérifier que l'arête `cli → security` n'entre dans aucun cycle ni aucune règle de sens ; traiter de même toute violation révélée par 1.1 (correction ou écart documenté) ; couvre le scénario « Graphe courant » ; vérifier : même commande, baseline `core/src/test/resources/architecture/package-cycles.txt` toujours vide
- [ ] 1.3 `AUD-ARC-20` — Ajouter à `CoreArchitectureTest` deux cas négatifs sur sources temporaires : `com.nexus.ranking` qui référence `com.nexus.search.SearchService` sans import (cycle) et `com.nexus.project` qui le référence sans import (`projectLeaf`), plus un cas positif où la même référence n'apparaît que dans un commentaire ; couvre les scénarios « Cycle par nom pleinement qualifié » et « Remontée depuis project par nom pleinement qualifié » ; vérifier : même commande, puis les deux cas négatifs en échec quand l'extraction de 1.1 est désactivée dans une copie

## 2. Échecs à la barrière de publication

- [ ] 2.1 `AUD-TST-16` — Ajouter à `core/src/test/java/com/nexus/index/ProjectIndexingPublicationTest.java` un test où le proxy de session lève dans `publish` après un commit Lucene réussi : exception propagée, projet `FAILED`, génération canonique inchangée, aucune ligne de `index_staged_files` pour le projet, recherche refusée par la garde de disponibilité, puis indexation suivante en rebuild complet qui finit `READY` ; couvre `project-indexing-pipeline › Un échec à la barrière de publication rend le projet à reconstruire` (scénarios « Publication canonique en échec après le commit Lucene », « Passage suivant », « Aucun service avant READY ») ; vérifier : `./mvnw -B -ntp -pl core test -Dtest=ProjectIndexingPublicationTest -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
- [ ] 2.2 `AUD-TST-16` — Ajouter un test où la session sémantique lève dans `commit` après le commit lexical : exception propagée, projet `FAILED`, `publish` jamais appelé, génération inchangée, rebuild complet au passage suivant ; couvre le scénario « Commit sémantique en échec après le commit lexical » ; vérifier : même commande, puis couverture JaCoCo des branches d'échec d'`IndexPublication` relevée dans `core/target/site/jacoco/jacoco.csv` après `./mvnw clean install`

## 3. Documentation durable

- [ ] 3.1 `AUD-ARC-20`, `AUD-TST-16` — Compléter `docs/developer/architecture-implementation.md#openspec-core-package-boundaries` (références qualifiées, organigramme du graphe) et `docs/developer/indexing-pipeline.md#openspec-project-indexing-pipeline` (séquence d'échec à la barrière, transitions READY → FAILED → READY) ; vérifier : `bash .claude/scripts/contracts.sh`
- [ ] 3.2 `AUD-ARC-20`, `AUD-TST-16` — Mettre à jour `docs/developer/openspec-traceability.md` : nouvelles lignes de `core-package-boundaries` et `project-indexing-pipeline` avec les tests de 1.3, 2.1 et 2.2 ou « test non écrit » ; vérifier : `bash scripts/release/test-openspec-doc-contracts.sh` après synchronisation des specs

## Workflow follow-up

- Une PR vers `develop` (skill `nexus-pr-workflow`), merge exact-head.
- Mettre à jour le statut de `AUD-ARC-20` et `AUD-TST-16` dans `docs/project/audits/2026-10-17/findings.json` avec la PR.
- Synchroniser les specs et archiver le changement une fois les tâches vérifiées.
