# Proposition

## Why

L'audit du 16/11/2026 (`origin/main` @ `7ec5c5dc`) place au sprint S2 « Couverture à jour et erreurs serveur tracées » six constats : `AUD-QUA-01` (Moyenne), `AUD-TST-13` (Moyenne), `AUD-TST-14`, `AUD-TST-04`, `AUD-TST-05` et `AUD-DEP-05` (Faible). Les sprints S3 à S6 modifient la redaction, la recherche et les adaptateurs. Avant eux, il faut une table de couverture juste, des planchers sur les paquets critiques, et des erreurs 500 qui laissent une trace côté serveur, faute de quoi une régression introduite par ces sprints passerait inaperçue (`docs/project/audits/2026-11-16/sprints.md`, section S2, « Pourquoi ici »). `AUD-QUA-01` débloque en outre `AUD-QUA-06` (sprint S6), qui retouche les mêmes mappers.

Critère de sortie du sprint (`docs/project/audits/2026-11-16/sprints.md`, section S2 ; `sprints-meta.json`, sprint 2) : la table de `adapter-coverage-gates.md` porte une mesure Linux et Windows au même commit, dénominateurs à jour ; `core/pom.xml` porte des règles `PACKAGE` pour `com.nexus.persistence.sqlite`, `com.nexus.search.lucene`, `com.nexus.context`, `com.nexus.cli` et `com.nexus.application`, et `coverage_floors.py` passe sur les deux plateformes ; `queryRanges`, `queryTerms` et `mergeRanges` ont une couverture de lignes non nulle dans `jacoco.xml` ; un job planifié exécute les tests REST et MCP en ordre aléatoire ; `IOExceptionMapper`, la branche 500 d'`IllegalStateExceptionMapper` et le catch générique de `NexusMcpTools` journalisent l'exception avec un identifiant renvoyé au client ; `readiness()` journalise la cause, et un test par surface le vérifie ; le niveau de log de la CLI et du serveur MCP se règle par une variable documentée.

Sources : `docs/project/audits/2026-11-16/findings.md` et `findings.json` (champ `constats`), `sprints.md`, `sprints-meta.json`, `plan-action.md`, `README.md`. Le code de production n'a pas changé depuis `7ec5c5dc` (`git log 7ec5c5dc..HEAD -- core adapters pom.xml distribution` est vide) ; chaque preuve ci-dessous a été relue au HEAD `6e0a5aaf`.

## What Changes

### Erreurs serveur tracées (`AUD-QUA-01`)

- [Existant vérifié] `IOExceptionMapper.toResponse` renvoie un 500 `io_error` sans journaliser l'exception (`adapters/rest-quarkus/src/main/java/com/nexus/api/IOExceptionMapper.java:15-19`) ; la branche finale d'`IllegalStateExceptionMapper` renvoie un 500 `internal_state_error` de la même façon (`adapters/rest-quarkus/src/main/java/com/nexus/api/IllegalStateExceptionMapper.java:38-43`). Aucune classe de `adapters/rest-quarkus/src/main` ne déclare de logger (`grep -rn "LOG\|Logger"` : aucun résultat).
- [Existant vérifié] Le gestionnaire d'outil MCP capture toute exception et renvoie `nexus_tool_error` avec `safeMessage(exception)` sans journaliser (`adapters/mcp-java/src/main/java/com/nexus/mcp/NexusMcpTools.java:235-238`) ; `safeMessage` rend « Erreur interne NEXUS » pour tout ce qui n'est ni `IllegalArgumentException` ni `IllegalStateException` (`:260-272`).
- [Existant vérifié] `NexusApplication.readiness()` déclare l'index dérivé absent sur `IOException | RuntimeException` sans journaliser la cause (`core/src/main/java/com/nexus/application/NexusApplication.java:299-303`).
- [Existant vérifié] La réponse d'erreur REST est le record public `ErrorResponse(String error, String message)` (`adapters/rest-quarkus/src/main/java/com/nexus/api/ApiModels.java:43-44`) ; `ExceptionMapperDisclosureTest#ioMapperDoesNotExposeInternalExceptionMessage` vérifie qu'un chemin interne n'y apparaît pas.
- [Exigence] Une erreur interne REST (500) et une erreur interne d'outil MCP sont journalisées côté serveur, exception et pile comprises, avec un identifiant de corrélation opaque renvoyé au client dans la réponse d'erreur.
- [Exigence] La réponse d'erreur garde la politique de sortie publique : ni chemin, ni pile, ni message interne ; l'identifiant ne dérive d'aucune donnée de la requête.
- [Exigence] Une sonde de disponibilité qui conclut à un index dérivé indisponible journalise la cause.
- [Écart] Hors audit, même défaut : `NexusReadinessCheck.call()` capture une `RuntimeException` sans la journaliser et publie le nom simple de sa classe dans la donnée `error` de la sonde (`adapters/rest-quarkus/src/main/java/com/nexus/api/NexusReadinessCheck.java:37-41`). La journalisation entre dans ce change ; la donnée `error` exposée sur le listener de management reste en l'état (voir `[Question]`).
- [Écart] Hors audit : le repli de sérialisation de `NexusMcpTools.textResult` renvoie `serialization_error` sans journaliser la `JsonProcessingException` (`NexusMcpTools.java:251-256`). Il suit la même exigence que le catch générique.

### Journalisation de la CLI et du serveur MCP (`AUD-DEP-05`)

- [Existant vérifié] `docs/developer/reproduce-and-debug.md:87` : « NEXUS n'a pas encore choisi de backend de logging. SLF4J utilise donc une implémentation NOP ». Aucun `pom.xml` ne déclare de dépendance `slf4j` (`grep -rn slf4j --include=pom.xml` : aucun résultat) ; seul `adapters/rest-quarkus/pom.xml:74` règle `java.util.logging.manager` pour les tests Quarkus.
- [Existant vérifié] `System.getLogger` est utilisé dans 13 classes de production, dont `NexusMcpServer` (`adapters/mcp-java/src/main/java/com/nexus/mcp/NexusMcpServer.java:26`), qui réserve stdout au framing JSON-RPC (`:21-22`, `:42-45`).
- [Existant vérifié] Les lanceurs `distribution/bin/nexus:8` et `distribution/bin/nexus.cmd` exécutent `java ... -jar nexus-cli.jar` sans point de réglage des journaux ; le serveur MCP est lancé directement par `java -jar nexus-mcp.jar` dans la configuration des clients (`docs/user/deployment-wizard-template.md:360`, `:464`). `adapters/rest-quarkus/src/main/resources/application.properties:1-13` ne contient aucune clé `quarkus.log.*`.
- [Exigence] Les messages SLF4J des dépendances (SDK MCP, JGit) atteignent le journal de la CLI et du serveur MCP au lieu d'être perdus.
- [Exigence] Le niveau de journalisation de la CLI et du serveur MCP se règle par une variable d'environnement documentée ; les journaux s'écrivent sur stderr, jamais sur stdout.
- [Proposition] Côté REST, documenter `quarkus.log.level` (et sa variable `QUARKUS_LOG_LEVEL`) et l'option `quarkus-logging-json` pour Docker, sans ajouter l'extension.

### Couverture et fiabilité de la suite (`AUD-TST-13`, `AUD-TST-14`, `AUD-TST-04`, `AUD-TST-05`)

- [Existant vérifié] `ContextFragmentFactory.fileFragments` découpe un fichier au-delà de `max(120, min(800, tokenBudget / 4))` tokens (`core/src/main/java/com/nexus/context/ContextFragmentFactory.java:240`) en fenêtres calculées par `queryRanges` (`:271-289`), `queryTerms` (`:291-299`) et `mergeRanges` (`:301-320`), avec `QUERY_WINDOW_LINES = 5`, `MAX_QUERY_WINDOWS = 4`, `FALLBACK_HEAD_LINES = 40` (`:45-47`). Comportement décrit dans `docs/developer/context-building.md:66`. Mesure de l'audit (rapports Windows de `7ec5c5dc`) : ces trois méthodes à 0 ligne couverte ; aucun test de `core/src/test/java/com/nexus/context/` (`ContextFragmentFactoryPathBoundaryTest`, `ContextFragmentFactoryRangeValidationTest`) n'utilise de fichier au-dessus du seuil.
- [Exigence] Le découpage d'un fichier au-dessus du seuil en fenêtres (fenêtres bornées, fusion, plafond, repli, termes ignorés) est spécifié et exécuté par la suite par défaut.
- [Existant vérifié] La « Mesure de référence » de `docs/developer/adapter-coverage-gates.md:95-114` cite le commit `f8a27f89` (ligne `com.nexus.security` remesurée à `c83c04ca`) : `com.nexus.security` 625 / 700 lignes Linux, 636 / 700 Windows ; `core` `BUNDLE` 11 099 lignes. L'audit mesure à `7ec5c5dc` sous Windows 731 / 792 lignes (92,30 %) pour `com.nexus.security` et 11 311 lignes pour le core ; `coverage_floors.py` confronté à la colonne Linux échoue (« more than 3.00 points above the published linux measure 89.29 % »).
- [Exigence] La table publie, pour chaque cible, une mesure Linux et une mesure Windows relevées au même commit, numérateurs et dénominateurs du build de ce commit.
- [Existant vérifié] `core/pom.xml:79-93` porte le `BUNDLE` (0.84 / 0.69) ; les seules règles `PACKAGE` du core visent `com.nexus.security` (`:97-114`), `com.nexus.index.jdt` (`:115-132`), `com.nexus.index.minos` (`:133-150`) et `com.nexus.index.scip` (`:151-168`).
- [Exigence] Les paquets `com.nexus.persistence.sqlite`, `com.nexus.search.lucene`, `com.nexus.context`, `com.nexus.cli` et `com.nexus.application` portent chacun un plancher `PACKAGE` en lignes et en branches, fixé par la règle d'écart publiée.
- [Existant vérifié] `docs/developer/adapter-coverage-gates.md:197` : « La preuve de régression est manuelle et n'est rejouée par aucun test ni job CI » ; `:257` : « Aucun test ni job CI n'exécute les classes dans un ordre inversé ou aléatoire ». Aucun `junit-platform.properties` ni option `runOrder` dans le dépôt.
- [Exigence] Un job planifié exécute les tests des modules REST et MCP dans un ordre aléatoire reproductible, et un job rejoue la preuve qu'une règle `CLASS` fait échouer le build.

## Type de changement

fonctionnel (contrat public d'erreur REST et MCP, journalisation, configuration de lancement) avec un volet outillage (planchers, table de couverture, jobs CI) : profil complet.

## Périmètre et exclusions

**Dans le périmètre :**
- Journalisation serveur et identifiant de corrélation : `IOExceptionMapper`, branche 500 d'`IllegalStateExceptionMapper`, catch générique et repli de sérialisation de `NexusMcpTools`, `NexusApplication.readiness()` et `NexusReadinessCheck`.
- Pont SLF4J et réglage du niveau de journalisation pour le JAR CLI et le JAR MCP ; documentation du réglage REST.
- Test de `ContextFragmentFactory` au-dessus du seuil plein ; nouvelle capacité qui spécifie ce découpage.
- Remesure de la table de couverture (Linux et Windows au même commit), remontée des planchers que la règle d'écart impose, règles `PACKAGE` des cinq paquets.
- Job planifié en ordre aléatoire pour REST et MCP ; job de preuve de la règle `CLASS` de `NexusRestTransportPolicy`.

**Hors périmètre :**
- Mapper par type `ProjectNotReadyException` et `ProjectIndexBusyException` et retirer le préfixe de message d'`IllegalStateExceptionMapper` (`AUD-QUA-06`, sprint S6).
- Un mapper REST générique pour les autres `RuntimeException` ; les journaux structurés JSON côté REST (seule leur documentation est prévue) ; les métriques Micrometer.
- Une règle `CLASS` sur `ContextFragmentFactory` (l'audit dit « envisager », voir `[Question]`) ; des règles pour les sous-paquets (`com.nexus.context.source.*`, `com.nexus.search.semantic.lucene`).
- La donnée `error` de `NexusReadinessCheck` exposée sur le listener de management.
- Les autres sprints de l'audit du 16/11 (changes `audit-1116-s1` et `s3` à `s7`).

## Capabilities

### New Capabilities
- `operational-logging` : journalisation côté serveur des erreurs internes REST et MCP avec identifiant de corrélation, cause d'une disponibilité dégradée, pont SLF4J et réglage du niveau de journalisation de la CLI et du serveur MCP, journaux hors stdout.
- `context-file-fragments` : choix des lignes d'un fichier servies dans un contexte quand aucun symbole ne le cible : fichier entier sous le seuil, fenêtres autour des termes au-delà, fusion, plafond et repli.

### Modified Capabilities
- `coverage-gates` : exigence ajoutée (planchers `PACKAGE` des cinq paquets critiques) ; exigence ajoutée (preuve automatisée qu'une règle `CLASS` mord) ; « La page de couverture publie la mesure de référence des planchers » réécrite pour exiger Linux et Windows au même commit, dénominateurs compris.
- `adapter-contract-tests` : exigence ajoutée (job planifié en ordre aléatoire pour les tests REST et MCP).
- `test-suite-reliability` : exigence ajoutée (le découpage en fenêtres de contexte est exécuté par la suite par défaut).

## Impact

- Code REST : `adapters/rest-quarkus/src/main/java/com/nexus/api/IOExceptionMapper.java`, `IllegalStateExceptionMapper.java`, `ApiModels.java` (`ErrorResponse`), `NexusReadinessCheck.java`.
- Code MCP : `adapters/mcp-java/src/main/java/com/nexus/mcp/NexusMcpTools.java`, `NexusMcpServer.java` (réglage du niveau au démarrage), `adapters/mcp-java/pom.xml` (pont SLF4J).
- Code core : `core/src/main/java/com/nexus/application/NexusApplication.java` (`readiness`), `core/src/main/java/com/nexus/cli/NexusCli.java` (réglage du niveau au démarrage), nouvelle classe de configuration des journaux dans le core, `core/pom.xml` (pont SLF4J pour le JAR `cli`, règles `PACKAGE`, planchers remontés).
- CI : nouveau workflow planifié sous `.github/workflows/` (ordre aléatoire, preuve de règle `CLASS`), `scripts/release/workflow_hygiene.py` s'il faut y déclarer le job ; références `chemin:ligne` de la documentation à recalculer (`.claude/rules/ci-and-scripts.md`).
- Tests : nouveau test de `ContextFragmentFactory` au-dessus du seuil ; `ExceptionMapperDisclosureTest`, `IllegalStateExceptionMapperTest`, `NexusMcpNegativeContractTest`, `NexusMcpServerIntegrationTest`, `NexusApplicationHardeningTest` complétés par la capture des journaux.
- Dépendances : pont SLF4J (`org.slf4j:slf4j-jdk-platform-logging`, version à aligner sur le `slf4j-api` résolu) ; effets sur SBOM, OSV et `license-compliance`.
- Documentation durable : `docs/developer/adapter-coverage-gates.md` (table, planchers, limites levées), `docs/developer/reproduce-and-debug.md` (section SLF4J, réglage du niveau, section OpenSpec de `operational-logging` et de `test-suite-reliability`), `docs/developer/context-building.md` (section OpenSpec de `context-file-fragments`), `docs/developer/rest-api.md` et `docs/developer/mcp.md` (champ de corrélation des erreurs), `docs/developer/ci-and-supply-chain.md` (nouveau workflow), `docs/developer/openspec-traceability.md`.
- ADR applicables : `ARCH-0085` (contrat de sortie publique CLI/REST/MCP, stdout réservé aux données), `ARCH-0086` (configuration résolue dans la composition root), `ARCH-0076` (cœur sans framework), `ARCH-0075` (adaptateurs minces). Besoin d'un nouvel ADR : voir `[Question]`.
- Effet utilisateur : les réponses d'erreur internes REST et MCP gagnent un champ (ajout compatible) ; aucun index ni schéma SQLite ne change.

## Questions ouvertes

- [Question] Le champ de corrélation ajouté à la réponse d'erreur REST et MCP, et le choix d'un pont SLF4J vers `System.Logger` pour les JAR CLI et MCP, justifient-ils un ADR qui complète `ARCH-0085` (contrat public) ou `GEN-0031` (JAR CLI autonome) ? Le design propose de s'en passer ; la décision revient au mainteneur (skill `nexus-adr`).
- [Question] Les valeurs des planchers des cinq nouveaux paquets et du plancher `com.nexus.security` ne sont pas connues avant la remesure Linux et Windows : elles découlent de la règle d'écart publiée (partie entière de la plus basse des deux mesures, moins 2 points, `docs/developer/adapter-coverage-gates.md:81`). Les chiffres Windows de l'audit (`com.nexus.cli` 76,9 % / 57,9 %, `com.nexus.application` 86,0 % / 57,0 %, `com.nexus.search.lucene` 84,1 % / 69,8 %) ne fixent aucun plancher.
