# Proposition

## Why

La vérification du 15/11 relève que la reconnaissance des blocs de clé de `secret-redaction-v10` laisse en clair deux formes que `secret-redaction-v9` masquait (`AUD-SEC-27`, Faible, sprint 16). Le corps de la seconde clé privée d'un fichier commenté ou concaténé, quand son `-----BEGIN` suit sur la même ligne le `-----END` de la première, part en clair dans Lucene, dans les embeddings et dans les sorties CLI/REST/MCP. Le sprint 16 exige « `END … BEGIN` sur une ligne et marqueur `BEGIN` coupé : masqués ou explicitement refusés, avec un test » (`docs/project/audits/2026-10-17/sprints.md`, section S16).

Sources : `docs/project/audits/2026-10-17/etat-2026-11-15.md` (`AUD-SEC-27`), `docs/project/audits/2026-10-17/findings.json`.

## What Changes

- [Existant vérifié] `endOfMatchingEndMarker` (`core/src/main/java/com/nexus/security/SensitiveContentRedactor.java:515-529`, base `58399279`) ferme le bloc à `endOfLine(content, markerEnd)` (`:525`) quand le `-----END` ne commence pas sa ligne. Le curseur passe au-delà d'un `-----BEGIN EC PRIVATE KEY-----` placé sur la même ligne, et la recherche reprend après : le corps du second bloc reste en clair. Ce défaut est introduit par la fermeture « à la fin de la ligne » du sprint 15 (tâche 7.2 du change `sprint-15-redaction-begin-incomplet`).
- [Existant vérifié] Sonde du 15/11 rejouée sur `core/target/classes` à `58399279` (`secret-redaction-v10`, JDK 24, corps factice) : corps du second bloc en clair pour `# …END RSA…-----` + `-----BEGIN EC…-----` (dix tirets), pour `x…END…` (préfixe `x`), pour `// …` (deux blocs RSA), pour `# …` en CRLF. Même corps masqué quand le `-----END` commence sa ligne avec dix tirets.
- [Écart] Hors audit, même cause : les cinq tirets de fin du `-----END` peuvent être les tirets d'ouverture du `-----BEGIN` suivant (`-----END X-----BEGIN Y-----`, la forme du marqueur collé déjà reconnue pour `-----BEGIN CERTIFICATE-----BEGIN RSA PRIVATE KEY-----`). Un `-----END` qui commence sa ligne ferme alors le bloc après ses tirets, et le `BEGIN` qui reste n'a plus de tirets : le second bloc reste en clair, y compris sous `secret-redaction-v9`. Sonde du 15/11 : en clair sous v10 pour la forme au milieu de ligne et pour la forme en début de ligne.
- [Existant vérifié] Oracle aléatoire du 15/11 (200 000 contenus de un à trois blocs RSA, EC, `PRIVATE KEY` et PGP ; préfixes de commentaire, LF, CRLF et CR, jonctions vides, avec espace, avec texte collé, avec marqueur `CERTIFICATE`, avec tirets partagés) : v10 laisse 92 267 corps en clair (un contenu peut en laisser plusieurs), le code corrigé aucun (rejeu à la tâche 8.1).
- [Décision] Fermer le bloc au premier des deux : la fin de la ligne du `-----END`, ou le prochain `-----BEGIN ` qui ouvre une clé privée sur cette ligne, recherché à partir des tirets de fin du `END` pour compter les tirets partagés. Le texte entre le `END` et ce `BEGIN` reste dans le bloc masqué. Un `BEGIN` d'un autre type (certificat) sur la ligne n'arrête pas le bloc, comme sous v10 : tout le reste de la ligne est masqué. C'est la correction proposée par l'audit (`min(fin de ligne, prochain BEGIN)`), étendue aux tirets partagés et au `END` qui commence sa ligne.
- [Décision] Ne pas masquer un marqueur `-----BEGIN` coupé sur deux lignes (concaténation Java, chaîne Python implicite, CRLF) : il reste une limite explicitement refusée. L'accepter reviendrait à abroger la règle « la fin d'un marqueur `BEGIN` est cherchée sur sa ligne » décidée par le mainteneur le 2026-11-12, dont le scénario `-----BEGIN ` suivi d'une ligne `"PRIVATE KEY-----"` doit rester visible : à ce stade, la coupure d'un littéral de parseur PEM et celle d'un marqueur de clé réelle sont indiscernables par une règle bornée. Le comportement est figé par un test pour les trois variantes de l'audit, et la limite est documentée comme fuite possible (`secret-redaction.md`).
- [Écart] L'audit présente le marqueur coupé comme une régression face à v9. C'est exact (v9 le masquait) et connu : la limite figure dans le design du sprint 15 (« Limite acceptée ») et dans `SensitiveContentRedactorTest#markerSplitAcrossTwoSourceLinesOpensNoKey` ; le test différentiel exclut ces cas comme `defective`. Le présent change ne la rouvre pas et l'étend aux variantes CRLF et chaîne implicite.
- [Exigence] Un bloc de clé privée qui suit un `-----END` sur la même ligne est masqué : après un `END` au milieu d'une ligne, après un `END` en début de ligne suivi du `BEGIN` sur cette ligne, que les tirets de fin soient ou non les tirets d'ouverture du `BEGIN`, en LF, CRLF et CR. Le bloc garde ses séparateurs de lignes.
- [Exigence] La correction passe `POLICY_VERSION` à `secret-redaction-v11` et le profil sémantique à `content-v10`, et ajoute une migration d'invalidation `V022`, conformément à `secret-redaction › Un changement de politique invalide les index dérivés` et au skill `nexus-redaction-change`.

## Type de changement

fonctionnel (politique de redaction, format des index dérivés) : profil complet.

## Périmètre et exclusions

**Dans le périmètre :**
- Fermeture d'un bloc de clé privée à son `-----END` dans `endOfMatchingEndMarker` : arrêt avant un `-----BEGIN ` de clé privée placé sur la ligne du `END`, tirets partagés compris.
- Test figeant le marqueur `BEGIN` coupé sur deux lignes (Java, Python, CRLF) comme limite explicite.
- Version de politique, profil sémantique, migration `V022`, documentation et contrats associés (skills `nexus-redaction-change` et `nexus-schema-migration`).

**Hors périmètre :**
- Reconnaître un marqueur `BEGIN` coupé sur deux lignes, un marqueur d'un autre type que ceux déjà reconnus, ou un corps PEM sans marqueur.
- Les autres motifs de `SensitiveContentRedactor`, `SecretAssignmentScanner`, et la sonde versionnée de 35 cas (`SecretRedactionProbeTest`).
- `AUD-QUA-33` (installateur), traité par un change à part.

## Capabilities

### New Capabilities

### Modified Capabilities
- `secret-redaction` : une exigence ajoutée (un `-----END` n'avale pas le `-----BEGIN` qui le suit sur sa ligne) ; scénario ajouté à « La fin d'un marqueur BEGIN est cherchée sur sa ligne » pour le marqueur coupé ; réécriture de « Un changement de politique invalide les index dérivés » pour `secret-redaction-v11`.

## Impact

- Code : `core/src/main/java/com/nexus/security/SensitiveContentRedactor.java` (`endOfMatchingEndMarker`, nouvelle `privateKeyBeginBefore`, `POLICY_VERSION`) ; `core/src/main/java/com/nexus/search/semantic/SemanticIndexingService.java:27` (`CONTENT_PROFILE_VERSION` de 9 à 10) ; `core/src/main/java/com/nexus/persistence/sqlite/SchemaMigrator.java:41` (enregistrement de `V022`).
- Schéma : `core/src/main/resources/db/migration/V022__invalidate_end_begin_same_line_indexes.sql`, sur le modèle de `V021`.
- Tests : `SensitiveContentRedactorTest`, nouveau `SchemaMigratorEndBeginSameLineUpgradeTest`, `SchemaMigratorChecksumTest`, tests d'upgrade qui comptent les migrations ou citent `secret-redaction-v10` ou `content-v9` (`SemanticIndexingServiceTest`).
- Documentation : `docs/developer/secret-redaction.md`, `docs/developer/openspec-traceability.md`, `docs/developer/release-and-recovery.md`, et `content-v10` dans `README.md`, `docs/architecture/overview.md`, `docs/architecture/arc42/08-concepts-transverses.md`, `docs/architecture/arc42/10-exigences-qualite.md`, `docs/developer/current-limitations.md`, `docs/developer/semantic-search.md`. Contrat : `scripts/release/test-operational-doc-contracts.sh`.
- ADR applicables, sans nouvel ADR : `DATA-0020` (migrations embarquées, append-only), `DATA-0073` (Lucene dérivé et reconstructible), `DATA-0074` (version de redaction dans le commit Lucene et le profil sémantique), `ARCH-0085` (une correction de redaction vaut pour CLI, REST et MCP).
- Effet utilisateur : rebuild des index dérivés de tous les projets à la mise à niveau.

## Questions ouvertes

Aucune. La décision de refuser le marqueur coupé est celle du design du sprint 15, reconduite ; elle se renverse par un nouveau change si le mainteneur préfère masquer ces formes au prix du masquage en trop décrit dans `secret-redaction › La fin d'un marqueur BEGIN est cherchée sur sa ligne`.
