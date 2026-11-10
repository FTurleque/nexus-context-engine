# Proposition

## Why

Le critère de sortie du sprint 1 de l'audit exige « une règle par classe pour les classes de bornes listées » (`docs/project/audits/2026-10-17/sprints.md:43`). Le mainteneur a clos `AUD-TST-03` le 30/10 avec des règles JaCoCo `PACKAGE` (PR #268) ; la revérification du 09/11 le classe `Partiel` : quatre classes de sécurité ou de bornes restent sans règle propre, et trois d'entre elles sont toujours sous 55 % de branches couvertes. Une règle par paquet peut rester verte pendant qu'une de ces classes perd ses derniers tests.

Source : `docs/project/audits/2026-10-17/findings.json` (`AUD-TST-03`, champs `verification` et `verification_mainteneur`), `etat-2026-11-09.md`.

## What Changes

- [Existant vérifié] Règles `PACKAGE` pour `com.nexus.security`, `com.nexus.index.jdt`, `com.nexus.index.minos`, `com.nexus.index.scip` (`core/pom.xml:98-165`) et `com.nexus.api` (`adapters/rest-quarkus/pom.xml:121-136`) ; une seule règle `CLASS`, pour `com.nexus.api.NexusApiApplicationService` (`adapters/rest-quarkus/pom.xml:141-154`).
- [Existant vérifié] Aucune règle `CLASS` pour `com.nexus.index.jdt.JdtStdioSession`, `com.nexus.index.minos.MinosPayloadReader`, `com.nexus.search.semantic.SemanticSearchConfiguration` et `com.nexus.api.NexusRestTransportPolicy` ; `com.nexus.search.semantic` n'est couvert que par la règle `BUNDLE` de `core`.
- [Existant vérifié] Mesure du 09/11 (commit `7bbfe675`, `findings.json`) : `JdtStdioSession` 13/70 branches (18,6 %), `MinosPayloadReader` 7/18 (38,9 %), `NexusRestTransportPolicy` 28/54 (51,9 %), `SemanticSearchConfiguration` 14/20 (70 %).
- [Écart] La clôture du 30/10 porte sur des règles `PACKAGE` ; le critère de sortie écrit demande des règles par classe.
- [Exigence] Chacune des quatre classes porte une règle JaCoCo `CLASS` de lignes et de branches, au plus 3 points sous sa mesure de référence.

## Type de changement

outillage (gates de build) : profil court.

## Périmètre et exclusions

**Dans le périmètre :**
- Quatre règles `CLASS` dans `core/pom.xml` et `adapters/rest-quarkus/pom.xml`, seuils tirés de la mesure du HEAD.
- La page `docs/developer/adapter-coverage-gates.md`.

**Hors périmètre :**
- Écrire des tests pour remonter la couverture de ces classes : la règle fige la mesure, elle ne l'améliore pas.
- `ProjectPathGuard` et `GeminiInstructionProvider`, cités par la preuve d'origine mais absents du critère de vérification du 09/11.
- Le contrôle d'écart plancher/mesure et la remontée des planchers existants (`sprint-12-durcir-les-gates-ajoutes`).

## Capabilities

### New Capabilities
- `coverage-gates`: planchers de couverture JaCoCo bloquants, leur écart maximal à la mesure de référence et les règles par paquet ou par classe (capacité partagée avec `sprint-12-durcir-les-gates-ajoutes`).

### Modified Capabilities

## Impact

- Build : `core/pom.xml` (exécution `jacoco-check-core`), `adapters/rest-quarkus/pom.xml` (exécution `jacoco-check-rest`).
- Documentation : `docs/developer/adapter-coverage-gates.md`, `docs/developer/openspec-traceability.md`.
- Aucun code Java.

## Questions ouvertes

- [Question] Une règle par paquet est-elle jugée suffisante pour ces classes ? Si oui, ce changement est remplacé par la réécriture du critère de sortie du sprint 1 (« une règle par paquet couvre les classes de bornes »), consignée par un ADR au format Archgate qui assume ce choix, et `AUD-TST-03` est clos sur cette base. Sinon, les tâches ci-dessous s'appliquent.
- [Question] Le plancher de branches de `JdtStdioSession` serait d'environ 16 % (mesure 18,6 % moins 3 points au plus) : un plancher aussi bas est-il accepté comme garde contre la régression, ou faut-il d'abord des tests (hors périmètre ici) ?
