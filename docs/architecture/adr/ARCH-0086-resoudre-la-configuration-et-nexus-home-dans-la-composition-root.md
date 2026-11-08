---
id: ARCH-0086
title: "Résoudre la configuration NEXUS, dont NEXUS_HOME, dans la composition root et l'injecter"
domain: architecture
rules: true
---

# Résoudre la configuration NEXUS, dont NEXUS_HOME, dans la composition root et l'injecter

- **Statut** : Accepted
- **Date** : 2026-11-08
- **Origine** : consolidation du registre ADR (audit du 2026-11-08)
- **Complète** : [ARCH-0076](ARCH-0076-garder-un-coeur-java-sans-framework-acyclique-et-verifie.md)
- **Remplace** : [ARCH-0066](archive/ARCH-0066-resoudre-la-configuration-dans-la-composition-root.md), [DATA-0019](archive/DATA-0019-stocker-les-donnees-locales-dans-un-nexus-home-configurable.md)

## Context

NEXUS persiste un registre de projets, une base SQLite et des index Lucene sans écrire dans les repositories analysés. L'emplacement de ces données doit être prévisible, local par défaut, configurable pour les environnements professionnels, déplaçable sur un autre volume et portable entre Windows, Linux et macOS. Écrire les index dans chaque repository augmenterait le risque de les versionner, compliquerait les permissions et polluerait les projets.

Le cœur reste sans framework applicatif et câblé à la main ([ARCH-0076](ARCH-0076-garder-un-coeur-java-sans-framework-acyclique-et-verifie.md)), avec pour mesure contre la duplication de configuration entre adaptateurs un modèle de configuration neutre traduit aux frontières. Sans ce modèle, chaque classe du cœur lit elle-même ses variables `NEXUS_*` (`System.getenv`) : la configuration effective n'existe nulle part en un seul objet, une variable peut être relue à chaque requête, les tests contournent l'environnement par des constructeurs ou parseurs package-private, et rien n'empêche une nouvelle lecture dispersée.

La question : **où sont stockées les données locales de NEXUS, où la configuration d'environnement du cœur est-elle lue, et comment parvient-elle aux composants ?**

## Options envisagées

Emplacement des données :

- **`.nexus/` dans chaque repository** : données proches du projet, mais pollution, risque de commit, permissions, duplication des exclusions.
- **Chemins natifs propres à chaque OS** (XDG, AppData) : intégration aux conventions système, mais davantage de branches de code et de comportements à documenter.
- **`${user.home}/.nexus` fixe** : très simple, mais impossible de déplacer les données ou d'isoler un environnement.
- **`NEXUS_HOME` configurable avec repli** : simple par défaut, configurable et testable ; convention propre à NEXUS à documenter.

Lecture de la configuration :

- **Lecture dans chaque classe de limites** : aucun changement ; configuration dispersée, non testable sans contournement, relue à chaque appel.
- **Registre global de l'environnement** (classe statique interrogée par chaque composant) : diff minimal, mais état global implicite et lecture cachée.
- **Résolution dans la composition root et injection** : l'environnement est lu une fois, résolu en un record typé, et chaque composant reçoit sa part.

## Decision

**Options retenues : `NEXUS_HOME` configurable avec repli sur `${user.home}/.nexus` ; configuration résolue dans la composition root et injectée.**

### Répertoire des données locales

Le répertoire racine NEXUS se résout dans cet ordre :

1. propriété système Java `nexus.home` ;
2. variable d'environnement `NEXUS_HOME` (lue dans l'environnement fourni par la composition root) ;
3. repli sur `${user.home}/.nexus`.

`NexusPaths` centralise la résolution des chemins ; SQLite et Lucene utilisent des sous-chemins dérivés de `NEXUS_HOME` :

```text
NEXUS_HOME/
├── nexus.db
└── indexes/
    └── <project-uuid>/
        └── lucene/
```

Les repositories source ne reçoivent aucun index NEXUS par défaut ; `.nexusignore` reste un fichier de configuration projet qui peut être versionné volontairement. Les tests injectent un répertoire temporaire par la propriété `nexus.home` et n'écrivent jamais dans le profil réel de l'utilisateur.

### Résolution et injection de la configuration

- `NexusApplicationFactory` est la seule classe de `core/src/main` qui lit l'environnement du processus (`System.getenv`, `System::getenv`), par `processEnvironment()`.
- Elle résout la configuration en un record `com.nexus.application.NexusConfiguration` depuis une `Map<String, String>` : l'environnement du processus en production, une table en test (`NexusApplicationFactory.configuration(NexusPaths, Map)`).
- Chaque classe de limites expose une fabrique pure depuis une `Map` (`from(Map)`, `maxFileSize(Map)`, `providerTimeout(Map)`, `maxConcurrentIndexing(Map)`, `configuration(NexusPaths, Map)`) ; les composants reçoivent leur part par constructeur, échéance fédérée comprise (`FederationDeadlinePolicy.from(Map)`).
- Noms de variables, bornes, défauts et messages d'erreur sont ceux des classes de limites ; la priorité est propriété système, puis variable, puis défaut.
- Deux parts valent pour tout le processus, parce que des primitives statiques les consultent à chaque ouverture : la borne de taille des lectures sans borne explicite (`ProjectFileLimits.configure`) et les postures `NEXUS_REQUIRE_STRICT_PATH_IO` / `NEXUS_REQUIRE_PRIVATE_STORAGE` (`SecurityPolicy.configure`, [SEC-0082](SEC-0082-borner-le-support-filesystem-et-la-traversee-des-chemins.md)). La composition root les installe avant d'ouvrir le stockage ; la propriété JVM correspondante reste prioritaire.
- Les points d'entrée (CLI, MCP, REST) obtiennent `NEXUS_HOME` par `NexusConfiguration.pathsFromEnvironment()`. L'adaptateur REST, sa propre composition root Quarkus, installe aussi les postures de sécurité avant son garde d'exposition ; sa configuration propre (`NexusRestSecurity`, `NexusRestProjectRootPolicy`) reste dans l'adaptateur.
- La configuration sémantique reste un paramètre distinct des fabriques de `NexusApplication`, qui permettent de la fournir explicitement ; elle se résout aussi depuis une `Map`.

## Do's and Don'ts

### Do

- Résoudre le répertoire racine par la propriété système `nexus.home`, puis par la variable d'environnement `NEXUS_HOME`, puis par repli sur `${user.home}/.nexus`.
- Placer dans ce répertoire la base `nexus.db` et les index sous `indexes/<project-uuid>/lucene/`.
- Centraliser la résolution des chemins dans `NexusPaths`.
- Injecter dans les tests un répertoire temporaire par la propriété `nexus.home`.
- Lire l'environnement du processus uniquement dans `NexusApplicationFactory`.
- Donner à toute nouvelle variable `NEXUS_*` du cœur une fabrique depuis une `Map` dans sa classe de limites, puis la porter par `NexusConfiguration`.
- Injecter la valeur résolue par constructeur dans le composant qui l'utilise.
- Tester une borne de configuration en passant une `Map` à la fabrique.

### Don't

- Ne pas écrire d'index NEXUS dans les repositories source par défaut.
- Ne pas écrire dans le profil réel de l'utilisateur pendant les tests.
- Ne pas appeler `System.getenv` ni référencer `System::getenv` ailleurs dans `core/src/main`.
- Ne pas ajouter de constructeur ou de parseur package-private dont le seul rôle est de contourner l'environnement en test.
- Ne pas changer un nom de variable, une borne, une valeur par défaut ou un message d'erreur à l'occasion du câblage de la configuration.

## Consequences

### Positives

- Aucune pollution des repositories ; emplacement central et prévisible, déplaçable sur un disque dédié ; suppression et reconstruction des index indépendantes du code source.
- La configuration effective existe en un objet, résolue une fois ; une valeur invalide échoue à la composition avec le message de sa classe de limites.
- Classes de limites et home testables sans environnement réel ni contournement.

### Négatives

- Les données d'un projet ne voyagent pas avec son repository ; un nettoyage explicite peut être nécessaire pour les projets supprimés.
- Le chemin par défaut n'utilise pas les conventions natives XDG/AppData.
- Les constructeurs de composition s'allongent (bornes de contexte, scanner, timeout de providers, limites SCIP, échéance fédérée).
- Deux parts restent des états de processus installés par la composition root ; avant leur installation, ce sont les valeurs par défaut et les propriétés JVM qui s'appliquent.
- Une valeur invalide d'une variable lue au besoin (une borne de contexte, par exemple) échoue dès la composition.

### Risques

| Risque | Impact | Mesure de maîtrise |
|---|---|---|
| Données de test écrites dans le profil réel | Élevé | Répertoire temporaire injecté par `nexus.home` dans les tests |
| Index volumineux sur le disque système | Moyen | `NEXUS_HOME` sur un autre volume |
| Permissions insuffisantes | Moyen | Répertoires créés au démarrage, erreur explicite |
| Lecture de fichier projet avant l'installation des postures | Moyen | CLI, MCP et REST composent `NexusApplication` (ou installent la posture, pour REST) avant toute lecture ; la propriété JVM reste prioritaire |
| Nouvelle lecture d'environnement hors composition root | Moyen | Règle `getenv-composition-root` de `CoreArchitectureTest` |
| Données orphelines après suppression d'un projet | Faible à moyen | Commande de purge ou de maintenance à prévoir |
| Divergence entre défaut d'un constructeur de commodité et production | Faible | La production passe toujours par `NexusApplicationFactory` ; les constructeurs de commodité appliquent les défauts historiques |

## Compliance and Enforcement

- Règle Archgate `ARCH-0086-resoudre-la-configuration-et-nexus-home-dans-la-composition-root.rules.ts` / `nexus-home-resolution-order` : `NexusPaths` déclare `HOME_PROPERTY = "nexus.home"` et `HOME_ENVIRONMENT_VARIABLE = "NEXUS_HOME"`, et `fromEnvironment(Map)` lit dans cet ordre la propriété, la variable de la table d'environnement, puis `user.home` avec `.nexus`.
- Même règle / `nexus-home-resolved-by-nexus-paths` : dans les sources de production, les littéraux `"nexus.home"`, `"NEXUS_HOME"` et `"nexus.db"` n'apparaissent que dans `NexusPaths.java`, et aucun autre fichier ne lit `NexusPaths.HOME_PROPERTY` ou `NexusPaths.HOME_ENVIRONMENT_VARIABLE` par `System.getProperty`, `System.getenv` ou le `get` d'une table d'environnement.
- Test `CoreArchitectureTest#processEnvironmentIsReadOnlyByTheCompositionRoot` (règle `getenv-composition-root` de `CoreArchitectureRules`) : aucun `System.getenv` ni `System::getenv`, écrit simplement, pleinement qualifié ou importé en statique, dans `core/src/main/java` hors de `NexusApplicationFactory` ; cas négatif `#getenvOutsideTheCompositionRootFailsGetenvRule`, références de méthode comprises.
- Test `NexusConfigurationTest` : défauts historiques sur une table vide, propagation de chaque part, message d'erreur d'une borne invalide.
- Tests des classes de limites par `Map` : `ProjectFileLimitsTest`, `ScipIndexLimitsTest`, `ContextMaterializationBudgetTest`, `ProjectScanLimitsTest`, `ContextDiscoveryLimitsTest`, `NexusPathsTest` (répertoires privés créés sous le home), `FederationDeadlinePolicyTest`.
- Ces tests sont lancés par `./mvnw clean install` (workflow `.github/workflows/ci.yml` : job Linux reactor, et job Windows gate par `scripts/validate-phase-6.ps1`).
- Aucun contrôle automatisé de l'absence d'index dans le repository source ; vérifié en revue. Revue : une nouvelle variable `NEXUS_*` du cœur passe par `NexusConfiguration`.

## Conditions de réexamen

La décision est réévaluée si NEXUS devient une application desktop exigeant une intégration stricte aux répertoires système, ou un service multi-utilisateur à stockage administré ; si le cœur adopte un mécanisme de configuration fourni par un framework (ce qui remettrait en cause [ARCH-0076](ARCH-0076-garder-un-coeur-java-sans-framework-acyclique-et-verifie.md)) ; ou si une part de configuration doit varier entre deux instances de `NexusApplication` d'un même processus alors qu'elle est installée pour le processus.

## Mise en œuvre

- Chemins : `core/src/main/java/com/nexus/config/NexusPaths.java` (`fromEnvironment(Map)`, `databaseFile()`, `indexesDirectory()`, `projectLuceneIndex(UUID)`, ainsi que `locksDirectory()` et `projectSemanticLuceneIndex(UUID)` sous le même home).
- Configuration : record `core/src/main/java/com/nexus/application/NexusConfiguration.java` (`pathsFromEnvironment()`, installation de `ProjectFileLimits.configure` et `SecurityPolicy.configure`) ; résolution `NexusApplicationFactory.configuration(NexusPaths, Map)` et lecture unique `NexusApplicationFactory.processEnvironment()`.
- Stockage privé : `core/src/main/java/com/nexus/security/PrivateStoragePolicy.java` ; `NexusPaths` ne fait que résoudre des chemins.
- Racines approuvées JDT portées par `JdtLanguageServerCodeIntelligenceProvider.Configuration#trustedProjectRoots` et appliquées par `BoundedJdtProcessBuilder`.
- Adaptateur REST : `adapters/rest-quarkus/src/main/java/com/nexus/api/NexusRestExposureGuard.java` (installation des postures), `NexusApiApplicationService` (`pathsFromEnvironment()`).
- Documentation : [architecture-implementation.md](../../developer/architecture-implementation.md).

## References

- [ARCH-0005](ARCH-0005-adopter-un-fonctionnement-local-first-et-opt-in.md) — local-first.
- [ARCH-0076](ARCH-0076-garder-un-coeur-java-sans-framework-acyclique-et-verifie.md) — cœur sans framework, câblage manuel, test d'architecture.
- [DATA-0073](DATA-0073-tenir-sqlite-pour-source-de-verite-et-lucene-pour-index-derive.md) — SQLite source de vérité, Lucene index dérivé.
- [SEC-0082](SEC-0082-borner-le-support-filesystem-et-la-traversee-des-chemins.md) — postures `NEXUS_REQUIRE_STRICT_PATH_IO` et `NEXUS_REQUIRE_PRIVATE_STORAGE`.
- Changement OpenSpec `openspec/changes/archive/2026-11-07-sprint-08-facade-configuration-durcissements/`.
- [Vue arc42 des décisions](../arc42/09-decisions.md).
