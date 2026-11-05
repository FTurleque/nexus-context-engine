# Tasks

## 1. Décision et préalables

- [ ] 1.1 `AUD-ARC-01` — Nouvel ADR (skill `nexus-adr`) qui complète ADR-0004 : graphe de packages du cœur acyclique, packages de modèle feuilles et leurs noms, vérification par le test d'architecture ; vérifier par `bash .claude/scripts/contracts.sh`
- [ ] 1.2 `AUD-ARC-01` — Vérifier que le test d'architecture d'`AUD-ARC-07` (sprint 3) et la correction d'`AUD-ARC-03` (sprint 4) sont dans `develop`, et relever la baseline de cycles initiale ; vérifier par `./mvnw -B -ntp -pl core test -Dtest='*Architecture*' -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`

## 2. Cycle index ↔ search (AUD-ARC-02)

- [ ] 2.1 `AUD-ARC-02` — Déplacer `ProjectIndexingService`, `IndexDocumentPublisher` et `ExternalCodeIntelligenceRefresher` dans le package d'orchestration, et `ResultLimitPolicy`, `SearchDocument` dans le package feuille ; retirer de la baseline les cycles `index` ↔ `search` et `index` ↔ `index.scan` ; vérifier par `./mvnw -B -ntp -pl core test -Dtest='*Architecture*,ProjectIndexingService*' -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
- [ ] 2.2 `AUD-ARC-02` — Introduire le port d'indexation sémantique, implémenté par `SemanticIndexingService`, avec une implémentation sans effet à la place de `null` ; retirer de la baseline le cycle `index` ↔ `search.semantic` ; vérifier par `./mvnw -B -ntp -pl core test -Dtest='*Architecture*,ProjectIndexingService*,NexusApplicationSemanticConfigurationTest' -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`

## 3. Cycles context ↔ context.source (AUD-ARC-04)

- [ ] 3.1 `AUD-ARC-04` — Déplacer `ContextFragment`, `ContextItem` et `ContextSelectionResult` dans `context.model`, mettre à jour les imports du cœur et des adaptateurs ; retirer de la baseline les cycles `context` ↔ `context.source`, `.git`, `.skill` ; vérifier par `./mvnw -B -ntp -pl core test -Dtest='*Architecture*,DefaultContextBuilder*,SkillContext*,LocalGitContextSourceProvider*' -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true` puis `./mvnw -B -ntp clean install`

## 4. Cycles search ↔ ranking (AUD-ARC-05)

- [ ] 4.1 `AUD-ARC-05` — Déplacer `SearchCandidate`, `SearchSignals`, `CandidateType`, `CandidateEnricher` et `CandidateMerger` dans le package de candidats ; retirer de la baseline les cycles `search` ↔ `ranking` et `search` ↔ `ranking.graph` ; vérifier par `./mvnw -B -ntp -pl core test -Dtest='*Architecture*,*Ranker*,GraphCandidateEnricher*,FederatedGoldenSearchCorpusTest' -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true` puis `./mvnw -B -ntp clean install`

## 5. Cycle search.semantic ↔ ollama (AUD-ARC-06)

- [ ] 5.1 `AUD-ARC-06` — Déplacer `fromEnvironment` et l'instanciation d'`OllamaEmbeddingProvider` de `SemanticSearchConfiguration` vers `NexusApplicationFactory` ; retirer de la baseline le cycle `search.semantic` ↔ `search.semantic.ollama` ; vérifier par `./mvnw -B -ntp -pl core test -Dtest='*Architecture*,SemanticSearchConfiguration*,NexusApplicationSemanticConfigurationTest' -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`

## 6. Graphe acyclique imposé (AUD-ARC-01)

- [ ] 6.1 `AUD-ARC-01` — Casser les cycles transitifs restants signalés par le test, puis vider la baseline et ajouter les règles de sens nommées (`index` sans `search`, `context.source.*` sans `context`, `ranking` sans `search`, `search.semantic` sans `ollama`) ; vérifier par `./mvnw -B -ntp -pl core test -Dtest='*Architecture*' -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
- [ ] 6.2 `AUD-ARC-01` — Mettre à jour la vue arc42 des building blocks et la documentation d'architecture courante (packages et sens de dépendance) ; vérifier par `bash .claude/scripts/contracts.sh`

## 7. Intégration

- [ ] 7.1 Vérifier l'absence de changement observable (réponses CLI, REST, MCP, self-smoke) : `/nx-validate` (contrats, `./mvnw -B -ntp clean install`, `scripts/self-smoke.ps1`)

## Workflow follow-up

- Ouvrir la PR vers `develop` (skill `nexus-pr-workflow`), après la fusion des sprints 3 à 6 ; merge exact-head (`gh pr merge --merge --match-head-commit`).
- Archiver le changement OpenSpec une fois la PR fusionnée (`/opsx:archive`).
