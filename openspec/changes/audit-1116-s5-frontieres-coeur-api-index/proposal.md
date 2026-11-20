# Proposition

## Why

L'audit du 16/11/2026 regroupe dans le sprint S5, « Frontières du cœur et API d'index unique », huit constats sur le même ensemble de packages du cœur : `AUD-QUA-02` (Moyenne), `AUD-ARC-12`, `AUD-ARC-02`, `AUD-ARC-04`, `AUD-ARC-01`, `AUD-ARC-05`, `AUD-QUA-08` et `AUD-PRF-03` (Faible). Deux faits les relient.

- Seule l'acyclicité du graphe des packages est garantie mécaniquement. Le tableau des sens d'ARCH-0076 n'est automatisé que sur quatre règles et ne correspond plus au code sur trois arêtes. Une dépendance descendante non voulue (`context` vers `persistence.sqlite`, `exposure` vers `search.lucene`) passerait donc le build. ARCH-0076 est accepté : la matrice complète et les nouveaux placements doivent être décidés par un nouvel ADR qui le complète.
- Côté Lucene, deux chemins d'écriture d'index coexistent. Le chemin par lot (`rebuild`/`applyChanges`) n'a plus d'appelant en production, mais il impose de faire deux fois chaque évolution de l'écriture. Or `AUD-PRF-03` (supprimer les passes de redaction redondantes dans les sessions) ne doit être fait que sur le chemin qui reste.

Critère de sortie du sprint (`docs/project/audits/2026-11-16/sprints.md`, section S5) : « Un nouvel ADR, qui complète ARCH-0076, porte la matrice complète des sens. CoreArchitectureTest la vérifie, et une fixture négative échoue par règle. SearchIndex et SemanticSearchIndex n'exposent plus que openSession, et Direct*Session est supprimée. processLocalOnly, RepositoryPath.fromPath et ProjectPathGuard.containsSymbolicLink ne sont plus publics dans le main. Les sessions Lucene et sémantique ne repassent plus un contenu déjà masqué dans le redacteur, et le gain apparaît dans portfolio.totalFullIndexMs. NexusApplication n'a plus de champ SemanticSearchIndex nullable. »

Sources : `docs/project/audits/2026-11-16/findings.json` (champ `constats`), `findings.md`, `sprints.md`, `sprints-meta.json` (sprint 5), `plan-action.md`, `architecture.md` (« Écarts avec la documentation », points 1 et 2). Preuves relues au HEAD `6e0a5aaf`. Le code de `core/` et `adapters/` n'a pas changé depuis le commit audité `7ec5c5dc` (`git diff --stat 7ec5c5dc HEAD -- core adapters` vide).

## What Changes

Ordre interne imposé par le sprint : `AUD-ARC-12` (décision) avant `AUD-ARC-02` et `AUD-ARC-04`, ces trois avant `AUD-ARC-01` ; `AUD-QUA-02` avant `AUD-PRF-03`.

- `AUD-ARC-12` — décision d'architecture
  - [Existant vérifié] ARCH-0076 est `Accepted` (`docs/architecture/adr/ARCH-0076-garder-un-coeur-java-sans-framework-acyclique-et-verifie.md:10`). Son tableau des sens (`:71-82`) ignore `cli`, `exposure`, `persistence.sqlite`, `search.lucene`, `index.jdt`/`scip`/`minos`/`java`/`markdown` et les fondations (`config`, `paths`, `token`, `security`). AGENTS.md interdit de réécrire une décision acceptée.
  - [Décision] Rédiger, avec le skill `nexus-adr` et au format Archgate (GEN-0057), un ADR `ARCH-NNNN` qui complète ARCH-0076. Il décide de la matrice complète des sens, fondations comprises, de la place de `ProjectIgnoreMatcher` et du sous-package des verrous et d'`ExternalTaskRunner`. Il est lié à ARCH-0076 et ajouté à l'index du registre. Le présent change n'écrit pas l'ADR : une tâche le prévoit, et le design en esquisse le contenu en `[Proposition]`.
- `AUD-ARC-02` — politique d'exclusion hors du scan d'indexation
  - [Existant vérifié] `import com.nexus.index.scan.ProjectIgnoreMatcher;` figure dans `core/src/main/java/com/nexus/context/source/instruction/InstructionDiscoverySupport.java:7` (instancié en `:38` et `:107`), dans `InstructionReferenceResolver.java:7` et dans `core/src/main/java/com/nexus/context/source/skill/LocalAgentSkillsProvider.java:5`. Côté `com.nexus`, `ProjectIgnoreMatcher` (298 lignes) n'importe que `paths` et `security` (`core/src/main/java/com/nexus/index/scan/ProjectIgnoreMatcher.java:3-6`). ARCH-0076:80 limite `context.source.*` à `context.model` et `search.candidate`.
  - [Écart] Hors audit : `ProjectIgnoreMatcher` importe aussi `org.eclipse.jgit.ignore.IgnoreNode` (`ProjectIgnoreMatcher.java:7`). La règle Archgate de BE-0083 fige son chemin (`docs/architecture/adr/BE-0083-appliquer-des-regles-d-exclusion-du-scan-de-type-gitignore.rules.ts:12-13`, `index/scan/**`). Le déplacer dans `com.nexus.paths`, l'une des deux cibles proposées par l'audit, ferait entrer JGit dans une fondation. Tout déplacement casse aussi la règle compagnon de BE-0083 si son chemin n'est pas mis à jour.
  - [Exigence] Les sources de contexte n'importent plus `com.nexus.index.scan`. La politique d'exclusion réside dans un package qui n'importe, dans `com.nexus`, que des fondations, et les exclusions appliquées ne changent pas.
- `AUD-ARC-04` — infrastructure hors du package du modèle d'index
  - [Existant vérifié] ARCH-0076:65 dit que « `com.nexus.index` garde le modèle et les ports ». Or ce package contient `ProjectIndexLockManager` (363 lignes, `FileChannel`/`FileLock`, imports `PrivateStoragePolicy` et `NexusPaths` en `core/src/main/java/com/nexus/index/ProjectIndexLockManager.java:3-5`) et `IndexingCapacityGate`, utilisé seulement par ce gestionnaire. Il contient aussi `ExternalTaskRunner` (`public final class`, `core/src/main/java/com/nexus/index/ExternalTaskRunner.java:34`, threads plateforme en `:75`), utilisé seulement par `core/src/main/java/com/nexus/index/service/ExternalCodeIntelligenceRefresher.java:6,21`. Le package racine compte 34 types et 1 742 lignes.
  - [Écart] Hors audit : ces chemins sont épinglés ailleurs. On les trouve dans les filtres `paths` de `.github/workflows/filesystem-semantics-qualification.yml:10,13,26,29` et de `.github/workflows/smb-filesystem-qualification.yml:10,20`, dans `scripts/release/test-final-audit-contracts.sh:142` et `scripts/release/test-operational-doc-contracts.sh:280` (`ExternalTaskRunner.java`), et dans la « Mise en œuvre » de DATA-0074 (`:135`) et de SEC-0082 (`:137`), deux ADR acceptés.
  - [Exigence] `com.nexus.index` ne contient plus ni le gestionnaire de verrou, ni la jauge de capacité, ni l'exécuteur de tâches externes, et cet exécuteur n'est plus public.
- `AUD-ARC-01` — matrice complète des sens
  - [Existant vérifié] `CoreArchitectureRules` ne déclare que quatre règles de sens : `index-sans-search`, `context-source-sans-context`, `ranking-sans-search` et `search-semantic-sans-ollama` (`core/src/test/java/com/nexus/architecture/CoreArchitectureRules.java:23-26`). `docs/developer/architecture-implementation.md:416` reconnaît cette limite.
  - [Existant vérifié] Trois arêtes du code contredisent le tableau : `core/src/main/java/com/nexus/context/ContextFragmentFactory.java:4` (`import com.nexus.index.CodeSymbol;`), `core/src/main/java/com/nexus/search/semantic/lucene/PersistentLuceneSemanticSearchIndex.java:4` (`import com.nexus.search.lucene.PersistentLuceneReaderSupport;`) et `context.source.*` vers `index.scan` (voir `AUD-ARC-02`).
  - [Existant vérifié] `persistence.sqlite`, `search.semantic.lucene`, `search.semantic.ollama`, `index.jdt`, `index.scip` et `index.minos` ne sont importés que par `application` (relevé `grep` des imports sur `core/src/main/java/com/nexus`). `search.lucene` l'est aussi par `search.semantic.lucene` (ligne ci-dessus), et `index.jdt` par `application/NexusConfiguration.java`.
  - [Écart] L'action de l'audit demande que « application et cli [soient] importés par personne dans le cœur ». Or `core/src/main/java/com/nexus/cli/NexusCli.java:3-4` importe `com.nexus.application.NexusApplication` et `NexusConfiguration` : c'est le point d'entrée de la CLI. L'exigence retenue interdit l'import de `cli` à tout le cœur, et celui de `application` à tout le cœur sauf `cli`.
  - [Exigence] Le test d'architecture vérifie une matrice d'autorisations complète, avec une fixture négative par règle, et échoue sur un package non classé.
- `AUD-ARC-05` — recherche sémantique désactivée
  - [Existant vérifié] `SemanticSearchIndex semanticSearchIndex = null;` se trouve en `core/src/main/java/com/nexus/application/NexusApplicationFactory.java:160`, en face de `SemanticIndexing.disabled()` en `:159`. `NexusApplication` porte un champ nullable et un booléen `semanticSearchEnabled` (`core/src/main/java/com/nexus/application/NexusApplication.java:64-65`), testé par `if (semanticSearchIndex != null)` en `:321`. Cela contredit ARCH-0076:97 (« pas par `null` »).
  - [Exigence] Une recherche sémantique désactivée est une implémentation sans effet du port, et l'état activé n'a qu'une source.
- `AUD-QUA-08` — surface publique réservée aux tests
  - [Existant vérifié] `ProjectIndexLockManager.processLocalOnly()` est public (`ProjectIndexLockManager.java:79-86`, Javadoc « Aucun code de production ne l'appelle »). Son seul appelant est la fabrique de test `core/src/test/java/com/nexus/index/service/ProjectIndexingServices.java:87`, plus trois tests de `core/src/test/java/com/nexus/index/`. `RepositoryPath.fromPath` (`core/src/main/java/com/nexus/paths/RepositoryPath.java:25`) et `ProjectPathGuard.containsSymbolicLink` (`core/src/main/java/com/nexus/security/ProjectPathGuard.java:91`) n'ont aucun appelant dans `core/src/main` ni dans `adapters/*/src/main`.
  - [Exigence] Ces trois méthodes ne sont plus publiques dans le code de production.
- `AUD-QUA-02` — API d'index unique
  - [Existant vérifié] Les écritures de production passent par les sessions (`core/src/main/java/com/nexus/index/service/IndexDocumentPublisher.java:65,69` ; `IndexPublication.java:44,49` appelle `applyChanges` de la session). `SearchIndex` déclare `applyChanges` et `rebuild` (`core/src/main/java/com/nexus/search/SearchIndex.java:11,13`), et son `openSession` par défaut crée un `DirectSearchIndexSession` (`:22-23`). `SemanticSearchIndex` déclare `rebuild` et `applyChanges` (`core/src/main/java/com/nexus/search/semantic/SemanticSearchIndex.java:30,45`) et un `openSession` par défaut qui crée un `DirectSemanticIndexSession` (`:66-70`). Les deux `Persistent*` surchargent `openSession` (`PersistentLuceneSearchIndex.java:59`, `PersistentLuceneSemanticSearchIndex.java:123`). `Direct*Session` (41 + 46 lignes) ne sert donc qu'aux doubles de test.
  - [Existant vérifié] Le défaut `SemanticSearchIndex.rebuild(projectId, provenance, documents)` ignore la provenance (`SemanticSearchIndex.java:35-40`), comme le défaut `applyChanges` avec provenance (`:53-59`). Les quatre méthodes publiques `rebuild`/`applyChanges` de `SemanticIndexingService` (`core/src/main/java/com/nexus/search/semantic/SemanticIndexingService.java:94-125`) sont absentes du port `SemanticIndexing` (`core/src/main/java/com/nexus/index/SemanticIndexing.java:15-43`).
  - [Existant vérifié] Le code est dupliqué : `LuceneSearchIndex.java:101-118` ≈ `:389-410`, et la récupération sur index corrompu de `LuceneSemanticSearchIndex.java:125-142` ≈ `:265-279`.
  - [Écart] D'après l'audit, les méthodes de `SemanticIndexingService` « ne sont appelées que par 17 fichiers de test ». Au HEAD, 11 fichiers de test appellent `.rebuild(` ou `.applyChanges(` et 6 autres surchargent ces méthodes dans un double, soit 17 fichiers qui touchent l'API par lot. Le nombre vaut pour toute l'API, pas pour le seul `SemanticIndexingService`.
  - [Exigence] Les ports d'index lexical et sémantique n'exposent plus que l'ouverture de session, sans défaut qui ignore la provenance. `Direct*Session` est supprimée, et `SemanticIndexingService` n'expose plus que son port.
- `AUD-PRF-03` — redaction redondante dans les sessions
  - [Existant vérifié] `IndexDocumentPublisher` redige le contenu une fois (`IndexDocumentPublisher.java:188`, `:212-214`) et construit le document avec le contenu redigé (`:195-200`), seul producteur de `SearchDocument` en production. `LuceneSearchIndex.java:317` le redige de nouveau, puis `SemanticIndexingService.java:184` et `:240` (`redactOnce`) une troisième fois. Ce choix est documenté comme « seconde barrière volontaire » (`docs/developer/secret-redaction.md:134`, `docs/developer/semantic-search.md:53`).
  - [Écart] L'idempotence de la redaction est affirmée (`docs/developer/secret-redaction.md:39`), mais aucune exigence de `secret-redaction` ne la porte, et aucun test de `core/src/test/java/com/nexus/security/` ne la nomme. Or retirer une passe sans changer le contenu indexé suppose cette idempotence. Sinon, il faudrait monter `POLICY_VERSION`.
  - [Écart] La mesure de l'audit (4,1 à 4,6 Mo/s par passe, environ 0,45 s de CPU par Mo indexé pour les deux passes redondantes) a été faite sur des classes compilées hors dépôt. Elle n'est pas rejouable ici : c'est une estimation, pas un `[Existant vérifié]`.
  - [Exigence] Les sessions Lucene et sémantique ne repassent pas dans le redacteur un contenu marqué comme redigé par le pipeline, et rédigent tout contenu non marqué. La redaction est idempotente.

## Type de changement

architectural (frontières du cœur, ports d'index, nouvel ADR), avec un volet performance sur la redaction : profil complet.

## Périmètre et exclusions

**Dans le périmètre :**
- Rédaction de l'ADR qui complète ARCH-0076 (tâche, skill `nexus-adr`), puis mise en œuvre des placements qu'il décide.
- `CoreArchitectureRules`, `CoreArchitectureTest` et `JavaSourceGraph` (matrice et fixtures négatives).
- Déplacements de `ProjectIgnoreMatcher`, `ProjectIndexLockManager`, `IndexingCapacityGate` et `ExternalTaskRunner`, avec leurs tests, leurs chemins épinglés (workflows, contrats, règle Archgate de BE-0083) et la documentation courante.
- Ports `SearchIndex` et `SemanticSearchIndex`, leurs implémentations Lucene, `SemanticIndexingService`, suppression de `DirectSearchIndexSession` et de `DirectSemanticIndexSession`, réécriture des tests sur les sessions.
- Implémentation sans effet de `SemanticSearchIndex` et composition dans `NexusApplicationFactory` et `NexusApplication`.
- Visibilité de `processLocalOnly`, `RepositoryPath.fromPath` et `ProjectPathGuard.containsSymbolicLink`.
- Marquage par le type du contenu redigé, sessions Lucene et sémantique, test d'idempotence, mesure dans `portfolio.totalFullIndexMs`.

**Hors périmètre :**
- Réécrire ARCH-0076, ou tout autre ADR accepté, au-delà de ce que décide le nouvel ADR (voir Questions ouvertes).
- Modifier la politique de redaction (`POLICY_VERSION`, motifs, profil `content-vN`) : la v12 relève du sprint S3. Ce change ne retire que des passes redondantes, à sortie identique.
- Les cycles entre espaces de premier niveau (`index`, `search`, `ranking`), voulus (`AUD-ARC-09`, Info) : la matrice reste au niveau sous-package.
- Les règles d'import des adaptateurs Maven (ARCH-0075) et les extractions de modules Maven.
- `AUD-QUA-07` (`safeMessage`, `Closeables`, sprint S7) et `AUD-QUA-05`, `AUD-SEC-06`, que ce sprint débloque sans les traiter.
- Formats persistés SQLite et Lucene, contrats CLI, REST et MCP : inchangés.

## Capabilities

### New Capabilities

### Modified Capabilities
- `core-package-boundaries` : huit exigences ajoutées (matrice complète des sens, une fixture négative par règle, adaptateurs importés par la seule composition, aucun import des points d'entrée, politique d'exclusion hors de `index.scan`, package du modèle d'index sans infrastructure, recherche sémantique désactivée sans `null`, aucune commodité de test publique).
- `project-indexing-pipeline` : trois exigences ajoutées (écritures d'index dérivés par session seulement, provenance sémantique jamais ignorée, service sémantique limité à son port), une modifiée (« Le service d'indexation exige un gestionnaire de verrou explicite », dont le scénario nomme `processLocalOnly()`).
- `secret-redaction` : deux exigences ajoutées (redaction idempotente, contenu marqué non redigé de nouveau par les sessions), une modifiée (« Chaque document est redigé une fois avant l'embedding »).

## Impact

- Code de production : `core/src/main/java/com/nexus/search/SearchIndex.java`, `search/DirectSearchIndexSession.java` (supprimé), `search/semantic/SemanticSearchIndex.java`, `search/semantic/DirectSemanticIndexSession.java` (supprimé), `search/semantic/SemanticIndexingService.java`, `search/lucene/LuceneSearchIndex.java`, `search/lucene/PersistentLuceneSearchIndex.java`, `search/semantic/lucene/LuceneSemanticSearchIndex.java`, `search/semantic/lucene/PersistentLuceneSemanticSearchIndex.java`, `index/SearchDocument.java`, `index/service/IndexDocumentPublisher.java`, `index/scan/ProjectIgnoreMatcher.java` (déplacé), `index/ProjectIndexLockManager.java`, `index/IndexingCapacityGate.java` et `index/ExternalTaskRunner.java` (déplacés), `index/service/ExternalCodeIntelligenceRefresher.java`, `index/service/ProjectIndexingService.java`, `context/source/instruction/*`, `context/source/skill/LocalAgentSkillsProvider.java`, `application/NexusApplicationFactory.java`, `application/NexusApplication.java`, `paths/RepositoryPath.java`, `security/ProjectPathGuard.java`.
- Tests : `core/src/test/java/com/nexus/architecture/*`, les 17 fichiers de test qui appellent ou surchargent l'API par lot, `index/service/ProjectIndexingServices.java`, `ProjectIndexLockManagerTest`, `RepositoryPathTest`, `ProjectPathGuardTest`, les tests de `index/scan/`, `SensitiveContentRedactorTest` ou `SecretRedactionProbeTest` (idempotence), `ScaleRegressionBenchmarkTest` (mesure).
- Outillage et contrats : `.github/workflows/filesystem-semantics-qualification.yml`, `.github/workflows/smb-filesystem-qualification.yml`, `scripts/release/test-final-audit-contracts.sh`, `scripts/release/test-operational-doc-contracts.sh`, `docs/architecture/adr/BE-0083-appliquer-des-regles-d-exclusion-du-scan-de-type-gitignore.rules.ts`.
- ADR : nouvel ADR `ARCH-NNNN` (complète ARCH-0076), `docs/architecture/adr/README.md`, `docs/architecture/arc42/09-decisions.md`. ADR applicables sans réécriture : ARCH-0076, ARCH-0075, DATA-0073, DATA-0074, BE-0083, SEC-0082, ARCH-0085.
- Documentation durable : `docs/developer/architecture-implementation.md` (section « OpenSpec core-package-boundaries », limite `:416`), `docs/developer/indexing-pipeline.md` (« OpenSpec project-indexing-pipeline », session de repli `:310`), `docs/developer/secret-redaction.md` (« OpenSpec secret-redaction », `:134`), `docs/developer/semantic-search.md:53`, `docs/architecture/overview.md`, `docs/developer/openspec-traceability.md`.
- Aucune migration SQLite, aucun format persisté ni contrat public modifié.

## Questions ouvertes

- [Question] Nom et place du package de `ProjectIgnoreMatcher` : `com.nexus.scan.rules` (proposé par l'audit), un autre package feuille, ou une autorisation `context.source.* ─► index.scan` consignée dans l'ADR ? JGit entre en jeu (voir `[Écart]` ci-dessus). L'ADR tranche ; la tâche 3.1 en dépend.
- [Question] Les arêtes `context ─► index` (`CodeSymbol`) et `search.semantic.lucene ─► search.lucene` (`PersistentLuceneReaderSupport`) sont-elles autorisées par la matrice, ou faut-il déplacer les types ? L'ADR tranche.
- [Question] Mettre à jour les chemins cités par des ADR acceptés (« Mise en œuvre » de DATA-0074, SEC-0082 et ARCH-0076, règle compagnon de BE-0083) relève-t-il du complément de mise en œuvre admis par `.claude/rules/documentation.md`, ou le nouvel ADR doit-il porter ces nouveaux chemins ? Une confirmation du mainteneur est exigée par le hook `guard-edit.ps1`.
- [Question] Le critère « le gain apparaît dans portfolio.totalFullIndexMs » ne fixe ni seuil ni corpus. Le plafond existant (`totalFullIndexMs <= 6000`, `scripts/verify-scale-budgets.py:186`) ne s'applique qu'au profil `full`, et le corpus du banc est synthétique. Il faut donc décider quel écart relatif, et sur quel profil, vaut preuve du gain.
