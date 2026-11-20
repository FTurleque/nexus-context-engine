# Proposition

## Why

L'audit du 16/11/2026 relève que plusieurs garde-fous de la CI sont verts sans voir ce qu'ils gardent : deux bancs de performance n'ont pas de seuil et deux n'ont pas de lanceur, le gate Docker ignore le wrapper Maven, deux gates requis tournent sur un JDK et une image flottants, et la plupart des checkouts gardent le jeton GitHub sur disque. Quarkus reste par ailleurs en 3.40.1.1 alors qu'une release corrective de sécurité 3.40.1.2 existe. Le sprint S1 « Remettre la CI et les mesures d'aplomb » regroupe ces cinq constats parce que les sprints suivants s'appuient sur des gates fiables et des bancs qui mesurent : le banc de débit de redaction branché ici est le prérequis d'`AUD-SEC-02` à `AUD-SEC-05` (sprint 3).

Critère de sortie du sprint (`docs/project/audits/2026-11-16/sprints.md`, section S1, et `sprints-meta.json`, `"numero": 1`) : « Quarkus est en 3.40.1.2 dans pom.xml et le gate OSV du SBOM agrégé passe. ci.yml (linux-reactor) et osv-scanner.yml (build-sbom) épinglent java-version '21.0.12+101.0.LTS' et ubuntu-24.04. docker-distribution.yml (PR et push) et windows-installer.yml filtrent mvnw, mvnw.cmd, .mvn/**, config/tool-integrity.properties et les deux vérificateurs. workflow_hygiene.py échoue sur un checkout sans persist-credentials: false, et sa fixture négative est rejetée. RedactionThroughputBenchmarkTest et IndexingHeapPeakBenchmarkTest sont nommés dans un -Dtest= de workflow, avec un seuil qui fait échouer le job. »

Sources : `docs/project/audits/2026-11-16/findings.md` (`AUD-DEP-01`, `AUD-PRF-04`, `AUD-DEP-02`, `AUD-DEP-03`, `AUD-DEP-04`), `docs/project/audits/2026-11-16/findings.json` (champ `constats`), `docs/project/audits/2026-11-16/plan-action.md`, `docs/project/audits/2026-11-16/README.md`. Commit audité `7ec5c5dc` ; depuis, seuls la documentation et `scripts/release/test-final-audit-contracts.sh` ont changé (`git log 7ec5c5dc..HEAD`). Chaque preuve ci-dessous a été relue au HEAD `6e0a5aaf`.

## What Changes

**AUD-DEP-01 — Quarkus 3.40.1.1 (Moyenne, S)**

- [Existant vérifié] `pom.xml:55` épingle `<quarkus.platform.version>3.40.1.1</quarkus.platform.version>`. Les exclusions `requireUpperBoundDeps` (`pom.xml:321-333`, `org.jctools:jctools-core`, `org.jspecify:jspecify`) demandent par commentaire à être revues « à chaque montée de quarkus.platform.version ».
- [Existant vérifié] Le gate `OSV aggregate SBOM vulnerability gate` scanne le SBOM CycloneDX du reactor avec `fail-on-vuln: true` (`.github/workflows/osv-scanner.yml:77-89`) ; le SBOM est produit par le job `build-sbom` (`:34-75`).
- [Existant vérifié] La documentation courante désigne Quarkus par la propriété `quarkus.platform.version`, et le contrat le vérifie sans numéro littéral (`scripts/release/test-operational-doc-contracts.sh:48`, `:139-141`) : une montée ne touche que `pom.xml`.
- [Existant vérifié] Selon l'audit (findings.md, `AUD-DEP-01`), Quarkus 3.40.1.2, publiée le 09/11/2026, corrige CVE-2026-107912, CVE-2026-110879 et CVE-2026-111497 (brotli4j 1.23.0 livré dans le classpath REST via `quarkus-vertx-http`). Ces faits externes ne sont pas revérifiés ici ; la résolution de l'artefact par le wrapper Maven les confirme à la mise en œuvre.
- [Décision] Monter `quarkus.platform.version` en 3.40.1.2 sans changement d'exigence : une version de dépendance n'est pas un comportement spécifié, et le comportement attendu (le gate OSV du SBOM agrégé passe) est déjà porté par `osv-scanner.yml`.

**AUD-PRF-04 — bancs sans seuil ou sans lanceur (Faible, S)**

- [Existant vérifié] `RedactionThroughputBenchmarkTest` (`core/src/test/java/com/nexus/benchmark/RedactionThroughputBenchmarkTest.java:35`) et `IndexingHeapPeakBenchmarkTest` (`core/src/test/java/com/nexus/benchmark/IndexingHeapPeakBenchmarkTest.java:60`) sont opt-in par `@EnabledIfSystemProperty(named = "nexus.scale.benchmark.enabled", ...)` ; aucun fichier de `.github` ni de `scripts` ne les nomme (recherche de `RedactionThroughput` et `IndexingHeapPeak` vide). Les autres classes opt-in sont nommées dans un `-Dtest=` de workflow (`scale-benchmark.yml:136`, `lucene-lifecycle-benchmark.yml:104`, `scanner-corpus-benchmark.yml:80`, `git-context-cache-qualification.yml:91`, `smb-filesystem-qualification.yml:90`, `measurement-tools.yml:135`, `semantic-search-qualification.yml:108` et `:282`).
- [Existant vérifié] `RedactionThroughputBenchmarkTest` écrit des médianes et des rapports (`prefilterGainRatio`, `tokenPassesShareOfRedact`) dans `target/redaction-throughput-benchmark.json` sans aucune assertion de durée (`RedactionThroughputBenchmarkTest.java:58-77`) ; `IndexingHeapPeakBenchmarkTest` relève `peakUsedHeapBytes` et `peakLiveHeapBytes` et n'asserte que les compteurs du rapport d'indexation (`IndexingHeapPeakBenchmarkTest.java:40-58`, `:102-104`).
- [Existant vérifié] Les bancs Lucene et Git n'assertent que la forme du rapport et affichent le gain sans seuil : `.github/workflows/lucene-lifecycle-benchmark.yml:136-150` (`samples >= 20`, `p95 >= p50`) puis `:169-170` (`print(... p95ImprovementRatio)`) ; `.github/workflows/git-context-cache-qualification.yml:123-152` puis `:159` (`print('warm-p95-improvement=', ...)`). Le banc du scanner a pour seul budget `scanMs <= 30_000` (`.github/workflows/scanner-corpus-benchmark.yml:100`). À l'inverse, `scale-benchmark.yml:225-230` et `scripts/verify-scale-budgets.py` imposent de vrais budgets.
- [Écart] La spec `test-suite-reliability › Chaque test opt-in a un lanceur automatisé` exige un lanceur automatisé pour tout test opt-in de `src/test`, ou son retrait de `src/test`. `IndexingHeapPeakBenchmarkTest` et `RedactionThroughputBenchmarkTest` y dérogent, et la page durable présente l'absence de lanceur du premier comme volontaire (`docs/developer/reproduce-and-debug.md:595`, `docs/developer/indexing-pipeline.md:355`). Le critère de sortie du sprint tranche pour un lanceur avec seuil.
- [Exigence] `RedactionThroughputBenchmarkTest` est lancé par un workflow déclenché par une modification de `core/src/main/java/com/nexus/security/**`, et le job échoue sous un seuil de débit.
- [Exigence] `IndexingHeapPeakBenchmarkTest` est lancé par un job planifié, qui échoue au-delà d'un seuil de pic de heap.
- [Exigence] Les bancs du cycle de vie Lucene et du cache Git échouent quand leur gain p95 passe sous un plancher, et le budget du banc du scanner est dérivé de relevés retenus et énoncé dans sa page.

**AUD-DEP-02 — filtres de chemins du gate Docker (Faible, S)**

- [Existant vérifié] Les filtres `paths:` de `.github/workflows/docker-distribution.yml` (PR `:20-36`, push `:39-55`) ne contiennent ni `mvnw`, ni `mvnw.cmd`, ni `.mvn/**`, ni `config/tool-integrity.properties`, ni `scripts/release/verify-pinned-checksum.sh`, ni `scripts/release/ToolArchiveVerifier.java`, et contiennent `'src/**'` alors qu'aucun répertoire `src` n'existe à la racine. Or `packaging/docker/Dockerfile:12-13` exécute `./mvnw -B -DskipTests package`, et `mvnw:7-9` lit `config/tool-integrity.properties` et appelle les deux vérificateurs.
- [Existant vérifié] `.github/workflows/windows-installer.yml:19-30` et `.github/workflows/main-push-windows-installer.yml:7-19` couvrent `mvnw`, `mvnw.cmd`, `.mvn/**` et `scripts/release/**` mais pas `config/tool-integrity.properties`.
- [Exigence] Le filtre de chemins d'un gate qui construit avec le wrapper couvre le wrapper, son ancre d'intégrité et ses vérificateurs, et ne contient aucun motif mort.

**AUD-DEP-03 — jeton conservé par les checkouts (Faible, S)**

- [Existant vérifié] 25 étapes `uses: actions/checkout@` dans `.github/workflows`, dont 6 avec `persist-credentials: false` (`action-pin-upstream.yml:35`, `ci.yml:279`, `measurement-tools.yml:40`, `:48`, `:112`, `semantic-search-qualification.yml:261`). Le checkout du job `publish` (`.github/workflows/release.yml:183-187`) n'en a pas, sous `contents: write`, `packages: write`, `id-token: write`, `attestations: write` (`:171-175`). `scripts/release/workflow_hygiene.py` ne contrôle pas cette clé (recherche de `persist` vide).
- [Existant vérifié] Des expressions de contexte sont interpolées dans des blocs `run:` : `${{ github.actor }}` (`release.yml:306`), `${{ steps.preflight.outputs.* }}` (`release.yml:330-331`), `${{ steps.publish.outputs.digest }}` (`release.yml:461`, `:477-478`, `:481`), `${{ github.event.pull_request.base.sha }}` (`scale-benchmark.yml:164`).
- [Exigence] Tout checkout déclare `persist-credentials: false`, et le contrôle d'hygiène échoue sinon, fixture négative comprise.
- [Exigence] `github.actor`, les sorties d'étape et les champs de `github.event.pull_request` arrivent dans un bloc `run:` par une variable d'environnement.

**AUD-DEP-04 — JDK et image flottants des gates requis Linux (Faible, S)**

- [Existant vérifié] Le job `linux-reactor` tourne sur `runs-on: ubuntu-latest` (`.github/workflows/ci.yml:81`) avec `java-version: '21'` (`:108-112`) ; le job `build-sbom` sur `ubuntu-latest` (`.github/workflows/osv-scanner.yml:36`) avec `java-version: '21'` (`:49`). Le Windows gate (`ci.yml:36`), CodeQL et l'installateur Windows épinglent `'21.0.12+101.0.LTS'`. Les deux checks sont requis (`docs/developer/branch-governance.md:39-47`).
- [Existant vérifié] `docs/developer/ci-and-supply-chain.md:57` affirme que les jobs hors benchmarks « restent sur `ubuntu-latest` et `windows-latest` » ; cette phrase devient fausse pour ces deux jobs.
- [Exigence] Les jobs `linux-reactor` et `build-sbom` épinglent la même version exacte du JDK que le Windows gate et une image `ubuntu-24.04`.

## Type de changement

outillage (workflows, contrôles d'hygiène, montée de dépendance, bancs de mesure) : profil court.

## Périmètre et exclusions

**Dans le périmètre :**
- `pom.xml` (`quarkus.platform.version`, relecture des exclusions `requireUpperBoundDeps`).
- `.github/workflows/ci.yml`, `osv-scanner.yml`, `docker-distribution.yml`, `windows-installer.yml`, `main-push-windows-installer.yml`, `release.yml`, `scale-benchmark.yml`, `lucene-lifecycle-benchmark.yml`, `git-context-cache-qualification.yml`, `scanner-corpus-benchmark.yml`, tous les workflows qui font un checkout, et le ou les workflows qui lancent les bancs de redaction et de pic de heap.
- `scripts/release/workflow_hygiene.py`, ses fixtures et les contrats qui l'appellent.
- Assertions de seuil dans les bancs ou dans leur étape de vérification ; documentation durable des bancs et de l'hygiène des workflows.

**Hors périmètre :**
- Tout code applicatif de `core` et des adaptateurs ; les bancs gardent leur protocole de mesure.
- Les corrections de redaction du sprint 3 (`AUD-SEC-01` à `AUD-SEC-05`), qui consomment le banc de débit branché ici.
- Épingler le JDK ou l'image des autres jobs (14 `java-version: '21'` et 10 `ubuntu-latest` relevés par l'audit) : seuls les deux gates requis Linux sont visés.
- Un nouveau check requis sur `main` : ni le gate Docker, ni les bancs ne deviennent requis.
- `AUD-DEP-05` à `AUD-DEP-07` et les constats des sprints S2 à S7, traités par leurs propres changes.

## Capabilities

### New Capabilities
- `performance-benchmark-thresholds`: les bancs de performance secondaires (débit de redaction, pic de heap de l'indexation, cycle de vie Lucene, cache Git, scanner) ont un lanceur automatisé et un seuil qui fait échouer leur job ; le benchmark d'échelle reste porté par `scale-benchmark-gate`.

### Modified Capabilities
- `ci-workflow-tooling`: cinq exigences ajoutées (checkout sans jeton persistant, contexte GitHub et sorties d'étape par l'environnement, JDK et image épinglés des gates requis Linux, filtres de chemins qui couvrent le wrapper et son ancre, filtres sans motif mort).
- `test-suite-reliability`: « Chaque test opt-in a un lanceur automatisé » reçoit un scénario pour `RedactionThroughputBenchmarkTest` et `IndexingHeapPeakBenchmarkTest`.

## Impact

- Build : `pom.xml:55` ; `target/sbom/bom.json` (présence de `com.aayushatharva.brotli4j`).
- Workflows : fichiers listés au périmètre. Aucun changement de nom de check requis.
- Outillage : `scripts/release/workflow_hygiene.py` (nouveaux contrôles, `PINNED_RUNNER_JOBS`), `scripts/release/fixtures/workflow-hygiene/`, `scripts/release/test-operational-doc-contracts.sh` et `scripts/release/test-tool-integrity-anchors.sh` (appels et fixtures), éventuellement `scripts/release/test-final-audit-contracts.sh` (lanceurs des tests opt-in).
- Tests : `RedactionThroughputBenchmarkTest`, `IndexingHeapPeakBenchmarkTest` (seuils, si portés par le test).
- Documentation durable : `docs/developer/ci-and-supply-chain.md` (« Hygiène des workflows », « Tests de mesure opt-in », `#openspec-ci-workflow-tooling`), `docs/developer/reproduce-and-debug.md` (`#openspec-test-suite-reliability`), `docs/developer/indexing-pipeline.md` (« Reproduire les mesures »), `docs/developer/indexing-corpus-limits.md` (budget du scanner), `docs/developer/lucene-lifecycle-qualification.md`, `docs/developer/git-context.md`, `docs/developer/openspec-traceability.md`.
- ADR : aucun nouvel ADR ; la chaîne d'outillage épinglée suit le processus établi (skill `nexus-dependency-bump`).

## Questions ouvertes

- [Question] Quelle métrique et quel seuil pour le banc de débit de redaction ? Le banc ne produit que des médianes en nanosecondes et des rapports (`prefilterGainRatio`, `tokenPassesShareOfRedact`) ; aucun débit minimal n'est sourcé par l'audit ni par un relevé daté. Débit absolu sur `ubuntu-24.04`, rapport à la base de la PR comme `scale-benchmark`, ou plafond de `tokenPassesShareOfRedact` ?
- [Question] Quel seuil pour le pic de heap ? `docs/developer/indexing-pipeline.md:355` recommande de préférer les rapports (heap vivant par fichier) aux valeurs absolues ; la valeur doit venir du relevé `docs/project/history/indexing-heap-and-hashing-measures-2026-11-07.md` ou d'un nouveau relevé sur le runner de référence.
- [Question] Bancs Lucene et Git : plancher de gain absolu ou seuil relatif à la base de la PR (deux options de l'audit) ? Pour Lucene, `p95ImprovementRatio` mesure un prototype persistant (`persistentPrototype`) face au cycle de vie de production ; un plancher sur ce gain garde-t-il bien le cache de readers de production ?
- [Question] Budget du scanner : l'audit demande de le « resserrer à partir des relevés retenus » sans citer de valeur ; aucun relevé daté n'est cité dans `docs/developer/indexing-corpus-limits.md`. Quel relevé fait foi ?
