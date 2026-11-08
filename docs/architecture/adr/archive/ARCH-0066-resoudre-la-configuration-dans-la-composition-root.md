---
id: ARCH-0066
title: "Résoudre la configuration NEXUS dans la composition root et l'injecter"
domain: architecture
rules: false
---

# Résoudre la configuration NEXUS dans la composition root et l'injecter

> **Archivé le 2026-11-08** : remplacé par [ARCH-0086](../ARCH-0086-resoudre-la-configuration-et-nexus-home-dans-la-composition-root.md) (configuration et NEXUS_HOME résolus dans la composition root). Ce document est conservé pour l'historique ; il n'est plus lu par Archgate.

- **Statut** : Superseded
- **Date** : 2026-11-07
- **Origine** : audit du 2026-10-17, constat `AUD-ARC-12` ; changement OpenSpec `sprint-08-facade-configuration-durcissements` (tâches 3.1 à 3.4)
- **Complète** : [ARCH-0003](ARCH-0003-conserver-un-coeur-java-sans-framework-applicatif.md)
- **Remplacé par** : [ARCH-0086](../ARCH-0086-resoudre-la-configuration-et-nexus-home-dans-la-composition-root.md)

## Context

[ARCH-0003](ARCH-0003-conserver-un-coeur-java-sans-framework-applicatif.md) garde le cœur sans framework applicatif : le câblage est manuel, dans `NexusApplicationFactory`, et ARCH-0003 retient le risque « duplication de configuration entre adaptateurs », avec pour mesure un modèle de configuration NEXUS neutre traduit aux frontières. Ce modèle n'existait pas : treize classes du cœur lisaient elles-mêmes les variables `NEXUS_*` par `System.getenv` ou la référence de méthode `System::getenv` (`NexusPaths`, `SecurityPolicy`, `ContextMaterializationLimits`, `ContextDiscoveryLimits`, `IndexingCapacityGate`, `ProjectIndexingService`, `JdtLanguageServerCodeIntelligenceProvider`, le constructeur de processus JDT, `ProjectScanLimits`, `ScipIndexLimits`, `ProjectFileLimits`, `FederationDeadlinePolicy` et la lecture sémantique de `NexusApplicationFactory`).

Conséquences observées : la configuration effective n'existait nulle part en un seul objet ; une même variable pouvait être relue à chaque requête ; les tests contournaient l'environnement par des constructeurs ou des parseurs package-private ; aucun contrôle n'empêchait une nouvelle lecture dispersée.

La question : **où la configuration d'environnement du cœur est-elle lue, et comment parvient-elle aux composants ?**

## Options envisagées

- **Option A — Lecture dans chaque classe de limites (statu quo)** : aucun changement ; configuration dispersée, non testable sans contournement, relue à chaque appel.
- **Option B — Registre global de l'environnement** (une classe statique que chaque composant interroge) : diff minimal ; reste un état global implicite, et les composants dépendent toujours d'une lecture cachée.
- **Option C — Résolution dans la composition root et injection** : la composition root lit l'environnement une fois, le résout en un record typé et passe à chaque composant sa part ; les composants ne connaissent qu'une `Map` ou leurs valeurs.

## Decision

**Option retenue : Option C.**

- `NexusApplicationFactory` est la seule classe de `core/src/main` qui lit l'environnement du processus (`System.getenv`, `System::getenv`), par `processEnvironment()`.
- Elle résout la configuration en un record `com.nexus.application.NexusConfiguration` depuis une `Map<String, String>` : l'environnement du processus en production, une table en test (`NexusApplicationFactory.configuration(NexusPaths, Map)`).
- Chaque classe de limites expose une fabrique pure depuis une `Map` (`from(Map)`, `maxFileSize(Map)`, `providerTimeout(Map)`, `maxConcurrentIndexing(Map)`, `configuration(NexusPaths, Map)`) ; les composants reçoivent leur part par constructeur, échéance fédérée comprise (`FederationDeadlinePolicy.from(Map)`).
- Noms de variables, bornes, défauts et messages d'erreur ne changent pas.
- Deux parts valent pour tout le processus, parce que des primitives statiques les consultent à chaque ouverture : la borne de taille des lectures sans borne explicite (`ProjectFileLimits.configure`) et les postures `NEXUS_REQUIRE_STRICT_PATH_IO` / `NEXUS_REQUIRE_PRIVATE_STORAGE` (`SecurityPolicy.configure`). La composition root les installe avant d'ouvrir le stockage. La propriété JVM correspondante reste prioritaire.
- Les points d'entrée (CLI, MCP, REST) obtiennent `NEXUS_HOME` par `NexusConfiguration.pathsFromEnvironment()`. L'adaptateur REST, sa propre composition root Quarkus, installe aussi les postures de sécurité avant son garde d'exposition ; sa configuration propre (`NexusRestSecurity`, `NexusRestProjectRootPolicy`) reste dans l'adaptateur.
- La configuration sémantique reste un paramètre distinct des fabriques de `NexusApplication`, qui permettent de la fournir explicitement ; elle se résout aussi depuis une `Map`.

Cette décision ne change ni les variables `NEXUS_*` ni leur priorité (propriété système, puis variable, puis défaut ; [DATA-0019](DATA-0019-stocker-les-donnees-locales-dans-un-nexus-home-configurable.md) pour `NEXUS_HOME`).

## Do's and Don'ts

### Do

- Lire l'environnement du processus uniquement dans `NexusApplicationFactory`.
- Donner à toute nouvelle variable `NEXUS_*` du cœur une fabrique depuis une `Map` dans sa classe de limites, puis la porter par `NexusConfiguration`.
- Injecter la valeur résolue par constructeur dans le composant qui l'utilise.
- Tester une borne de configuration en passant une `Map` à la fabrique.

### Don't

- Ne pas appeler `System.getenv` ni référencer `System::getenv` ailleurs dans `core/src/main`.
- Ne pas ajouter de constructeur ou de parseur package-private dont le seul rôle est de contourner l'environnement en test.
- Ne pas changer un nom de variable, une borne, une valeur par défaut ou un message d'erreur à l'occasion de ce câblage.

## Consequences

### Positives

- La configuration effective existe en un objet, résolue une fois ; une valeur invalide échoue à la composition avec le message de sa classe de limites.
- Les classes de limites sont testables sans environnement réel ni contournement.
- La règle `getenv-composition-root` du test d'architecture empêche le retour d'une lecture dispersée.

### Négatives

- Les constructeurs de composition s'allongent (bornes de contexte, scanner, timeout de providers, limites SCIP, échéance fédérée).
- Deux parts restent des états de processus installés par la composition root (taille de lecture par défaut, postures de sécurité) ; avant leur installation, ce sont les valeurs par défaut et les propriétés JVM qui s'appliquent.
- Une valeur invalide d'une variable qui n'était lue qu'au besoin (par exemple une borne de contexte) échoue désormais dès la composition.

### Risques

| Risque | Impact | Mesure de maîtrise |
|---|---|---|
| Lecture de fichier projet avant l'installation des postures | Moyen | CLI, MCP et REST composent `NexusApplication` (ou installent la posture, pour REST) avant toute lecture ; la propriété JVM reste prioritaire |
| Nouvelle lecture d'environnement hors composition root | Moyen | Règle `getenv-composition-root` de `CoreArchitectureTest` |
| Divergence entre valeur par défaut d'un constructeur de commodité et production | Faible | La production passe toujours par `NexusApplicationFactory` ; les constructeurs de commodité appliquent les défauts historiques |

## Compliance and Enforcement

- Test `CoreArchitectureTest#processEnvironmentIsReadOnlyByTheCompositionRoot` (règle `getenv-composition-root` de `CoreArchitectureRules`) : aucun `System.getenv` ni `System::getenv`, écrit simplement, pleinement qualifié ou importé en statique, dans `core/src/main/java` hors de `NexusApplicationFactory` ; cas négatif `#getenvOutsideTheCompositionRootFailsGetenvRule`, références de méthode comprises.
- Test `NexusConfigurationTest` : défauts historiques sur une table vide, propagation de chaque part, message d'erreur d'une borne invalide.
- Tests des classes de limites par `Map` : `ProjectFileLimitsTest`, `ScipIndexLimitsTest`, `ContextMaterializationBudgetTest`, `ProjectScanLimitsTest`, `ContextDiscoveryLimitsTest`, `NexusPathsTest`, `FederationDeadlinePolicyTest`.
- Règle Archgate `DATA-0019-stocker-les-donnees-locales-dans-un-nexus-home-configurable.rules.ts` / `nexus-home-resolution-order` : `NexusPaths.fromEnvironment(Map)` garde l'ordre propriété, variable, `user.home`.
- Revue : une nouvelle variable `NEXUS_*` du cœur passe par `NexusConfiguration`.

## Conditions de réexamen

La décision est réévaluée si le cœur adopte un mécanisme de configuration fourni par un framework (ce qui remettrait en cause ARCH-0003), ou si une part de configuration doit varier entre deux instances de `NexusApplication` d'un même processus alors qu'elle est aujourd'hui installée pour le processus.

## Mise en œuvre

- Record `core/src/main/java/com/nexus/application/NexusConfiguration.java` ; résolution `NexusApplicationFactory.configuration(NexusPaths, Map)` et lecture unique `NexusApplicationFactory.processEnvironment()`.
- Durcissement du stockage privé déplacé de `NexusPaths` vers `com.nexus.security.PrivateStoragePolicy` (`AUD-ARC-14`) ; `NexusPaths` ne fait que résoudre des chemins.
- Valeur brute des racines approuvées JDT portée par `JdtLanguageServerCodeIntelligenceProvider.Configuration#trustedProjectRoots` et appliquée par `BoundedJdtProcessBuilder` (`AUD-ARC-19`).

## References

- [ARCH-0003](ARCH-0003-conserver-un-coeur-java-sans-framework-applicatif.md) — cœur sans framework, câblage manuel.
- [ARCH-0059](ARCH-0059-verifier-les-frontieres-du-coeur-par-un-test-d-architecture.md) — test d'architecture du cœur.
- [DATA-0019](DATA-0019-stocker-les-donnees-locales-dans-un-nexus-home-configurable.md) — résolution de `NEXUS_HOME`.
- [SEC-0048](SEC-0048-distinguer-traversee-stricte-et-compatibilite-filesystem.md) — postures `NEXUS_REQUIRE_STRICT_PATH_IO` et `NEXUS_REQUIRE_PRIVATE_STORAGE`.
- Changement OpenSpec `openspec/changes/archive/2026-11-07-sprint-08-facade-configuration-durcissements/`.
- [Vue arc42 des décisions](../../arc42/09-decisions.md), [architecture-implementation.md](../../../developer/architecture-implementation.md).
