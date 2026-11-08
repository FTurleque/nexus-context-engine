---
id: ARCH-0085
title: "Stabiliser le contrat de sortie publique dans la projection du cœur : CLI humaine ou JSON, codes de sortie, parité REST et MCP"
domain: architecture
rules: true
---

# Stabiliser le contrat de sortie publique dans la projection du cœur : CLI humaine ou JSON, codes de sortie, parité REST et MCP

- **Statut** : Accepted
- **Date** : 2026-11-08
- **Origine** : consolidation du registre ADR (audit du 2026-11-08)
- **Complète** : [ARCH-0075](ARCH-0075-exposer-le-coeur-par-des-adaptateurs-minces-isoles.md)
- **Remplace** : [ARCH-0030](archive/ARCH-0030-stabiliser-le-contrat-cli-avec-sorties-humaines-json-et-codes-de-sortie.md), [ARCH-0061](archive/ARCH-0061-decider-le-contrat-de-sortie-publique-dans-la-projection-du-coeur.md), [GEN-0015](archive/GEN-0015-valider-le-mvp-par-la-cli-avant-les-integrations.md)
- **Remplacé par** : —

## Context

`NexusCli` expose le registre de projets, l'indexation, l'inspection, la recherche et la construction de contexte. Elle sert à un développeur, à des scripts (PowerShell, CI, wrappers) et de surface de validation de bout en bout du moteur, sans LLM. REST et MCP sont des adaptateurs minces qui délèguent à `NexusApplication` ([ARCH-0075](ARCH-0075-exposer-le-coeur-par-des-adaptateurs-minces-isoles.md)) ; reste à fixer qui décide de la forme des réponses.

Les besoins sont : une sortie lisible par défaut et une sortie structurée, déterministe et facile à parser ; une séparation nette entre données et erreurs ; des codes de sortie stables ; une logique CLI testable sans tuer la JVM ; aucun format de sérialisation imposé aux packages métier. L'audit du 2026-10-17 a relevé trois projections écrites à part (CLI, MCP, REST), chacune appelant `PublicContextPolicy` et calculant ses chemins, avec des clés différentes pour les mêmes objets (`excerpt`, `id`, `rank`, `sourceProvider`, `rootPath`, résultats fédérés aplatis ou imbriqués), un ordre de `scoreComponents` dépendant de la JVM (AUD-QUA-01) et des `signature` et `qualifiedName` de providers exposés sans redaction (AUD-SEC-03).

La question est : **quel contrat de sortie publique — formats, codes, flux, clés — tiennent la CLI, REST et MCP, et où est-il décidé ?**

## Options envisagées

- **Formats de la CLI** : texte seul (fragile pour l'automatisation, dépendant de la langue) ; JSON seul (expérience développeur médiocre) ; **texte par défaut et JSON opt-in par `--json`** (deux rendus à maintenir) ; framework CLI complet et DTO publics versionnés (dépendance et complexité sans besoin démontré).
- **Lieu de la forme des réponses** : **projection unique dans le cœur, en records sérialisés tels quels** (un correctif vaut pour les trois canaux ; changement de contrat ponctuel pour les clients qui dépendaient d'une divergence) ; projection en `Map` ordonnées (forme non typée, clé ajoutée invisible au compilateur) ; trois mappers et un test de parité (chaque correctif écrit trois fois).

## Decision

**Options retenues : sortie humaine par défaut et JSON opt-in, codes de sortie stables et séparation `stdout`/`stderr` ; projection unique dans le cœur, en records sérialisés tels quels par les trois canaux.**

### CLI, adaptateur mince

- La CLI est un adaptateur mince qui passe par `NexusApplication` : elle ne contient ni logique de recherche, ni ranking, ni politique de budget. Lire les bornes publiées par `ContextBudgetPolicy` et `ResultLimitPolicy` reste permis.
- La CLI expose au minimum `nexus project add`, `nexus project list`, `nexus index`, `nexus search`, `nexus context` et `nexus inspect`, avec `--budget`, `--explain`, une sortie lisible par un humain et une sortie JSON (`--json`).

### Formats, flux et codes de sortie

- Sans `--json`, la CLI produit une sortie destinée au développeur ; avec `--json`, option globale placée avec les options de la commande, chaque commande émet un document JSON unique sur `stdout`.
- Les erreurs sont émises sur `stderr` ; en mode JSON, elles sont structurées. `stdout` reste réservé aux données de succès.
- Codes de sortie : `0` succès, `2` erreur d'utilisation ou arguments invalides, `1` erreur d'exécution NEXUS ou erreur inattendue.
- La logique principale retourne un code de sortie testable ; seul `main` appelle `System.exit`.
- Jackson est utilisé uniquement comme infrastructure de sérialisation de l'adaptateur CLI ; aucune classe des packages `index`, `search`, `ranking`, `context` ou `project` n'en dépend.

### Projection publique unique

- `com.nexus.exposure.PublicOutputProjection` est le seul point qui construit une réponse publique de recherche, de recherche fédérée, de contexte, de contexte fédéré, d'indexation, de projet, de symbole et de relation. Il produit les records de `com.nexus.exposure.PublicOutput`, dont l'ordre des composantes est l'ordre des clés JSON.
- La projection applique la politique de sortie : chemins relatifs au projet par `PublicProjectPathPolicy`, diagnostics filtrés par `PublicDiagnosticPolicy`, `PublicContextPolicy` pour les contextes, redaction de `excerpt`, `id`, `qualifiedName`, `signature`, `source` et `target`, tri par clé de `scoreComponents` et des métadonnées.
- Le contrat retient la forme la plus complète et la plus sûre : `rank`, `id` et `excerpt` sur chaque résultat classé, `sourceProvider` sur chaque symbole, résultats fédérés imbriqués (`project`, `result` ou `item`), aucune clé `rootPath`.

### Parité CLI, REST et MCP

- La CLI, REST et MCP sérialisent ces records sans ajouter, retirer, renommer ni réordonner de clé. Le rendu humain de la CLI lit les mêmes records.
- Les seules divergences sont déclarées par `com.nexus.exposure.PublicOutputSchema` : la clé `command` en tête des objets JSON de la CLI, et `find_symbol` et `find_usages`, outils MCP sans équivalent CLI ni REST.

### Hors décision

La sélection des résultats et la persistance ne changent pas : la redaction des métadonnées de symbole se fait à la sortie. Le parsing des arguments de la CLI reste manuel.

## Do's and Don'ts

### Do

- Garder la sortie humaine par défaut et émettre, avec `--json`, un document JSON unique sur `stdout`.
- Émettre les erreurs sur `stderr`, structurées en mode JSON.
- Retourner `0` en cas de succès, `2` pour une erreur d'utilisation ou d'arguments invalides, `1` pour une erreur d'exécution ou inattendue.
- Faire déléguer chaque commande CLI à `NexusApplication`.
- Construire toute réponse publique CLI, REST ou MCP par `PublicOutputProjection`.
- Ajouter un champ public en ajoutant une composante au record de `PublicOutput` concerné, pour les trois canaux à la fois.
- Déclarer dans `PublicOutputSchema` toute divergence voulue entre canaux.
- Trier par clé toute map exposée.

### Don't

- Ne pas appeler `System.exit` ailleurs que dans `main`.
- Ne pas mettre de logique de recherche, de ranking ni de politique de budget dans la CLI.
- Ne pas retirer de la CLI une commande ou option du flux minimal.
- Ne pas utiliser Jackson hors de l'infrastructure de sérialisation de l'adaptateur CLI.
- Ne pas recomposer, compléter ou renommer dans un adaptateur une structure produite par la projection.
- Ne pas appeler `PublicContextPolicy`, `PublicProjectPathPolicy` ou `SensitiveContentRedactor` depuis un adaptateur pour façonner une réponse.
- Ne pas exposer une map dans son ordre d'itération interne.

## Consequences

### Positives

- Scripts et outils consomment la CLI sans parser de texte localisé ; les tests appellent la CLI avec des flux injectés, sans tuer la JVM.
- Un correctif de sortie (ordre, chemins, redaction) s'écrit une fois et vaut pour les trois canaux ; la parité est vérifiée par des tests.
- Deux exécutions de la même requête produisent le même JSON, aux durées près.
- Le moteur se valide de bout en bout depuis la CLI, sans LLM ni serveur.

### Négatives

- Deux formats de sortie CLI à maintenir ; le contrat JSON est une surface publique à faire évoluer avec prudence.
- Le cœur porte les noms des clés publiques des trois canaux.
- Le parsing manuel des arguments reste à maintenir.

### Risques

| Risque | Impact | Mesure de maîtrise |
|---|---|---|
| Une composante ajoutée à un record de `PublicOutput` expose une donnée sans redaction | Élevé | La projection est le seul constructeur des records ; la revue vérifie la politique appliquée à chaque nouvelle composante |
| Logique métier introduite dans une commande CLI | Élevé | Règle `cli-delegates-to-application-services` |
| Bruit de logs sur `stdout` | Élevé | `stdout` réservé au résultat ; diagnostics sur `stderr` |
| Changement accidentel de code de sortie | Moyen | Tests dédiés aux succès et erreurs d'utilisation ; règle `cli-only-main-calls-system-exit` |
| Jackson change l'ordre de sérialisation des records | Faible | Les tests de parité comparent l'ordre des clés de chaque canal au contrat |

## Compliance and Enforcement

- Règle Archgate compagnon `ARCH-0085-stabiliser-le-contrat-de-sortie-publique-cli-rest-mcp.rules.ts` :
  - `cli-only-main-calls-system-exit` : dans `core/src/main/java`, commentaires et littéraux effacés, `System.exit` (ou `Runtime.exit`/`halt`) n'apparaît que dans le corps de `NexusCli.main` ;
  - `cli-delegates-to-application-services` : le paquet `com.nexus.cli` ne référence ni Lucene, SQLite/JDBC, JavaParser, JGit, `com.nexus.persistence`, `com.nexus.search.lucene`, `com.nexus.search.semantic`, `com.nexus.ranking.graph`, ni les classes de recherche, de ranking et de sélection sous budget, et référence `NexusApplication` ;
  - `cli-exposes-mvp-commands` : `NexusCli.java` porte `project add`/`list`, `index`, `search`, `context`, `inspect`, `--budget`, `--explain` et `--json`.
- `NexusCliTest` (`rendersHelpAsJsonWithoutInitializingAProject`, `returnsStructuredUsageErrorForUnknownCommand`, `executesTheMvpFlowThroughStableJsonOutputs`, `core/src/test/java/com/nexus/cli`).
- `PublicOutputProjectionTest` (cœur) : chemins relatifs, `excerpt` redigé, `id`, tri de `scoreComponents` pour deux ordres d'insertion, redaction des métadonnées de symbole, détection d'une clé ajoutée, retirée ou déplacée par `PublicOutputSchema`.
- `CliOutputParityTest` (cœur), `NexusRestOutputParityTest` (REST) et `NexusMcpOutputParityTest` (MCP) : sur la même fixture, les clés JSON de chaque canal, et leur ordre, égalent le contrat de `PublicOutputSchema` pour la recherche, la recherche fédérée, le contexte et le contexte fédéré ; MCP vérifie aussi `find_symbol` et `find_usages`.
- Tests lancés par `./mvnw -B clean install` (job « Linux reactor Maven build » de `.github/workflows/ci.yml`) ; `scripts/self-smoke.ps1` (via `/nx-validate`) indexe le dépôt et construit un contexte par le JAR CLI.
- Confinement de Jackson à l'adaptateur CLI : aucun contrôle automatisé, la décision n'étant pas respectée (voir « Écarts constatés »).
- Revue : aucun adaptateur ne construit de `Map` de réponse ni n'appelle une politique de sortie directement.

## Écarts constatés

Constat du 2026-10-29 (la décision ci-dessus est inchangée) :

- Jackson est importé hors de l'adaptateur CLI (`com.nexus.cli`), y compris dans les packages `index` et `search` que la confirmation exclut : `core/src/main/java/com/nexus/index/jdt/JdtDocumentMessages.java:3`, `JdtJsonRpcFrameReader.java:3`, `JdtLanguageServerCodeIntelligenceProvider.java:6`, `JdtLocationMapper.java:5`, `JdtRelationCollector.java:3`, `JdtStdioSession.java:3`, `JdtSymbolMapper.java:3`, `JdtWorkspaceMessages.java:3` ; `core/src/main/java/com/nexus/index/minos/MinosCodeIndexImporter.java:4`, `MinosDocumentParser.java:3`, `MinosFactMapper.java:5` ; `core/src/main/java/com/nexus/search/semantic/ollama/OllamaEmbeddingProvider.java:3` ; `core/src/main/java/com/nexus/persistence/sqlite/SqliteIndexSql.java:3`.

Relevé du 2026-11-08 : constat toujours vrai ; au HEAD, l'import Jackson de `JdtLanguageServerCodeIntelligenceProvider.java` est aux lignes 8-9, les autres références sont inchangées.

## Conditions de réexamen

La décision est réévaluée si : le nombre de commandes et d'options rend le parsing manuel difficile à maintenir ; un contrat CLI public versionné exige des DTO dédiés plus formels ; plusieurs plateformes imposent une bibliothèque CLI spécialisée ; un canal doit exposer une forme qu'aucun autre ne peut porter ; un quatrième canal public apparaît. Une modification substantielle donne lieu à un nouvel ADR qui remplace explicitement celui-ci.

## Mise en œuvre

- CLI : `core/src/main/java/com/nexus/cli/NexusCli.java` (codes `EXIT_SUCCESS`, `EXIT_RUNTIME_ERROR`, `EXIT_USAGE_ERROR` en `:29-31`, `System.exit` dans `main` en `:43`), `CliRenderer.java` (sérialisation Jackson et rendu terminal), `TerminalTextSanitizer.java`.
- Projection : `core/src/main/java/com/nexus/exposure/PublicOutputProjection.java`, `PublicOutput.java`, `PublicOutputSchema.java` (`CLI_COMMAND_FIELD` `:21`, `MCP_ONLY_OPERATIONS` `:24`), `PublicContextPolicy.java`.
- Canaux : `adapters/rest-quarkus/src/main/java/com/nexus/api/ApiMapper.java`, `adapters/mcp-java/src/main/java/com/nexus/mcp/NexusMcpTools.java`.
- Pages développeur : [CLI](../../developer/cli.md), [REST](../../developer/rest-api.md), [MCP](../../developer/mcp.md).

## References

- [ARCH-0075](ARCH-0075-exposer-le-coeur-par-des-adaptateurs-minces-isoles.md), [ARCH-0076](ARCH-0076-garder-un-coeur-java-sans-framework-acyclique-et-verifie.md), [ARCH-0077](ARCH-0077-positionner-nexus-comme-moteur-de-contexte-independant.md), [BE-0072](BE-0072-construire-le-contextbundle-sous-budget-de-tokens.md).
- Changement OpenSpec `openspec/changes/archive/2026-10-31-sprint-04-sortie-publique-et-redaction/` et spécification `openspec/specs/public-output-projection/`.
- Audit du 2026-10-17 : `docs/project/audits/2026-10-17/findings.md` (AUD-QUA-09, AUD-ARC-08, AUD-QUA-01, AUD-SEC-03).
