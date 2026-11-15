# Design

**Profil :** complet

## Contexte

- [Existant vérifié] `redact` remplace d'abord les blocs de clé privée par `replacePrivateKeyBlocks`, puis refuse ou masque les autres formats sur ce même texte (`core/src/main/java/com/nexus/security/SensitiveContentRedactor.java:160-186`, appel en `:164`).
- [Existant vérifié] À `58399279`, `replacePrivateKeyBlocks` (`:421-453`) cherche un `-----BEGIN `, lit son type sur sa ligne (`closingDashesOnMarkerLine` `:609-620`), puis cherche la fin avec `endOfMatchingEndMarker` (`:515-529`). Celle-ci prend le premier `-----END <type>-----` du même type et rend `markerEnd` s'il commence sa ligne, `endOfLine(content, markerEnd)` sinon (`:525`). Le curseur reprend à cette position : un `-----BEGIN ` situé entre `markerEnd` et la fin de la ligne n'est jamais examiné.
- [Existant vérifié] Sonde du 15/11 (`secret-redaction-v10`, JDK 24, corps factice) : `# -----END RSA PRIVATE KEY----------BEGIN EC PRIVATE KEY-----` sur une ligne, avec `# ` ou `x` en préfixe, en LF et CRLF, laisse le corps du bloc EC en clair. Le même contenu avec un `END` en début de ligne et dix tirets est masqué ; avec les cinq tirets de fin partagés (`-----END X-----BEGIN Y-----`), il est en clair, que le `END` commence sa ligne ou non, car le `END` consomme les tirets d'ouverture du `BEGIN`.
- [Existant vérifié] Un marqueur dont les tirets de fin sont les tirets d'ouverture du suivant est déjà reconnu côté `BEGIN` : la reprise à `begin + 11` retrouve `-----BEGIN CERTIFICATE-----BEGIN RSA PRIVATE KEY-----` (`sprint-15-redaction-begin-incomplet`, design, Décisions).
- [Existant vérifié] Un marqueur `BEGIN` coupé sur deux lignes n'ouvre pas de clé (`SensitiveContentRedactorTest#markerSplitAcrossTwoSourceLinesOpensNoKey`) : limite acceptée du sprint 15 (design, « Risques / Compromis »).
- [Existant vérifié] Dernière migration `V021__invalidate_private_key_marker_indexes.sql`, enregistrée à `core/src/main/java/com/nexus/persistence/sqlite/SchemaMigrator.java:41` ; `POLICY_VERSION = "secret-redaction-v10"` (`:21`) ; `CONTENT_PROFILE_VERSION = 9` (`core/src/main/java/com/nexus/search/semantic/SemanticIndexingService.java:27`). Le premier numéro libre est `V022`.
- Page durable : `docs/developer/secret-redaction.md`, section « OpenSpec secret-redaction ».

## Objectifs / Hors objectifs

**Objectifs :**
- `secret-redaction › Un marqueur END n'avale pas le marqueur BEGIN qui le suit sur sa ligne`.
- `secret-redaction › La fin d'un marqueur BEGIN est cherchée sur sa ligne` (scénario « Marqueur coupé sur deux lignes » : refus explicite, figé par un test).
- `secret-redaction › Un changement de politique invalide les index dérivés` (`secret-redaction-v11`).

**Hors objectifs :** masquer un marqueur `BEGIN` coupé sur deux lignes ; reconnaître d'autres formes de marqueur ; toucher les autres motifs, `SecretAssignmentScanner` ou la sonde de 35 cas.

## Composants et responsabilités

Non applicable : `SensitiveContentRedactor` garde ses responsabilités et son API (`redact`, `redactField`). `endOfMatchingEndMarker` change de position de sortie, deux méthodes privées s'ajoutent (`endOfLineOrPrivateKeyBegin`, `opensPrivateKeyAt`) ; aucun appelant n'est modifié.

## Modèle de domaine et invariants

- Invariant de fin de bloc : le premier `-----END <type>-----` du type du bloc le ferme. S'il commence sa ligne, le bloc s'arrête après ses tirets ; sinon à la fin de sa ligne, avant son CR ou son LF (sprint 15). Dans les deux cas, un `-----BEGIN ` qui ouvre une clé privée placé après le `END` sur sa ligne arrête le bloc avant lui, et le tour suivant de la boucle le traite comme n'importe quel début de bloc.
- Invariant de tirets partagés : les tirets qui suivent le type du `END` forment une seule suite. Si ses cinq derniers tirets ouvrent un `BEGIN` de clé privée (`opensPrivateKeyAt(content, dashRunEnd - 5)`), le bloc s'arrête avant ces cinq tirets. Cela couvre cinq tirets partagés (`-----END X-----BEGIN Y-----`) comme six à neuf tirets entre `X` et `BEGIN`, où les tirets d'ouverture du `BEGIN` commencent dans la suite de fin du `END`. Sinon ces tirets n'auraient plus de `-----BEGIN ` à reprendre. Un `-----BEGIN ` qui chevauche la suite commence forcément cinq tirets avant sa fin.
- Invariant de texte intermédiaire : le texte entre le `END` et le `BEGIN` trouvé fait partie du bloc masqué, comme tout ce qui suit un `END` hors début de ligne jusqu'à la fin de sa ligne sous v10.
- Invariant de marqueur d'un autre type : un `-----BEGIN ` dont le type n'est pas une clé privée n'arrête pas le bloc. Tout le reste de la ligne est masqué, comme sous v10.
- Invariant de non-régression : sur tout contenu, ce qui est visible sous v11 l'est sous v10 (v11 masque au moins autant). Le rendu change seulement quand un `BEGIN` de clé privée suit un `END` sur sa ligne.
- Invariant de lignes : le remplacement conserve chaque CR et LF (`.claude/rules/search-and-redaction.md`), donc les ranges persistés ne bougent pas.
- Invariant de coût : la recherche du `BEGIN` part de la fin du `END` et s'arrête au premier `BEGIN` de clé privée ou au premier CR ou LF, dans un seul balayage. Elle ne calcule pas la fin de ligne d'abord : sur une longue ligne de blocs successifs, cela coûterait la longueur de la ligne à chaque bloc. Chaque candidat `BEGIN` est examiné jusqu'aux premiers tirets qui le suivent sur sa ligne, zone disjointe de celle du candidat suivant. Le travail reste linéaire.

## Traitements et interactions

Vue organigramme (cible) : où s'arrête un bloc fermé par un `-----END` ? Couvre `secret-redaction › Un marqueur END n'avale pas le marqueur BEGIN qui le suit sur sa ligne` et, par son dernier nœud, `secret-redaction › Un marqueur END hors début de ligne ferme le bloc à la fin de sa ligne`.

```mermaid
flowchart TD
    A["END du type du bloc trouvé : typeEnd = fin de son type, markerEnd = typeEnd + 5"] --> S{"un BEGIN de clé privée commence à typeEnd (tirets partagés) ?"}
    S -- oui --> P["fin du bloc = typeEnd ; le tour suivant traite ce BEGIN"]
    S -- non --> L{"le END commence sa ligne ?"}
    L -- oui --> Q["fin du bloc = markerEnd"]
    L -- non --> B["balayer depuis markerEnd jusqu'au premier CR, LF ou BEGIN de clé privée"]
    B --> R{"BEGIN de clé privée avant CR ou LF ?"}
    R -- oui --> T["fin du bloc = position de ce BEGIN"]
    R -- non --> U["fin du bloc = fin de la ligne, CR ou LF exclu"]
```

Pour `# -----END RSA PRIVATE KEY----------BEGIN EC PRIVATE KEY-----`, le `END` ne commence pas sa ligne : le balayage trouve le `BEGIN` à `markerEnd`, le bloc RSA s'arrête là, et le bloc EC est masqué comme un bloc ordinaire. Pour `-----END RSA PRIVATE KEY-----BEGIN EC PRIVATE KEY-----`, les tirets de fin du `END` sont ceux du `BEGIN` : le premier test s'applique et le bloc s'arrête avant eux. Pour `-----BEGIN CERTIFICATE-----` placé après le `END`, le balayage l'ignore (type sans `PRIVATE KEY`) et rend la fin de la ligne.

## États et transitions

Non applicable : traitement pur, sans état persistant hors version de politique.

## Contrats d'interface

Non applicable : aucune API ne change. La projection publique applique la même redaction aux trois canaux (`ARCH-0085`).

## Persistance et intégrité

- [Décision] Ajouter la migration `core/src/main/resources/db/migration/V022__invalidate_end_begin_same_line_indexes.sql`, copie du corps de `V021` avec un commentaire SQL en français : `INSERT OR IGNORE` de `project_index_generations`, génération incrémentée, projets `NOT_INDEXED`, `last_indexed_at` à `NULL`, faits canoniques conservés. Enregistrée dans `SchemaMigrator` après `V021` (`:41`), conformément à `DATA-0020` (migrations append-only protégées par checksum).
- `POLICY_VERSION` passe à `secret-redaction-v11` et `CONTENT_PROFILE_VERSION` à 10 (`content-v10`). Lucene compare la version de politique de son commit, et le profil sémantique inclut la politique (`DATA-0074`).

## Transactions, concurrence et idempotence

La migration est un lot SQL unique appliqué par le migrateur ; rejouée, elle est ignorée grâce à l'historique des migrations. La redaction reste déterministe et idempotente.

## Sécurité et isolation

- Les index d'une politique antérieure peuvent contenir en clair le corps de la seconde clé privée d'un fichier commenté, concaténé ou sur une ligne JSON. Ils sont invalidés puis reconstruits (`DATA-0073`).
- Les tests n'utilisent que des corps factices assemblés à l'exécution (`MIIfakebodyNOTAKEY0123`, `MIIsecondbodyNOTAKEY4567`, `GENBODYn`). Aucun vrai matériau de clé n'entre dans le dépôt.

## Erreurs et reprise

Non applicable : aucune erreur nouvelle ; le remplacement des blocs ne lève pas `OversizedSecretException`.

## Observabilité

Non applicable : la redaction ne produit pas de diagnostic propre ; les diagnostics existants ne changent pas.

## Performance et mesure

- Aucun seuil nouveau : ni l'audit ni le critère de sortie du sprint 16 n'en fixent. Le garde-fou `#endThenBeginRepetitionsHaveBoundedCost` redige environ 1 Mo de chacun des contenus suivants sous 10 s, seuil des garde-fous voisins : cycles `BEGIN … x END` collés sur une seule ligne, mêmes cycles à raison d'un par ligne, un `END` suivi de `-----BEGIN CERTIFICATE ` répété, un `END` suivi de 1 Mo de tirets, un `END` en début de ligne suivi de `-----x ` répété.
- Mesure du 15/11 (JDK 24, meilleur de 3, indicatif) : 45 ms, 54 ms, 7 ms, 9 ms et 424 ms pour ces cinq contenus. Une première version qui calculait la fin de ligne avant de chercher le `BEGIN` prenait 21,5 s sur le premier (quadratique) ; le garde-fou l'échoue au seuil de 10 s.

## Compatibilité, migration et retour arrière

- Mise à niveau : rebuild normal des dérivés de chaque projet au premier passage. Section « Mise à niveau V022 — END puis BEGIN sur une ligne » de `docs/developer/release-and-recovery.md`, sur le modèle de V021.
- Retour arrière : un binaire antérieur refuse une base à version de schéma supérieure ; restaurer la sauvegarde de `NEXUS_HOME`.

## Décisions

- [Décision] Fermer le bloc au premier de la fin de ligne et du prochain `BEGIN` de clé privée, comme l'audit le propose, avec trois précisions : la recherche part de `typeEnd` pour compter les tirets partagés ; elle s'applique aussi au `END` qui commence sa ligne (cas des tirets partagés) ; un `BEGIN` d'un autre type est ignoré, pour ne pas montrer en clair le texte qui le suit sur la ligne (v10 le masquait).
- [Décision] Ne pas mettre en œuvre la seconde partie de l'action de l'audit par un masquage : l'audit ne propose de correction que pour la première forme et le critère de sortie du sprint accepte « explicitement refusé, avec un test ». Un marqueur coupé n'a pas de fin sur sa ligne ; l'accepter ferait masquer toute la suite d'un `-----BEGIN ` suivi d'une ligne `"PRIVATE KEY-----"`, ce que `secret-redaction › La fin d'un marqueur BEGIN est cherchée sur sa ligne` interdit depuis la décision du mainteneur du 2026-11-12. Les trois variantes de l'audit (Java, Python implicite, CRLF) sont figées par `#splitMarkerVariantsOpenNoKey`.
- [Décision] Un seul balayage qui s'arrête au premier `BEGIN` de clé privée ou au premier CR ou LF, au lieu de calculer d'abord la fin de ligne (mesure ci-dessus).
- [Décision] Pas d'expression régulière : la boucle manuelle reste linéaire, et sa sortie est comparée à la boucle v9 (corpus généré existant) et, pour ce change, à v10 (sonde de non-régression de la tâche 5.1).
- Aucun nouvel ADR : la politique de redaction et son invalidation suivent un processus établi (skill `nexus-redaction-change`, `DATA-0020`, `DATA-0073`, `DATA-0074`).

## Risques / Compromis

- [Risque] Un contenu aujourd'hui correct changerait de rendu. → Test différentiel contre la boucle v9 (déjà en place) ; sonde contre v10 sur 150 000 contenus (jonctions, préfixes, types, séparateurs variés) : 41 265 rendus diffèrent, aucun ne montre en clair un fragment que v10 masque. Fuzz différentiel de l'audit de sécurité (200 000 contenus de deux ou trois blocs, cinq à onze tirets entre blocs, avec et sans tirets partagés) : 0 corps en clair sous v11, 0 corps masqué sous v10 et visible sous v11 (tâche 5.3).
- [Limite acceptée] Un marqueur `BEGIN` coupé sur deux lignes (constante Java, chaîne Python implicite, coupure CRLF) laisse son corps en clair. C'est une fuite possible, présente depuis `secret-redaction-v10` et documentée dans `docs/developer/secret-redaction.md`. Elle se renverse par un nouveau change si le mainteneur préfère le masquage en trop.
- [Risque] Rebuild de tous les projets à la mise à niveau. → Inévitable pour purger les corps de clé en clair ; documenté.

## Stratégie de vérification

- `./mvnw -B -ntp -pl core test -Dtest=SensitiveContentRedactorTest,SecretRedactionProbeTest -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`. Les nouveaux tests de bloc après un `END` échouent sur v10 (12 des 15 cas paramétrés, le test généré), et passent après la correction.
- `./mvnw -B -ntp -pl core test -Dtest=SchemaMigrator*Test,SemanticIndexingServiceTest -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`.
- `bash .claude/scripts/contracts.sh` (`content-v10`, `secret-redaction-v11`, `V022`), `./mvnw -B -ntp clean install`, `pwsh -NoProfile -File scripts/self-smoke.ps1`, `openspec validate sprint-16-redaction-end-puis-begin --strict`.

## Traçabilité

| Exigence (`capacité › titre`) | Scénario | Conception ou diagramme | Tâche | Test ou preuve |
|---|---|---|---|---|
| `secret-redaction › Un marqueur END n'avale pas le marqueur BEGIN qui le suit sur sa ligne` | END au milieu d'une ligne puis BEGIN | Organigramme de fin de bloc | 1.1, 1.2 | core/src/test/java/com/nexus/security/SensitiveContentRedactorTest.java#privateKeyBlockAfterAnEndMarkerOnTheSameLineIsRedacted (END commenté, END précédé d'un x) |
| `secret-redaction › Un marqueur END n'avale pas le marqueur BEGIN qui le suit sur sa ligne` | END en début de ligne puis BEGIN sur la même ligne | Organigramme de fin de bloc | 1.1, 1.2 | core/src/test/java/com/nexus/security/SensitiveContentRedactorTest.java#privateKeyBlockAfterAnEndMarkerOnTheSameLineIsRedacted (END en début de ligne, dix tirets) |
| `secret-redaction › Un marqueur END n'avale pas le marqueur BEGIN qui le suit sur sa ligne` | Tirets de fin partagés avec l'ouverture | Organigramme de fin de bloc, Décisions | 1.1, 1.2, 5.1 | core/src/test/java/com/nexus/security/SensitiveContentRedactorTest.java#privateKeyBlockAfterAnEndMarkerOnTheSameLineIsRedacted (tirets partagés) |
| `secret-redaction › Un marqueur END n'avale pas le marqueur BEGIN qui le suit sur sa ligne` | Six à neuf tirets entre le END et le BEGIN | Modèle de domaine et invariants (tirets partagés) | 5.3 | core/src/test/java/com/nexus/security/SensitiveContentRedactorTest.java#privateKeyBlockAfterAnEndMarkerOnTheSameLineIsRedacted (six à neuf tirets, END en début et au milieu de ligne) et #generatedBlocksFollowedByAnotherBlockOnTheSameLineNeverLeakABody |
| `secret-redaction › Un marqueur END n'avale pas le marqueur BEGIN qui le suit sur sa ligne` | Deux clés sur une ligne JSON | Modèle de domaine et invariants | 1.1, 1.2 | core/src/test/java/com/nexus/security/SensitiveContentRedactorTest.java#twoKeysOnOneJsonLineAreBothRedacted |
| `secret-redaction › Un marqueur END n'avale pas le marqueur BEGIN qui le suit sur sa ligne` | BEGIN d'un autre type sur la ligne du END | Modèle de domaine et invariants | 1.1, 1.2 | core/src/test/java/com/nexus/security/SensitiveContentRedactorTest.java#beginOfAnotherTypeOnTheEndLineStillHidesTheRestOfTheLine<br>core/src/test/java/com/nexus/security/SensitiveContentRedactorTest.java#endMarkerFollowedByAnIncompleteBeginKeepsClosingAtTheEndOfItsLine |
| `secret-redaction › La fin d'un marqueur BEGIN est cherchée sur sa ligne` | Marqueur coupé sur deux lignes | Décisions, Risques / Compromis | 1.4 | core/src/test/java/com/nexus/security/SensitiveContentRedactorTest.java#splitMarkerVariantsOpenNoKey |
| `secret-redaction › Un changement de politique invalide les index dérivés` | Mise à jour d'une base existante | Persistance et intégrité | 2.2 | core/src/test/java/com/nexus/persistence/sqlite/SchemaMigratorEndBeginSameLineUpgradeTest.java#upgradesRealV021SchemaOnceAndRebuildsDerivedIndexesUnderTheNewPolicy<br>core/src/test/java/com/nexus/persistence/sqlite/SchemaMigratorEndBeginSameLineUpgradeTest.java#freshDatabaseRecordsV022WithChecksum |
| `secret-redaction › Un changement de politique invalide les index dérivés` | Index lexical d'une ancienne politique | Persistance et intégrité | 2.1 | preuve existante partielle, réalignée sur v11 : `LuceneSearchIndexTest#redactionPolicyCheckRejectsMissingOrObsoleteCommitPolicy` |
