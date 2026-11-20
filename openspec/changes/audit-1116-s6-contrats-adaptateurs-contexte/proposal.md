# Proposition

## Why

L'audit du 16/11/2026 relève que plusieurs contrats entre le cœur et ses adaptateurs reposent sur du texte plutôt que sur des types. Le statut 409 REST est déduit d'un message en français, les métadonnées de contexte sont des `Map` à clés littérales hors du schéma public typé, chaque surface recode ses entrées, trois états de processus s'écrasent sans contrôle, et les instructions des dépôts indexés sont servies aux agents sans marquage de confiance (`docs/project/audits/2026-11-16/README.md`, section « Des contrats portés par du texte plutôt que par des types »). Rien n'est cassé, mais une évolution du cœur peut casser une surface sans erreur de compilation ni de test.

Le sprint S6 regroupe cinq constats de sévérité Faible : `AUD-ARC-03`, `AUD-QUA-04`, `AUD-QUA-05`, `AUD-QUA-06` et `AUD-SEC-06` (`docs/project/audits/2026-11-16/sprints.md`, section S6). Son critère de sortie : « REST et MCP mappent ProjectNotReadyException et ProjectIndexBusyException par type, et IllegalStateExceptionMapper ne contient plus de préfixe de message. REST et MCP appellent la même fonction du cœur pour requestedSources, qui traite une entrée vide de façon identique. Les métadonnées consommées par self-smoke.ps1 sont des records couverts par PublicOutputSchema, avec un champ de confiance pour les items INSTRUCTION et SKILL. SECURITY.md ou current-limitations.md décrit le modèle de menace d'injection. configure() et acquireShared échouent sur une valeur différente de celle déjà installée. »

Sources : `docs/project/audits/2026-11-16/findings.md` et `findings.json` (champ `constats`), `sprints.md`, `sprints-meta.json`, `plan-action.md`. Preuves relues au HEAD `6e0a5aaf` : depuis le commit audité `7ec5c5dc`, seul `scripts/release/test-final-audit-contracts.sh` a changé hors documentation (`git diff --stat 7ec5c5dc HEAD -- core adapters scripts SECURITY.md`).

**Prérequis.** Ce change passe après deux changes créés en parallèle :
- `audit-1116-s2-couverture-et-erreurs-tracees` (`AUD-QUA-01`, prérequis de `AUD-QUA-06`) : journalisation serveur dans les mêmes mappers REST et le même `catch` MCP.
- `audit-1116-s5-frontieres-coeur-api-index` (`AUD-ARC-12`, `AUD-ARC-04`, `AUD-ARC-02`) : ADR qui complète `ARCH-0076`, sortie de l'infrastructure concurrente de `com.nexus.index` (prérequis de `AUD-QUA-05`) et de `ProjectIgnoreMatcher` hors du scan d'indexation (prérequis de `AUD-SEC-06`).

Ordre interne imposé par le sprint : `AUD-QUA-04` avant `AUD-SEC-06`.

## What Changes

### AUD-ARC-03 — Normalisation des entrées

- [Existant vérifié] Les défauts sont redéclarés par chaque surface : `adapters/rest-quarkus/src/main/java/com/nexus/api/NexusResource.java:33-34`, `FederatedResource.java:28-29`, `adapters/mcp-java/src/main/java/com/nexus/mcp/NexusMcpTools.java:27-28`, `core/src/main/java/com/nexus/cli/NexusCli.java:234,255`. Ils lisent les constantes de `ResultLimitPolicy` et `ContextBudgetPolicy`.
- [Existant vérifié] `find_usages` a une limite par défaut littérale de 20 (`NexusMcpTools.java:145`), contre `ResultLimitPolicy.DEFAULT_RESULT_LIMIT = 10` (`core/src/main/java/com/nexus/config/ResultLimitPolicy.java:13`).
- [Existant vérifié] Sources demandées : REST convertit chaque entrée par `CandidateType.valueOf(value.trim()…)` sans filtrer les blancs, donc une entrée vide échoue en « Source de contexte inconnue » (`adapters/rest-quarkus/src/main/java/com/nexus/api/NexusApiApplicationService.java:181-193`). MCP filtre les entrées vides et borne la cardinalité à `CandidateType.values().length` (`adapters/mcp-java/src/main/java/com/nexus/mcp/McpToolArguments.java:16,79-99`). REST n'a pas de borne de cardinalité propre.
- [Existant vérifié] La CLI passe `Set.of()` (`NexusCli.java:203,219`), n'a pas d'option de sources et `docs/developer/cli.md` n'en parle pas.
- [Exigence] REST et MCP convertissent les sources demandées par une même fonction du cœur, qui traite de la même façon une entrée vide ou blanche, une valeur inconnue et une cardinalité excessive.
- [Exigence] La limite par défaut de `find_usages` est la limite commune de résultats.
- [Décision] L'absence d'option de sources en CLI devient une divergence déclarée par `PublicOutputSchema` et documentée dans `docs/developer/cli.md`, plutôt qu'une nouvelle option. L'audit laisse le choix ; une option CLI élargirait le contrat de la CLI (`ARCH-0085`, « Ne pas retirer… » ne s'applique pas à un ajout, mais le besoin n'est pas exprimé).

### AUD-QUA-04 — Métadonnées de contexte typées

- [Existant vérifié] Les métadonnées d'un contexte simple sont 45 `put` littéraux dans une `LinkedHashMap` (`core/src/main/java/com/nexus/context/ContextBuildMetadata.java:54-116`) ; celles d'un contexte fédéré, 23 `put` (`core/src/main/java/com/nexus/context/FederatedContextService.java:351-376`). La sortie publique les porte en `SortedMap<String, Object>` (`core/src/main/java/com/nexus/exposure/PublicOutput.java:126,147`).
- [Existant vérifié] `PublicOutputSchema.divergences` ne compare que les composantes de records (`core/src/main/java/com/nexus/exposure/PublicOutputSchema.java:34-60`) : une clé de métadonnée renommée échappe aux tests de parité.
- [Existant vérifié] `scripts/self-smoke.ps1` lit 19 fois `.metadata.<clé>` (lignes 169 à 269), sur 11 clés : `nativeSourcesDiscovered`, `gitEnabled`, `skillsDiscovered`, `skillSelectedItems`, `skillResourcesDiscovered`, `skillsExecuted`, `gitRepositoryAvailable`, `gitCommitsInspected`, `gitRelatedCommits`, `gitSelectedItems`, `reductionRatio`. `docs/developer/reproduce-and-debug.md:349-351` en cite trois.
- [Écart] Preuve qui ne tient pas : les trois accès `.metadata` de `scripts/validate-phase-6.ps1:149-151` lisent `$sbomJson.metadata.component`, l'en-tête CycloneDX du SBOM, pas les métadonnées de contexte. Ce script n'est pas un consommateur des métadonnées de contexte.
- [Exigence] Les métadonnées d'un contexte simple et d'un contexte fédéré sont des records de la sortie publique, comparés par le contrat de clés comme le reste de la réponse ; les clés lues par `scripts/self-smoke.ps1` en font partie, sous le même nom.
- [Décision] Les clés publiques actuelles et leur ordre JSON (tri par clé) sont conservés : les records déclarent leurs composantes dans l'ordre lexicographique, si bien qu'un client existant ne voit pas de différence de forme.

### AUD-QUA-05 — États de processus

- [Existant vérifié] `ProjectFileLimits.configure` écrase `configuredMaxFileSizeBytes` (`core/src/main/java/com/nexus/security/ProjectFileLimits.java:21,37-44`) ; `SecurityPolicy.configure` écrase `posture` (`core/src/main/java/com/nexus/config/SecurityPolicy.java:26,34-38`) ; `IndexingCapacityGate.acquireShared` fixe `shared` au premier appel et ignore sans erreur une limite différente ensuite (`core/src/main/java/com/nexus/index/IndexingCapacityGate.java:14,32-34,43-54`).
- [Existant vérifié] Installation par `NexusConfiguration.installProcessPolicies()` (`core/src/main/java/com/nexus/application/NexusConfiguration.java:70-73`), appelée par `NexusApplicationFactory.java:133` ; l'adaptateur REST appelle aussi `SecurityPolicy.configure(System.getenv())` (`adapters/rest-quarkus/src/main/java/com/nexus/api/NexusRestExposureGuard.java:29`), puis la factory réinstalle la même posture filtrée.
- [Existant vérifié] `ARCH-0086` (Accepted) décide que « deux parts valent pour tout le processus » et prévoit son réexamen « si une part de configuration doit varier entre deux instances de NexusApplication d'un même processus » (`docs/architecture/adr/ARCH-0086-resoudre-la-configuration-et-nexus-home-dans-la-composition-root.md:69,134`). Elle ne nomme pas la borne de concurrence d'indexation.
- [Exigence] Chacun des trois états refuse, par une erreur explicite, une valeur différente de celle déjà installée pour le processus, et accepte sans effet la même valeur.
- [Décision] Les tests remettent ces états à zéro par un point d'entrée réservé aux tests, non public hors de leur paquet, plutôt que de dépendre de l'ordre d'exécution.
- [Écart] L'audit demande de « documenter la règle dans l'ADR ARCH-0086 » ; un ADR accepté ne se réécrit pas (`.claude/rules/documentation.md`, GEN-0057). La règle est consignée dans la mise en œuvre et les pages développeur ; voir la question Q2.

### AUD-QUA-06 — Conflits d'état typés

- [Existant vérifié] `IllegalStateExceptionMapper` classe en 409 en analysant le message par préfixe, marqueur, suffixe et forme d'UUID (`adapters/rest-quarkus/src/main/java/com/nexus/api/IllegalStateExceptionMapper.java:20-23,46-89`). Les messages viennent de `core/src/main/java/com/nexus/project/ProjectReadiness.java:12-13` et `core/src/main/java/com/nexus/index/ProjectIndexLockManager.java:230-231`.
- [Existant vérifié] MCP réduit toute `IllegalStateException` au message « Opération NEXUS indisponible dans l'état courant » sous le code `nexus_tool_error` (`NexusMcpTools.java:235-238,268-269`) : un client MCP ne distingue pas « pas prêt » et « occupé ».
- [Existant vérifié] Précédent dans le dépôt : `IndexingCapacityExceededException extends IllegalStateException` (`core/src/main/java/com/nexus/index/IndexingCapacityExceededException.java:4`) a son propre mapper REST (`adapters/rest-quarkus/src/main/java/com/nexus/api/IndexingCapacityExceededExceptionMapper.java:11-15`).
- [Exigence] Un projet non prêt et une mutation d'index déjà en cours sont signalés par des exceptions typées du cœur, mappées par type en REST (409, avec `Retry-After` pour l'index occupé) et en MCP (codes d'erreur distincts) ; aucun mapper ne lit le texte du message.
- [Décision] Les deux exceptions sont des sous-classes d'`IllegalStateException`, pour que tout appelant qui attrape aujourd'hui `IllegalStateException` garde son comportement.

### AUD-SEC-06 — Provenance du contenu servi

- [Existant vérifié] `agents.md`, `agent.md`, `claude.md`, `gemini.md` sont reconnus à toute profondeur (`core/src/main/java/com/nexus/index/scan/ProjectScanner.java:272-279`) ; un budget est réservé aux instructions (`core/src/main/java/com/nexus/context/ContextBudgetAllocator.java:19,143`) ; les skills sont servies sans troncature de leurs instructions (`core/src/main/java/com/nexus/context/source/skill/SkillContextSelector.java:18,80-81`) ; les références `@chemin` sont suivies sur cinq niveaux (`core/src/main/java/com/nexus/context/source/instruction/InstructionReferenceResolver.java:27-28`).
- [Écart] Preuve décalée : la priorité « jusqu'à 94 » d'`AgentsMdInstructionProvider` est à la ligne 39 (`Math.min(94, (alias ? 64 : 74) + depth * 4)`), pas à la ligne 29 citée par l'audit. Le fait tient.
- [Existant vérifié] La description des outils `build_context` et `explain_context` ne dit pas que le contenu servi est une donnée du dépôt (`NexusMcpTools.java:155-156`), pas plus que celle des outils fédérés (`:188-189`). Le record public `ContextItem` n'a aucun champ de provenance ni de confiance (`PublicOutput.java:99-110`).
- [Existant vérifié] `ProjectIgnoreMatcher` n'exclut ni `vendor/` ni `third_party/` (`core/src/main/java/com/nexus/index/scan/ProjectIgnoreMatcher.java:40-54`). Aucune mention d'injection d'instructions dans `SECURITY.md` ni `docs/developer/` hors audits (recherche `prompt injection|injection d.instructions` : 0 résultat pertinent).
- [Existant vérifié] Point qui tient : les sources natives fédérées restent `project-local` (`FederatedContextService.java:370`).
- [Exigence] Chaque élément de contexte servi porte sa provenance et son niveau de confiance ; les éléments `INSTRUCTION` et `SKILL` sont marqués comme contenu du dépôt non fiable.
- [Exigence] La description des outils MCP de contexte indique que le contenu servi est une donnée du dépôt indexé, pas une instruction pour l'agent.
- [Exigence] Le modèle de menace d'injection d'instructions par un dépôt indexé est décrit dans `SECURITY.md` et `docs/developer/current-limitations.md`.
- [Proposition] Désactiver ou limiter les sources `INSTRUCTION`/`SKILL` par projet, et exclure `vendor/`, `third_party/` et les sous-modules de la découverte d'instructions par défaut : hors de ce change (question Q4).

## Type de changement

fonctionnel (contrat public CLI/REST/MCP, erreurs des adaptateurs, configuration de processus) : profil complet.

## Périmètre et exclusions

**Dans le périmètre :**
- Une fonction du cœur de conversion des sources demandées, appelée par REST et MCP ; défaut de `find_usages` aligné sur `ResultLimitPolicy` ; divergence CLI déclarée.
- Records de métadonnées de contexte simple et fédéré dans `PublicOutput`, couverts par `PublicOutputSchema` et les tests de parité des trois canaux.
- Refus d'une valeur différente par `ProjectFileLimits.configure`, `SecurityPolicy.configure` et `IndexingCapacityGate.acquireShared` (à leur emplacement après S5), avec remise à zéro réservée aux tests.
- `ProjectNotReadyException` et `ProjectIndexBusyException`, mappées par type en REST et MCP.
- Champs de provenance et de confiance sur les éléments de contexte, phrase de description des outils MCP de contexte, modèle de menace écrit.

**Hors périmètre :**
- La journalisation serveur et l'identifiant de corrélation des mappers (`AUD-QUA-01`, change S2).
- Le déplacement de `IndexingCapacityGate` et de `ProjectIgnoreMatcher` (`AUD-ARC-04`, `AUD-ARC-02`, change S5).
- Le découpage de `ContextBuildMetadata` et des grandes méthodes (`AUD-QUA-03`, sprint S7, qui dépend de ce change).
- La désactivation par projet des sources `INSTRUCTION`/`SKILL` et l'exclusion de `vendor/`, `third_party/` et des sous-modules (Q4).
- Une option CLI de sources demandées.
- La normalisation des autres paramètres (`limit`, `tokenBudget`, `constraints`) au-delà des défauts : leurs bornes sont déjà communes (`ResultLimitPolicy.validate`, `ContextBudgetPolicy`).

## Capabilities

### New Capabilities
- `query-parameter-normalization` : conversion commune des paramètres de requête des adaptateurs (sources demandées, défauts de limite) et divergence CLI déclarée.
- `project-state-conflicts` : signalement typé, par REST et MCP, d'un projet non prêt et d'une mutation d'index déjà en cours.
- `process-wide-configuration` : unicité, pour un processus, des valeurs de configuration installées par la composition root.
- `context-content-provenance` : marquage de provenance et de confiance du contenu de contexte servi aux agents, description des outils MCP et modèle de menace écrit.

### Modified Capabilities
- `public-output-projection` : exigence ajoutée, les métadonnées de contexte simple et fédéré suivent le contrat typé de la sortie publique.

## Impact

- Code cœur : `core/src/main/java/com/nexus/search/candidate/CandidateType.java` (ou classe de normalisation voisine), `core/src/main/java/com/nexus/exposure/PublicOutput.java`, `PublicOutputSchema.java`, `PublicOutputProjection.java`, `PublicContextPolicy.java`, `core/src/main/java/com/nexus/context/ContextBuildMetadata.java`, `FederatedContextService.java`, `core/src/main/java/com/nexus/project/ProjectReadiness.java`, `core/src/main/java/com/nexus/index/ProjectIndexLockManager.java`, `core/src/main/java/com/nexus/security/ProjectFileLimits.java`, `core/src/main/java/com/nexus/config/SecurityPolicy.java`, `IndexingCapacityGate.java` (emplacement fixé par S5).
- Adaptateurs : `adapters/rest-quarkus/src/main/java/com/nexus/api/NexusApiApplicationService.java`, `IllegalStateExceptionMapper.java`, nouveaux mappers par type ; `adapters/mcp-java/src/main/java/com/nexus/mcp/McpToolArguments.java`, `NexusMcpTools.java`.
- Tests : `PublicOutputProjectionTest`, `CliOutputParityTest`, `NexusRestOutputParityTest`, `NexusMcpOutputParityTest`, `IllegalStateExceptionMapperTest`, `NexusProjectStateConflictIntegrationTest`, `NexusMcpServerIntegrationTest`, `NexusMcpErrorSanitizationTest`, `NexusMcpNegativeContractTest`, `IndexingCapacityGateTest`, `ProjectFileLimitsTest`, `NexusConfigurationTest`, `NexusRestExposureGuardTest`, nouveaux tests de la normalisation et des exceptions typées.
- Scripts : `scripts/self-smoke.ps1` garde ses 11 clés ; aucun changement attendu.
- Documentation : `docs/developer/rest-api.md`, `docs/developer/mcp.md`, `docs/developer/cli.md`, `docs/developer/context-building.md`, `docs/developer/architecture-implementation.md`, `docs/developer/current-limitations.md`, `docs/developer/query-size-policy.md`, `SECURITY.md`, `docs/developer/openspec-traceability.md`.
- Contrat public : **BREAKING** pour un client MCP qui testait le code `nexus_tool_error` sur un projet non prêt ou occupé (codes distincts) ; ajout de clés `origin` et `trust` dans chaque élément de contexte (ajout de composante permis par `ARCH-0085`) ; métadonnées inchangées en JSON, sauf clé optionnelle (Q3).
- ADR applicables : `ARCH-0075`, `ARCH-0085`, `ARCH-0086`, `SEC-0033`, ADR du change S5. Besoin d'un nouvel ADR : questions Q1 et Q2.

## Questions ouvertes

- [Question] Q1 — Le marquage de confiance des éléments de contexte et le modèle de menace d'injection fixent un modèle de confiance sur une surface publique, critère d'ADR de `.claude/rules/documentation.md`. Faut-il un ADR de sécurité qui complète `SEC-0033` (et cite `ARCH-0085`) avant la livraison ? [Proposition] Oui : un ADR `SEC-NNNN` qui complète `SEC-0033`, écrit hors de ce change par le skill `nexus-adr`. Les métadonnées typées, elles, appliquent `ARCH-0085` sans changer de clé et ne demandent pas d'ADR.
- [Question] Q2 — La règle « une valeur par processus, refus d'une valeur différente » et l'ajout de la borne de concurrence d'indexation à la liste des états de processus vont-ils dans un ADR qui complète `ARCH-0086`, ou dans la mise en œuvre et les pages développeur seulement ? L'audit visait `ARCH-0086`, qui ne peut pas être réécrit.
- [Question] Q3 — `gitContextCache` n'est présent que sous `explain` quand le cache n'est pas vide (`ContextBuildMetadata.java:100-106`, `FederatedContextService.java:375-377`). Un record l'exposerait toujours (`null` ou objet vide), ce qui change la forme JSON hors `explain`. Faut-il une composante toujours présente, ou une sous-structure séparée réservée à `explain` ?
- [Question] Q4 — La désactivation des sources `INSTRUCTION`/`SKILL` par projet et l'exclusion de `vendor/`, `third_party/` et des sous-modules de la découverte d'instructions, proposées par l'audit hors critère de sortie, font-elles l'objet d'un change séparé ? Elles touchent la découverte (`BE-0083`, `SEC-0033`) et la configuration par projet.
