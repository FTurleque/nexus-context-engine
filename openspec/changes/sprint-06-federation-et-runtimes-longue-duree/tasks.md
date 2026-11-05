# Tasks

## 1. Découpage des méthodes longues (AUD-QUA-10)

- [ ] 1.1 `AUD-QUA-10` — Découper `FederatedContextService.build` en étapes privées nommées (validation, collecte par projet, sélection, métadonnées), sans modifier les tests ; vérifier par `./mvnw -B -ntp -pl core test -Dtest='FederatedContext*,FederatedContextBudgetScaleBenchmarkTest' -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
- [ ] 1.2 `AUD-QUA-10` — Découper `ProjectIndexingService.indexLocked` en étapes nommées (scan, diff, publication, providers externes, statut), sans modifier les tests ; vérifier par `./mvnw -B -ntp -pl core test -Dtest='ProjectIndexingService*' -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`

## 2. Vecteur de requête unique (AUD-PRF-02)

- [ ] 2.1 `AUD-PRF-02` — Calculer le vecteur de requête une fois dans le chemin fédéré (recherche et contexte) sur la requête redactée, et le transmettre aux `SemanticSearchStrategy` par projet ; vérifier par un test avec un `EmbeddingProvider` qui compte ses appels (1 appel pour 3 projets, requête transmise redactée) : `./mvnw -B -ntp -pl core test -Dtest='SemanticSearchStrategyTest,FederatedSearchService*,FederatedContextServiceTest' -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
- [ ] 2.2 `AUD-PRF-02` — Couvrir l'indisponibilité du provider : un seul appel, tous les projets dégradés avec le même diagnostic ; vérifier par le même test ciblé, puis par le workflow `semantic-search-qualification.yml` (stabilisé par `AUD-DEP-19`)

## 3. Échéance globale de la fédération (AUD-PRF-03)

- [ ] 3.1 `AUD-PRF-03` — Nouvel ADR (skill `nexus-adr`) qui complète ADR-0043 : échéance globale, vérification entre projets, pas de parallélisme, valeur par défaut et borne ; vérifier par `bash .claude/scripts/contracts.sh`
- [ ] 3.2 `AUD-PRF-03` — Appliquer l'échéance (horloge injectée) dans l'étape « collecte par projet » de `FederatedContextService` et dans `FederatedSearchService`, plafonner le délai d'embedding par le temps restant, et lister les projets non commencés dans un diagnostic public ; vérifier par un test à horloge simulée (ordre du diagnostic, projets servis inchangés) : `./mvnw -B -ntp -pl core test -Dtest='FederatedContextServiceTest,FederatedSearchServicePolicyTest' -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
- [ ] 3.3 `AUD-PRF-03` — Exposer le diagnostic à parité CLI, REST et MCP via la projection publique ; vérifier par `./mvnw -B -ntp clean install` (tests REST et MCP inclus) puis mettre à jour la documentation développeur de la fédération et vérifier `bash .claude/scripts/contracts.sh`

## 4. Métadonnées Lucene sur le reader en cache (AUD-PRF-04)

- [ ] 4.1 `AUD-PRF-04` — Lire `userData` sur le reader en cache (ADR-0045) dans `PersistentLuceneSemanticSearchIndex.isCompatible`, repli `SegmentInfos.readLatestCommit` sans reader ; vérifier par un test qui compte les ouvertures de reader sur deux recherches successives : `./mvnw -B -ntp -pl core test -Dtest='LuceneSemanticSearchIndexTest,SemanticSearchStrategyTest' -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
- [ ] 4.2 `AUD-PRF-04` — Même traitement pour `PersistentLuceneSearchIndex.isPresent` utilisé par la sonde de readiness de `NexusApplication` ; vérifier par `./mvnw -B -ntp -pl core test -Dtest='*LuceneSearchIndex*,NexusApplication*' -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`

## 5. Cache de contexte Git (AUD-PRF-06)

- [ ] 5.1 `AUD-PRF-06` — Nouvel ADR (skill `nexus-adr`) qui complète ADR-0046 : capacité alignée sur `MAX_PROJECTS`, borne mémoire, cache de récence par (dépôt, HEAD) (couvre aussi la tâche 6.1) ; vérifier par `bash .claude/scripts/contracts.sh`
- [ ] 5.2 `AUD-PRF-06` — Porter `DEFAULT_CAPACITY` à `FederatedScopePolicy.MAX_PROJECTS` et exposer les compteurs `CacheStats` dans les métadonnées `explain` ; vérifier par un test de 2 passages cycliques sur 20 projets (hits au second passage) : `./mvnw -B -ntp -pl core test -Dtest='PersistentGitContextSourceProvider*' -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
- [ ] 5.3 `AUD-PRF-06` — Étendre `git-context-cache-qualification.yml` à un scope de plus de 16 projets ; vérifier par une exécution `workflow_dispatch` verte du workflow

## 6. Cache de récence Git (AUD-PRF-07)

- [ ] 6.1 `AUD-PRF-07` — Mémoriser dans `GitRecencyCandidateEnricher` les chemins modifiés par (racine du dépôt, HEAD) dans la borne `MAX_CUMULATIVE_CHANGED_PATHS`, invalidés au changement de HEAD ; vérifier par un test (second appel sans parcours de log, nouveau commit pris en compte) : `./mvnw -B -ntp -pl core test -Dtest=GitRecencyCandidateEnricherTest -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`

## 7. Recherche de symboles scopée projet (AUD-PRF-09)

- [ ] 7.1 `AUD-PRF-09` — Migration `V0NN` (skill `nexus-schema-migration`, numéro suivant après le sprint 5) qui ajoute l'index de préfiltre fuzzy scopé projet, avec test d'upgrade ; vérifier par `./mvnw -B -ntp -pl core test -Dtest='SchemaMigrator*' -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
- [ ] 7.2 `AUD-PRF-09` — Scoper le `MATCH` FTS et le préfiltre fuzzy par projet dans `SqliteSymbolSearch` et interroger les termes en lot dans `SymbolSearchStrategy`, résultats identiques ; vérifier par `./mvnw -B -ntp -pl core test -Dtest='*SymbolSearch*,FederatedGoldenSearchCorpusTest' -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true` et par un benchmark 25/100 projets peuplés dans la même base (plan `EXPLAIN` sans `SCAN` global)

## 8. Intégration

- [ ] 8.1 Vérifier le critère de sortie du sprint (un vecteur, aucun reader hors cache, échéance qui nomme les projets non servis) sur l'ensemble : `/nx-validate` (contrats, `./mvnw -B -ntp clean install`, `scripts/self-smoke.ps1`)

## Workflow follow-up

- Ouvrir la PR vers `develop` (skill `nexus-pr-workflow`), après la fusion du sprint 5 ; merge exact-head (`gh pr merge --merge --match-head-commit`).
- Archiver le changement OpenSpec une fois la PR fusionnée (`/opsx:archive`).
