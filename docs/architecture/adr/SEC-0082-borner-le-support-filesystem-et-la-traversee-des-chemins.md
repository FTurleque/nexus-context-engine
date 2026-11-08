---
id: SEC-0082
title: "Borner le support aux filesystems locaux qualifiés et distinguer traversée stricte et compatibilité"
domain: security
rules: true
files: ["core/src/main/java/**", "adapters/*/src/main/java/**"]
---

# Borner le support aux filesystems locaux qualifiés et distinguer traversée stricte et compatibilité

- **Statut** : Accepted
- **Date** : 2026-11-08
- **Origine** : consolidation du registre ADR (audit du 2026-11-08)
- **Remplace** : [SEC-0047](archive/SEC-0047-borner-le-support-filesystem-aux-filesystems-locaux-qualifies.md), [SEC-0048](archive/SEC-0048-distinguer-traversee-stricte-et-compatibilite-filesystem.md)
- **Remplacé par** : —

## Context

NEXUS protège ses accès sensibles par `ProjectPathGuard`, `SafeFileIO`, `NOFOLLOW_LINKS` et un `FileLock` par projet pour les mutations d'index. Sur un filesystem local, ces mécanismes réduisent fortement les sorties de racine, les lectures via symlink et les collisions d'indexation. Deux limites demeurent (issue #52) :

- les primitives Java portables ne sont pas un sandbox contre un acteur local qui mute ancêtres, hard-links ou points de montage pendant une opération ;
- la sémantique de `FileLock`, des liens et de la cohérence des métadonnées diffère sur les filesystems réseau (SMB/CIFS, NFS, volumes distribués, couches de virtualisation).

La séquence capture des identités → ouverture → revalidation ne détecte pas une substitution ABA d'un ancêtre restauré avant revalidation ; `NOFOLLOW_LINKS` sur l'ouverture finale ne protège pas les composants intermédiaires. Java 21 n'offre pas `SecureDirectoryStream` sur tous les providers, et une dépendance native pour ce seul cas alourdirait la distribution sans garantie portable sur SMB.

La question est : **quels filesystems NEXUS supporte-t-il par contrat, avec quelles preuves pour étendre ce support, et comment traiter un provider qui n'offre pas de traversée stricte ?**

## Options envisagées

- **Frontière de support** : déclarer supportés tous les filesystems montables (promesse non démontrée) ; **supporter les seuls filesystems locaux qualifiés, SMB/NFS hors support sans preuve spécifique** (retenu) ; détecter le type de filesystem au runtime et bloquer les variantes inconnues (heuristique fragile : `FileStore.type()` ne décrit pas les garanties réelles d'un montage).
- **Traversée** : dépendance native pour une traversée sûre partout (contraintes de distribution, rien de portable sur SMB) ; refuser tout provider sans `SecureDirectoryStream` (Windows local inutilisable) ; **traversée stricte quand elle existe, fallback de compatibilité best-effort par défaut, mode strict exigible** (retenu).

## Decision

### Frontière de support filesystem

NEXUS supporte officiellement les usages sur filesystem local lorsque les qualifications Linux et Windows de la version concernée sont vertes :

- filesystem local du runner Ubuntu de la qualification dédiée ;
- filesystem local NTFS du runner Windows de la qualification dédiée ;
- filesystem local équivalent sur poste utilisateur, sous réserve des garanties standards du système hôte.

Ne sont **pas supportés par contrat**, tant qu'une qualification complète n'a pas démontré la technologie, la configuration et les scénarios nécessaires : SMB/CIFS général (la fixture SMB 3.1.1 loopback Windows est une preuve ciblée, pas une extension de support) ; NFS ; volumes distribués ou synchronisés dont la cohérence de lock et de métadonnées n'est pas démontrée ; montages WSL/DrvFS ou couches de virtualisation sans qualification dédiée ; un acteur local privilégié qui modifie activement ancêtres, hard-links ou points de montage pendant l'opération. Une qualification ciblée ou observationnelle ne suffit pas à élargir ce contrat ; une extension vers SMB ou NFS exige une PR de qualification séparée, dans un environnement représentatif et reproductible.

« Non supporté » signifie que NEXUS ne promet ni exclusion mutuelle inter-processus ni confinement au-delà des garanties du filesystem sous-jacent ; l'exécution n'échoue pas nécessairement.

NEXUS n'ajoute pas de blocage runtime fondé sur `FileStore.type()` : la frontière de support est documentaire et de qualification.

### Traversée stricte et fallback de compatibilité

`SafeFileIO` utilise toujours `SecureDirectoryStream` lorsqu'il est disponible. `NEXUS_REQUIRE_STRICT_PATH_IO=true` (propriété JVM `nexus.requireStrictPathIo`) refuse le fallback **avant l'ouverture du fichier**, donc avant toute lecture ; l'inspection préalable du provider et des répertoires reste nécessaire.

Sans cette exigence, le fallback de compatibilité garde ses contrôles de composants, `NOFOLLOW_LINKS`, la capture des identités et leur revalidation avant lecture. Il est best-effort et ne résiste pas à l'ABA restauré. Windows local reste utilisable par défaut. Il n'existe aucune option de production pour forcer le fallback ; la seam de test est package-private et ne change aucun état global.

### Exposition REST durcie

REST `direct-https`, `reverse-proxy-https` et loopback avec hardening explicite exigent les deux politiques `NEXUS_REQUIRE_PRIVATE_STORAGE=true` et `NEXUS_REQUIRE_STRICT_PATH_IO=true`. Le guard vérifie leur activation ; les primitives imposent leur garantie au moment de l'accès. Un provider incapable de traversée stricte échoue fermé même si les contrôles TLS réussissent.

`loopback-forward` garde son modèle local : Docker, token, allowlist et publication hôte loopback déclarée sont obligatoires ; ce mode ne signifie pas exposition distante et n'impose pas ces deux flags. Une publication Docker distante utilise les modes HTTPS. NEXUS ne peut pas introspecter la publication réelle du daemon : la déclaration reste une responsabilité de l'opérateur.

### Capacités par provider

- OpenJDK Linux : le provider Unix utilise le stream sécurisé lorsque les primitives natives correspondantes existent ; le code teste la capacité réelle et ne déduit aucune garantie du seul nom de l'OS.
- OpenJDK Windows NTFS : `WindowsDirectoryStream` n'implémente pas `SecureDirectoryStream` ; la compatibilité reste disponible, le mode strict refuse.
- UNC/SMB via le provider Windows : même limitation, sans retirer le support UNC local ; la qualification SMB loopback ne prouve ni la résistance ABA ni la confidentialité d'un stockage distant.
- Autres providers : décision par capacité effective, aucun fallback silencieux lorsque le mode strict est exigé.
- Aucun mode n'est un sandbox contre les hard-links, la modification du contenu d'un fichier déjà ouvert, les changements de montage ou un administrateur local privilégié. La racine enregistrée est une frontière de confiance.

### Cohérence de publication

`READY` atteste une génération SQLite et dérivés construite et revalidée contre le dernier scan ; le scan n'est pas un snapshot atomique. Une mutation entre la dernière observation et `save(READY)` reste possible ; un fingerprint persisté identifie la génération sans bloquer un écrivain externe. Aucune architecture de watcher n'est introduite pour prétendre fermer cette fenêtre. Les mutations détectées restent fail-closed (`FAILED`, puis reconstruction) et la matérialisation revalide et borne chaque lecture. Une cohérence instantanée exige un snapshot ou une immuabilité imposée de l'extérieur.

## Do's and Don'ts

### Do

- Ne déclarer supportés que les usages sur filesystem local, lorsque les qualifications Linux et Windows de la version sont vertes.
- Exiger une qualification complète (technologie, configuration, scénarios), dans une PR séparée, avant toute extension du support.
- Tenir la frontière de support par la documentation et la qualification.
- Utiliser `SecureDirectoryStream` dans `SafeFileIO` chaque fois qu'il est disponible.
- Refuser le fallback avant l'ouverture du fichier quand `NEXUS_REQUIRE_STRICT_PATH_IO=true`, sans omettre l'inspection préalable du provider et des répertoires.
- Garder dans le fallback les contrôles de composants, `NOFOLLOW_LINKS`, les identités et la revalidation avant lecture.
- Exiger `NEXUS_REQUIRE_PRIVATE_STORAGE=true` et `NEXUS_REQUIRE_STRICT_PATH_IO=true` pour REST `direct-https`, `reverse-proxy-https` et le loopback durci, et échouer fermé sans traversée stricte.
- Exiger en `loopback-forward` Docker, token, allowlist et publication hôte loopback déclarée.

### Don't

- Ne pas ajouter de blocage runtime fondé sur `FileStore.type()`.
- Ne pas présenter comme supportés SMB/CIFS général, NFS, les volumes distribués ou synchronisés non démontrés, ni les montages WSL/DrvFS ou de virtualisation sans qualification dédiée.
- Ne pas élargir le contrat sur la foi d'une qualification ciblée ou observationnelle, dont la fixture SMB 3.1.1 loopback.
- Ne pas promettre, hors périmètre, d'exclusion mutuelle inter-processus ni de confinement au-delà du filesystem sous-jacent.
- Ne pas offrir d'option de production qui force le fallback ; ne pas rendre publique la seam de test ni lui faire changer un état global.
- Ne pas présenter le fallback de compatibilité comme résistant à l'ABA restauré.
- Ne pas publier Docker à distance en `loopback-forward`.

## Consequences

### Positives

- Contrat de support précis et auditable, sans promesse implicite sur des sémantiques de lock non démontrées.
- Mode strict exigible là où l'exposition le demande ; Windows local reste utilisable par défaut.
- Chemin clair pour qualifier SMB ou NFS plus tard sans modifier rétroactivement la politique.

### Négatives

- Les déploiements sur partage réseau restent hors support.
- Le fallback par défaut ne résiste pas à l'ABA restauré ; Windows NTFS et UNC ne peuvent pas servir une exposition REST durcie.
- Aucune protection absolue contre un administrateur local ou un acteur filesystem hostile ; une extension réseau demandera une infrastructure multi-client avec injection de panne.

### Risques

| Risque | Impact | Mesure de maîtrise |
|---|---|---|
| Fallback forçable en production | Élevé | Seam package-private ; règle `safe-file-io-fallback-not-forceable` |
| Exposition HTTPS sans traversée stricte | Élevé | `requireHardenedStorageAndTraversal()` appelé par le guard REST ; échec fermé |
| Partage réseau pris pour un environnement supporté | Moyen | Matrice de support documentée ; extension par PR de qualification |

## Compliance and Enforcement

- Règle Archgate `SEC-0082-borner-le-support-filesystem-et-la-traversee-des-chemins.rules.ts` (commentaires et littéraux effacés, contrôles propres à Archgate) :
  - `no-runtime-filesystem-type-gate` : aucun source de production (`core/src/main/java`, `adapters/*/src/main/java`) qui référence `FileStore` ou `getFileStore` n'appelle `.type()` ;
  - `safe-file-io-fallback-not-forceable` : `SafeFileIO` référence `SecureDirectoryStream`, la surcharge `openReadNoFollow(…, forceFallback, …)` n'est ni `public` ni `protected`, l'entrée de production lui passe `SecurityPolicy.requireStrictPathIo()` et `false`, et aucun autre source de production ne mentionne `forceFallback` ;
  - `hardened-rest-requires-private-storage-and-strict-traversal` : `SecurityPolicy.requireHardenedStorageAndTraversal()` teste `requirePrivateStorage()` et `requireStrictPathIo()`, et `NexusRestExposureGuard` l'appelle.
- Workflow `.github/workflows/filesystem-semantics-qualification.yml` (Ubuntu et Windows) : `ProjectIndexLockManagerTest`, `SafeFileIOTest` (`forcedFallbackStrictRefusesBeforeOpeningEvenAnOrdinaryFile`, `abaRestorationDemonstratesCompatibilityLimitWithoutReadingOutsideContent`) et `FilesystemSemanticsQualificationTest` : lock exclusif entre deux JVM, libération et réacquisition, refus d'un répertoire ou d'un fichier de lock symbolique, refus d'un symlink final à l'ouverture, confinement lexical et canonique, mutation de chemin entre validation et lecture traitée fail-closed.
- Workflow `.github/workflows/smb-filesystem-qualification.yml` : `SmbFilesystemQualificationTest`, preuve ciblée SMB 3.1.1 loopback.
- Test `adapters/rest-quarkus/src/test/java/com/nexus/api/NexusRestExposureGuardTest.java` (`hardenedExposureRequiresPrivateStorageAndStrictTraversal`, cas `loopback-forward`).
- Contrat `scripts/release/test-final-audit-contracts.sh` (workflow `ci.yml`) : le fallback de `SafeFileIO` capture et revalide les identités (`captureFallbackPathSnapshot`, `revalidateFallbackPathSnapshot`, `attributes.fileKey()`).
- Revue : toute extension du support vers un filesystem réseau arrive par une PR de qualification séparée.

## Conditions de réexamen

La décision est réévaluée si un besoin utilisateur réel exige SMB, NFS ou un filesystem distribué précis ; si une infrastructure reproductible permet de qualifier ces montages avec plusieurs clients et des scénarios de panne ; si Java ou le système hôte fournit des primitives de confinement ou de verrouillage plus fortes et portables ; ou si un incident réel montre un écart entre le comportement local qualifié et la documentation.

## Mise en œuvre

- `core/src/main/java/com/nexus/security/SafeFileIO.java` (traversée stricte, fallback, seam package-private) ; `core/src/main/java/com/nexus/config/SecurityPolicy.java` (`NEXUS_REQUIRE_STRICT_PATH_IO`, `nexus.requireStrictPathIo`, `requireHardenedStorageAndTraversal()`) ; `adapters/rest-quarkus/src/main/java/com/nexus/api/NexusRestExposureGuard.java`.
- `core/src/main/java/com/nexus/index/ProjectIndexLockManager.java` (lock inter-processus par projet).
- Matrice de support, qualification SMB et lecture des résultats CI : [`filesystem-support.md`](../../developer/filesystem-support.md).

## References

- Issue #52 — qualification des sémantiques de filesystem hostile ou réseau.
- [ARCH-0086](ARCH-0086-resoudre-la-configuration-et-nexus-home-dans-la-composition-root.md) — `NEXUS_HOME` et configuration.
- [DATA-0073](DATA-0073-tenir-sqlite-pour-source-de-verite-et-lucene-pour-index-derive.md) — Lucene dérivé et reconstructible.
- [DATA-0074](DATA-0074-garantir-le-cycle-de-vie-et-la-coherence-des-index-derives.md) — cycle de vie des index dérivés.
- [Contrat Java 21 `SecureDirectoryStream`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/SecureDirectoryStream.html) ; providers OpenJDK 21 [Windows](https://github.com/openjdk/jdk21u/blob/master/src/java.base/windows/classes/sun/nio/fs/WindowsFileSystemProvider.java) et [Unix](https://github.com/openjdk/jdk21u/blob/master/src/java.base/unix/classes/sun/nio/fs/UnixFileSystemProvider.java).
