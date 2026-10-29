---
id: DATA-0049
title: "Identité repository par composants et diagnostics publics"
domain: data
rules: false
---

# Identité repository par composants et diagnostics publics

- **Statut** : Accepted
- **Date** : 2026-09-09

## Context

Le remplacement textuel du backslash par `/` confondait sous POSIX le fichier
`a\b.java` avec `a/b.java`. Cette ambiguïté affectait l'identité SQLite, les index
dérivés et les relectures. Une ancienne clé ne permet pas de reconstruire son
origine physique. Les diagnostics libres pouvaient aussi exposer le suffixe d'un
chemin interne comportant des espaces.

## Decision

`com.nexus.paths.RepositoryPath` définit le contrat unique. L'encodage filesystem
itère les composants du `Path` relatif et les joint par `/`. Il conserve la casse,
les espaces et les caractères de chaque composant. L'identité fichier refuse
racine, composants vides, `.` et `..`, et NUL. La chaîne vide n'est admise que par
`encode` pour représenter explicitement un scope racine.

La reconstruction utilise le filesystem du projet, un composant à la fois. Un
composant non représentable à l'identique est refusé : un backslash POSIX ne peut
pas devenir silencieusement un séparateur Windows. Le contrôle de confinement est
lexical ; `ProjectPathGuard` puis `SafeFileIO` restent responsables des accès
physiques, des symlinks, de la revalidation et des lectures bornées. Les modes
strict et compatibility de l'ADR-0048 restent inchangés.

Les frontières restent explicites :

- filesystem : `fromPath` / `encode` ;
- identités persistées : constructeur validant, puis `resolve` / `toPath` ;
- SCIP : `Document.relative_path` utilise `/`, sans composants vides, `.` ou `..`,
  conformément au [protocole SCIP](https://github.com/sourcegraph/scip/blob/main/scip.proto) ;
- MINOS : le contrat d'import `filePath` est une chaîne relative à séparateurs `/`,
  sans correction ni suppression d'espaces ; l'appartenance à l'allowlist NEXUS est exacte ;
- Git : les chaînes JGit ne sont pas réinterprétées, les cibles filesystem sont
  encodées par composants pour les comparer aux chemins Git ;
- JDT/LSP : parser l'URI `file:` puis le `Path` natif avant encodage, et conserver
  les frontières de confinement et de sélection des sources ;
- instructions : références relatives à séparateurs `/`, validation avant résolution ;
- sortie publique : `PublicProjectPathPolicy`, distincte du contrôle d'accès I/O.

Les formes drive/UNC externes sont refusées par le parseur provider. Les noms POSIX
contenant un backslash non initial restent littéraux. Aucun repli ne cherche un
autre fichier quand un chemin provider est inconnu ou incompatible.

## Do's and Don'ts

### Do

- Encoder les identités repository par `com.nexus.paths.RepositoryPath` : composants du `Path` relatif joints par `/`, casse, espaces et caractères de chaque composant conservés.
- Refuser dans une identité fichier la racine, les composants vides, `.`, `..` et NUL ; n'admettre la chaîne vide que par `encode`, pour le scope racine.
- Reconstruire un chemin par le filesystem du projet, un composant à la fois, et refuser un composant non représentable à l'identique.
- Laisser à `ProjectPathGuard` puis `SafeFileIO` les accès physiques, les symlinks, la revalidation et les lectures bornées.
- Passer par `fromPath` / `encode` pour le filesystem, par le constructeur validant puis `resolve` / `toPath` pour les identités persistées, et par `PublicProjectPathPolicy` pour la sortie publique.
- Refuser dans le parseur provider les formes drive et UNC externes.

### Don't

- Ne pas transformer silencieusement un backslash POSIX en séparateur Windows.
- Ne pas chercher un autre fichier quand un chemin provider est inconnu ou incompatible.
- Ne pas réinterpréter les chaînes JGit.

## Upgrade et invalidation

V007 est une nouvelle migration checksumée ; V001–V006 sont inchangées. Elle
supprime les fichiers indexés, donc les symboles et relations via les FK en
cascade, incrémente les générations et remet tous les projets à `NOT_INDEXED`
avec `last_indexed_at = NULL`. Projets, racines, langues et technologies restent
disponibles. Les chemins historiques ne sont jamais devinés.

Le service refuse les recherches hors `READY`. L'indexation suivante reconstruit
complètement SQLite et Lucene avant `READY`. Le fingerprint porte un domaine
`nexus-repository-path-v2`, également pour les corpus sans backslash : un index
sémantique resté désactivé pendant l'upgrade ne peut redevenir compatible plus
tard par hasard. La reconstruction sémantique suit son mécanisme de provenance.
Les caches Git sont en mémoire d'instance et disparaissent au redémarrage ;
aucune migration de cache Git sur disque n'est nécessaire.

## Diagnostics

Un diagnostic structuré conserve code, message, chemin repository et catégorie
de cause séparés. La matérialisation utilise cette projection. Les métadonnées
publiques restent bornées, récursivement filtrées et fermées aux objets inconnus.

Un chemin interne inconnu détecté dans du texte libre entraîne le remplacement
du message entier par `[INTERNAL_PATH]`. Un espace, une virgule ou une parenthèse
peuvent appartenir au nom physique ; aucune de ces ponctuations n'est une borne
de confidentialité sûre. Les racines connues peuvent être projetées en chemin
repository relatif. Les requêtes du client restent séparées des diagnostics.

## Consequences

Un rebuild après upgrade est obligatoire. Les diagnostics libres ambigus perdent
leur détail plutôt que de divulguer un suffixe ; les producteurs structurés
conservent un code et un chemin relatif utiles. Aucune dépendance supplémentaire,
aucune modification des garanties REST, SafeFileIO ou des seuils de couverture.


### Compatibilité du producteur MINOS historique

L'inspection de `NexusExportService` du producteur MINOS a confirmé un encodage
historique par remplacement textuel. Un consommateur ne peut pas réparer une
information déjà perdue. Sur un corpus contenant un backslash, NEXUS exige donc
`repositoryPathFormat=repository-components-v2` et contrôle cette déclaration
avant toute lecture source. Le producteur doit être corrigé pour émettre ce
format. Le corpus ordinaire reste compatible avec les exports v1 existants.

## Compliance and Enforcement

- Tests `RepositoryPathTest` (`core/src/test/java/com/nexus/paths/`), `PosixPathIdentityIntegrationTest` (`core/src/test/java/com/nexus/index/`), `SchemaMigratorRepositoryPathUpgradeTest` (`core/src/test/java/com/nexus/persistence/sqlite/`, invalidation V007 puis reconstruction), `MinosProjectPathGuardTest.mapsPosixBackslashUsingExactScannedIdentity`, `PublicProjectPathPolicyTest` et `PublicDiagnosticPolicyTest` (`core/src/test/java/com/nexus/security/`), `ApiMapperPrivacyTest` (`adapters/rest-quarkus`) et `NexusMcpNegativeContractTest` (`adapters/mcp-java`) pour `[INTERNAL_PATH]`, lancés par `mvnw clean install` (workflow `.github/workflows/ci.yml` : job Linux reactor, et job Windows gate par `scripts/validate-phase-6.ps1`).
- Aucune règle Archgate : une interdiction textuelle du remplacement de séparateur toucherait aussi `core/src/main/java/com/nexus/security/PublicDiagnosticPolicy.java:33`, qui l'applique à une racine Windows pour filtrer la sortie publique, hors de l'identité repository ; vérifié en revue.
