# ADR-0048 — Distinguer traversée stricte et compatibilité filesystem

- Date : 2026-09-09
- Statut : acceptée
- Complète : ADR-0047

## Contexte

La séquence capture des identités → ouverture → revalidation ne détecte pas une
substitution ABA d'un ancêtre restauré avant revalidation. `NOFOLLOW_LINKS` sur
l'ouverture finale ne protège pas à lui seul les composants intermédiaires.
Java 21 ne fournit pas de primitive portable équivalente à `SecureDirectoryStream`
sur tous les providers. Ajouter une dépendance native uniquement pour ce cas
augmenterait les contraintes de distribution sans garantie portable sur SMB.

## Décision

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

## Références primaires

- [Contrat Java 21 SecureDirectoryStream](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/SecureDirectoryStream.html)
- [Provider Windows OpenJDK 21](https://github.com/openjdk/jdk21u/blob/master/src/java.base/windows/classes/sun/nio/fs/WindowsFileSystemProvider.java)
- [Provider Unix OpenJDK 21](https://github.com/openjdk/jdk21u/blob/master/src/java.base/unix/classes/sun/nio/fs/UnixFileSystemProvider.java)
