# Proposition

## Why

Un défaut de `SensitiveContentRedactor`, antérieur aux sprints, a été relevé à la vérification du 12/11. Un marqueur `-----BEGIN ` qui n'ouvre pas une clé privée fait sauter le vrai bloc qui le suit : il suffit d'une ligne `-----BEGIN ` incomplète ou d'un littéral de code `"-----BEGIN "`, comme dans un parseur PEM ou sa fixture. Le corps de la clé part alors en clair dans Lucene, dans les embeddings et dans les sorties CLI/REST/MCP. Le sprint 15 exige « Clé privée masquée derrière un « -----BEGIN » incomplet (test) » (`docs/project/audits/2026-10-17/sprints.md:354`).

Sources : `docs/project/audits/2026-10-17/findings.json` (`AUD-SEC-26`), `docs/project/audits/2026-10-17/etat-2026-11-12.md:74-82`, `:159`.

## What Changes

- [Existant vérifié] `replacePrivateKeyBlocks` (`core/src/main/java/com/nexus/security/SensitiveContentRedactor.java:399-438`, appelée en premier par `redact` en `:160`) cherche `-----BEGIN ` (`:403`), puis la fin du marqueur par `indexOf("-----", begin + 11)` (`:409`), sans limite de ligne. Si le marqueur ne se termine ni par `PRIVATE KEY-----` ni par `PRIVATE KEY BLOCK-----` (`:416`), la boucle reprend à `markerEnd` (`:417-418`). Pour un marqueur incomplet, ces tirets sont ceux du vrai `-----BEGIN RSA PRIVATE KEY-----` suivant : la reprise tombe après son début, et le bloc n'est plus reconnu.
- [Existant vérifié] Sonde du 12/11 (classes de `core/target/classes` compilées depuis l'arbre courant, `secret-redaction-v9`, JDK 24, corps de clé factice) : le bloc RSA seul est masqué. Il reste en clair derrière `if (line.startsWith("-----BEGIN ")) {}`, derrière une ligne `-----BEGIN ` et derrière une ligne `-----BEGIN CERT`. Même résultat pour un bloc `PGP PRIVATE KEY BLOCK` placé derrière une ligne `-----BEGIN `. Aucun autre motif ne couvre un corps PEM.
- [Écart] Cas absent de l'audit, même cause : un marqueur complet d'un autre type, collé au vrai marqueur sur la même ligne (`-----BEGIN CERTIFICATE-----BEGIN RSA PRIVATE KEY-----`), fait aussi sauter le bloc. Ses tirets de fin sont les tirets d'ouverture du marqueur suivant. Sonde du 12/11 : corps en clair. Une ligne `-----BEGIN CERTIFICATE-----` suivie d'un bloc complet sur une autre ligne est en revanche masquée.
- [Écart] L'audit propose deux corrections : reprendre à `begin + PRIVATE_KEY_BEGIN.length()`, « ou chercher la fin de marqueur sur la même ligne ». La seconde ne suffit pas seule : dans le cas collé ci-dessus, la fin de marqueur est sur la même ligne, et la reprise en `markerEnd` saute encore le vrai début. Les deux sont retenues ensemble : la reprise corrige la fuite, la fin de marqueur limitée à sa ligne supprime le masquage en trop décrit plus bas (voir le design, Décisions).
- [Existant vérifié] La sonde du 12/11 applique la reprise à `begin + PRIVATE_KEY_BEGIN.length()` à une copie de la boucle : les sept cas (cinq en clair, deux masqués) sont masqués et gardent leurs séparateurs de lignes. Les quatre cas sans défaut donnent une sortie identique à la boucle actuelle et à `redact` v9 : bloc seul, certificat puis bloc, marqueur incomplet sans bloc, bloc tronqué. 1 Mo de `-----BEGIN \n` répété prend 9 ms, contre 5 ms aujourd'hui ; `redact` complet sur ce contenu prend 278 ms.
- [Écart] Hors audit : la fin du marqueur est cherchée au-delà de sa ligne. Une ligne `-----BEGIN ` suivie, plus loin, d'un littéral `"PRIVATE KEY-----"` est prise pour l'ouverture d'une clé. Sans fin correspondante, tout le reste du contenu devient `[REDACTED]`, ce qui est un masquage en trop, jamais une fuite (sonde du 12/11).
- [Existant vérifié] Un vrai marqueur PEM tient sur sa ligne : `rsaPrivateKeyBlock` (`core/src/test/java/com/nexus/security/SensitiveContentRedactorTest.java:173-177`) et les blocs de `SecretRedactionProbeTest` placent `-----BEGIN <type>-----` seul sur sa ligne, comme le format textuel PEM (RFC 7468).
- [Décision] Décision du mainteneur du 2026-11-12 : ce changement limite aussi la recherche de la fin du marqueur `-----BEGIN ` à sa ligne, c'est-à-dire avant le premier CR ou LF qui suit `-----BEGIN `. Même politique `secret-redaction-v10`, même profil `content-v9`, même migration `V021`. Sonde du 12/11 sur une copie de la boucle, en LF, CRLF et CR : les sept cas de fuite restent masqués, le cas du littéral plus loin est rendu inchangé, et les quatre cas sans défaut gardent la sortie v9.
- [Exigence] Un bloc de clé privée, PGP compris, reste masqué quand un marqueur `-----BEGIN ` qui n'ouvre pas une clé privée le précède. Ce marqueur peut être incomplet, sans tirets de fin sur sa ligne, ou complet et collé au vrai marqueur. Le bloc garde ses séparateurs de lignes.
- [Exigence] Un contenu sans bloc de clé privée reste inchangé par cette étape, même s'il contient un marqueur `-----BEGIN ` incomplet.
- [Exigence] Un marqueur `-----BEGIN ` dont la fin `-----` n'est pas sur sa ligne n'ouvre pas de clé privée : il ne déclenche aucun masquage au-delà de sa ligne, quel que soit le séparateur (LF, CRLF ou CR), et un littéral `PRIVATE KEY-----` situé plus loin reste visible.
- [Exigence] La correction passe `POLICY_VERSION` à `secret-redaction-v10` et le profil sémantique à `content-v9`, et ajoute une migration d'invalidation `V021`, conformément à `secret-redaction › Un changement de politique invalide les index dérivés` et au skill `nexus-redaction-change`.

## Type de changement

fonctionnel (politique de redaction, format des index dérivés) : profil complet.

## Périmètre et exclusions

**Dans le périmètre :**
- Reprise de la recherche de `-----BEGIN ` après un marqueur qui n'ouvre pas une clé privée, dans `replacePrivateKeyBlocks`.
- Recherche de la fin du marqueur `-----BEGIN ` limitée à sa ligne, dans `replacePrivateKeyBlocks`, qui supprime le masquage en trop jusqu'à la fin du contenu.
- Version de politique, profil sémantique, migration `V021`, documentation et contrats associés (skills `nexus-redaction-change` et `nexus-schema-migration`).

**Hors périmètre :**
- Les autres motifs de `SensitiveContentRedactor`, `SecretAssignmentScanner`, et la sonde versionnée de 35 cas (`SecretRedactionProbeTest`), dont le compte est épinglé par la spec.
- Les marqueurs de type inconnu ou en minuscules (`-----begin rsa private key-----`), et un corps PEM sans aucun marqueur.
- Les jetons `sk-`, `glpat-` ou `xox?-` après `-----END CERTIFICATE-----` : limite déjà documentée (`docs/developer/secret-redaction.md:225`).

## Capabilities

### New Capabilities

### Modified Capabilities
- `secret-redaction` : deux exigences ajoutées (bloc de clé privée après un marqueur qui n'ouvre pas une clé ; fin du marqueur cherchée sur sa ligne) ; réécriture de « Un changement de politique invalide les index dérivés » pour `secret-redaction-v10`.

## Impact

- Code : `core/src/main/java/com/nexus/security/SensitiveContentRedactor.java` (`replacePrivateKeyBlocks`, `:409-419` ; `POLICY_VERSION`, `:21`) ; `core/src/main/java/com/nexus/search/semantic/SemanticIndexingService.java:27` (`CONTENT_PROFILE_VERSION` de 8 à 9) ; `core/src/main/java/com/nexus/persistence/sqlite/SchemaMigrator.java:40` (enregistrement de `V021`).
- Schéma : `core/src/main/resources/db/migration/V021__invalidate_private_key_marker_indexes.sql`, sur le modèle de `V020`.
- Tests : `SensitiveContentRedactorTest`, nouveau `SchemaMigratorPrivateKeyMarkerUpgradeTest`, `SchemaMigratorChecksumTest`, tests d'upgrade qui comptent les incréments de génération, et tests qui citent `secret-redaction-v9` ou `content-v8` (`SemanticIndexingServiceTest:36`).
- Documentation : `docs/developer/secret-redaction.md`, `docs/developer/openspec-traceability.md`, `docs/developer/release-and-recovery.md`, et `content-v9` dans `README.md`, `docs/architecture/overview.md`, `docs/architecture/arc42/08-concepts-transverses.md`, `docs/architecture/arc42/10-exigences-qualite.md`, `docs/developer/current-limitations.md`, `docs/developer/semantic-search.md`. Contrat : `scripts/release/test-operational-doc-contracts.sh` (`:90` dernière migration, `:241` et `:251` profil, `:253-255` politique, `:267` recovery).
- ADR applicables, sans nouvel ADR : `DATA-0020` (migrations embarquées, append-only), `DATA-0073` (Lucene dérivé et reconstructible), `DATA-0074` (version de redaction dans le commit Lucene et le profil sémantique), `ARCH-0085` (une correction de redaction vaut pour CLI, REST et MCP).
- Effet utilisateur : rebuild des index dérivés de tous les projets à la mise à niveau.

## Questions ouvertes

Aucune.
