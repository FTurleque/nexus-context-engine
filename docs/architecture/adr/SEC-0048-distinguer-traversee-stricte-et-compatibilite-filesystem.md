---
id: SEC-0048
title: "Distinguer traversée stricte et compatibilité filesystem"
domain: security
rules: true
files: ["core/src/main/java/**", "adapters/*/src/main/java/**"]
---

# Distinguer traversée stricte et compatibilité filesystem

- **Statut** : Accepted
- **Date** : 2026-09-09
- **Complète** : ADR-0047

## Context

La séquence capture des identités → ouverture → revalidation ne détecte pas une
substitution ABA d'un ancêtre restauré avant revalidation. `NOFOLLOW_LINKS` sur
l'ouverture finale ne protège pas à lui seul les composants intermédiaires.
Java 21 ne fournit pas de primitive portable équivalente à `SecureDirectoryStream`
sur tous les providers. Ajouter une dépendance native uniquement pour ce cas
augmenterait les contraintes de distribution sans garantie portable sur SMB.

## Decision

`SafeFileIO` utilise toujours `SecureDirectoryStream` lorsqu'il est disponible.
`NEXUS_REQUIRE_STRICT_PATH_IO=true` (propriété JVM `nexus.requireStrictPathIo`)
refuse le fallback **avant l'ouverture du fichier**, donc avant toute lecture.
L'inspection préalable du provider et des répertoires reste nécessaire.

Sans cette exigence, le fallback local conserve ses contrôles de composants,
`NOFOLLOW_LINKS`, identités et revalidation avant lecture. Il est best-effort et
ne résiste pas à l'ABA restauré. Windows local reste utilisable par défaut.
Il n'existe aucune option de production pour forcer le fallback ; la seam de
test est package-private et ne change aucun état global.

REST `direct-https`, `reverse-proxy-https` et loopback avec hardening explicite
exigent **les deux** politiques `NEXUS_REQUIRE_PRIVATE_STORAGE=true` et
`NEXUS_REQUIRE_STRICT_PATH_IO=true`. Le guard vérifie leur activation ; les
primitives imposent leur garantie au moment de l'accès. Un provider incapable
de traversée stricte échoue fermé même si les contrôles TLS réussissent.

`loopback-forward` conserve son modèle local : Docker, token, allowlist et
publication hôte loopback déclarée sont obligatoires. Ce mode ne signifie pas
exposition distante et n'impose pas ces deux flags. Une publication Docker
distante doit utiliser les modes HTTPS. NEXUS ne peut pas introspecter la
publication réelle du daemon : la déclaration reste une responsabilité opérateur.

## Do's and Don'ts

### Do

- Utiliser `SecureDirectoryStream` dans `SafeFileIO` chaque fois qu'il est disponible.
- Refuser le fallback avant l'ouverture du fichier lorsque `NEXUS_REQUIRE_STRICT_PATH_IO=true` (propriété JVM `nexus.requireStrictPathIo`), sans omettre l'inspection préalable du provider et des répertoires.
- Garder dans le fallback de compatibilité les contrôles de composants, `NOFOLLOW_LINKS`, les identités et la revalidation avant lecture.
- Exiger `NEXUS_REQUIRE_PRIVATE_STORAGE=true` et `NEXUS_REQUIRE_STRICT_PATH_IO=true` pour REST `direct-https`, `reverse-proxy-https` et le loopback avec hardening explicite, et échouer fermé si le provider ne permet pas la traversée stricte.
- Exiger en `loopback-forward` Docker, token, allowlist et publication hôte loopback déclarée.

### Don't

- Ne pas offrir d'option de production qui force le fallback ; ne pas rendre publique la seam de test ni lui faire changer un état global.
- Ne pas présenter le fallback de compatibilité comme résistant à l'ABA restauré.
- Ne pas publier Docker à distance en `loopback-forward` : une publication distante utilise les modes HTTPS.

## Capacités et limites

- OpenJDK Linux : le provider Unix utilise le stream sécurisé lorsque les
  primitives natives correspondantes sont disponibles ; le code teste la
  capacité réelle et ne déduit pas une garantie du seul nom de l'OS.
- OpenJDK Windows NTFS : `WindowsDirectoryStream` n'implémente pas
  `SecureDirectoryStream`. Compatibility reste disponible ; strict refuse.
- UNC/SMB via le provider Windows : même limitation de primitive Java, sans
  supprimer le support UNC local existant. La qualification SMB loopback ne
  prouve ni la résistance ABA ni la confidentialité d'un stockage distant.
- Autres providers : décision par capacité effective, aucun fallback silencieux
  lorsque strict est exigé.
- Aucun mode ne constitue une sandbox contre les hard-links, modifications du
  contenu d'un fichier déjà ouvert, changements de mounts ou administrateurs
  locaux privilégiés. La racine enregistrée est une frontière de confiance.

## Cohérence de publication

`READY` atteste une génération SQLite/dérivés construite et revalidée contre le
dernier scan. Le scan lui-même n'est pas un snapshot atomique. Une mutation entre
la dernière observation et `save(READY)` reste possible. Un fingerprint persisté
identifie la génération mais ne bloque pas un écrivain externe ; un watcher peut
perdre des événements et ne rend pas publication/écriture atomiques. Un scan de
plus ne fait que déplacer la fenêtre. Aucune architecture de watcher n'est
introduite pour prétendre fermer cette fenêtre. Les mutations détectées restent
fail-closed (`FAILED`, puis reconstruction), et la matérialisation revalide et
borne chaque lecture. Une cohérence instantanée exige un snapshot ou une
immuabilité imposée extérieurement.

## Compliance and Enforcement

- Règle Archgate `SEC-0048-distinguer-traversee-stricte-et-compatibilite-filesystem.rules.ts` / `safe-file-io-fallback-not-forceable` : `SafeFileIO` référence `SecureDirectoryStream`, la surcharge `openReadNoFollow(…, forceFallback, …)` n'est ni `public` ni `protected`, l'entrée de production lui passe `SecurityPolicy.requireStrictPathIo()` et `false`, et aucun autre source de production ne mentionne `forceFallback` ; commentaires et littéraux effacés. Contrôle propre à Archgate.
- Règle Archgate `SEC-0048-distinguer-traversee-stricte-et-compatibilite-filesystem.rules.ts` / `hardened-rest-requires-private-storage-and-strict-traversal` : `SecurityPolicy.requireHardenedStorageAndTraversal()` teste `NexusPaths.requirePrivateStorage()` et `requireStrictPathIo()`, et `NexusRestExposureGuard` l'appelle.
- Test `core/src/test/java/com/nexus/security/SafeFileIOTest.java` (`forcedFallbackStrictRefusesBeforeOpeningEvenAnOrdinaryFile`, `abaRestorationDemonstratesCompatibilityLimitWithoutReadingOutsideContent`…) ; workflow `.github/workflows/filesystem-semantics-qualification.yml`.
- Test `adapters/rest-quarkus/src/test/java/com/nexus/api/NexusRestExposureGuardTest.java` (`hardenedExposureRequiresPrivateStorageAndStrictTraversal`, cas `loopback-forward`).
- Contrat `scripts/release/test-final-audit-contracts.sh` (workflow `ci.yml`) : le fallback de `SafeFileIO` capture et revalide les identités (`captureFallbackPathSnapshot`, `revalidateFallbackPathSnapshot`, `attributes.fileKey()`).

## Références primaires

- [Contrat Java 21 SecureDirectoryStream](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/SecureDirectoryStream.html)
- [Provider Windows OpenJDK 21](https://github.com/openjdk/jdk21u/blob/master/src/java.base/windows/classes/sun/nio/fs/WindowsFileSystemProvider.java)
- [Provider Unix OpenJDK 21](https://github.com/openjdk/jdk21u/blob/master/src/java.base/unix/classes/sun/nio/fs/UnixFileSystemProvider.java)
