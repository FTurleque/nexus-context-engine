# Design

**Profil :** complet

## Contexte

- [Existant vérifié] `redact` remplace d'abord les blocs de clé privée par `replacePrivateKeyBlocks`, puis refuse ou masque les autres formats sur ce même texte (`core/src/main/java/com/nexus/security/SensitiveContentRedactor.java:156-182`, appel en `:160`).
- [Existant vérifié] `replacePrivateKeyBlocks` (`:399-438`) : `begin = indexOf("-----BEGIN ", cursor)` (`:403`) ; `markerEnd = indexOf("-----", begin + 11) + 5` (`:409-414`), sans limite de ligne ; si le marqueur ne se termine ni par `PRIVATE KEY-----` ni par `PRIVATE KEY BLOCK-----` (`:24-27`, `:416`), le texte est copié jusqu'à `markerEnd` et la recherche reprend en `markerEnd` (`:417-419`). Sinon, la ligne de fin `-----END <type>-----` est cherchée (`:422-424`). Le bloc trouvé, ou à défaut tout le reste du contenu, est remplacé par `[REDACTED]` en gardant ses CR et LF (`:425-435`, `appendLineSeparators` `:440-447`).
- [Existant vérifié] Défaut : la recherche de `-----` part de `begin + 11` et peut trouver les tirets d'ouverture du marqueur suivant. Pour `-----BEGIN \n-----BEGIN RSA PRIVATE KEY-----`, le premier marqueur s'étend jusqu'au début du second, n'est pas une clé, et la reprise en `markerEnd` tombe à l'intérieur du second marqueur. Le vrai début n'est plus jamais trouvé.
- [Existant vérifié] Sonde du 12/11 (`core/target/classes` compilé depuis l'arbre courant, `secret-redaction-v9`, JDK 24, corps factice `MIIfakebodyNOTAKEY0123`) : corps en clair derrière `if (line.startsWith("-----BEGIN ")) {}`, une ligne `-----BEGIN `, une ligne `-----BEGIN CERT`, et dans `-----BEGIN CERTIFICATE-----BEGIN RSA PRIVATE KEY-----`. Bloc PGP en clair derrière une ligne `-----BEGIN `. Masqué : bloc seul, et `-----BEGIN CERTIFICATE-----` suivi sur une autre ligne d'un bloc complet.
- [Existant vérifié] Tests existants des blocs : `SensitiveContentRedactorTest#redactsPrivateKeyBlocksWithoutChangingSourceLineCount` (`core/src/test/java/com/nexus/security/SensitiveContentRedactorTest.java:455`), `#redactsTruncatedPrivateKeyBlockThroughEndOfContent` (`:473`), `#masksTokenGluedToAPrivateKeyBlockWhoseBodyHas255Characters` et `#refusesTokenGluedToAPrivateKeyBlockWhoseBodyHas256Characters` (`:181`, `:190`), et les cas de bloc de `SecretRedactionProbeTest` (`:57-58`, `:67`). Garde-fou de temps : `#malformedKeysAndOversizedValuesHaveBoundedLookahead` (`:55`, 10 s).
- [Existant vérifié] `POLICY_VERSION = "secret-redaction-v9"` (`SensitiveContentRedactor.java:21`) ; `CONTENT_PROFILE_VERSION = 8` (`core/src/main/java/com/nexus/search/semantic/SemanticIndexingService.java:27`) ; dernière migration `V020__invalidate_composite_name_indexes.sql`, enregistrée à `core/src/main/java/com/nexus/persistence/sqlite/SchemaMigrator.java:40`, testée par `SchemaMigratorCompositeNameUpgradeTest`. Le premier numéro libre est donc `V021`.
- Page durable : `docs/developer/secret-redaction.md`, section « OpenSpec secret-redaction ».

## Objectifs / Hors objectifs

**Objectifs :**
- `secret-redaction › Un marqueur BEGIN qui n'ouvre pas une clé privée ne fait pas sauter le bloc suivant`.
- `secret-redaction › Un changement de politique invalide les index dérivés` (`secret-redaction-v10`).

**Hors objectifs :** limiter le marqueur à sa ligne (sauf réponse contraire à la [Question] 1 de la proposition) ; reconnaître des marqueurs en minuscules ou un corps PEM sans marqueur ; toucher les autres motifs ou `SecretAssignmentScanner`.

## Composants et responsabilités

Non applicable : `SensitiveContentRedactor` garde ses responsabilités et son API (`redact`, `redactField`). Seule la position de reprise d'une boucle privée change ; aucun appelant n'est modifié.

## Modèle de domaine et invariants

- Invariant de recherche : après un marqueur `-----BEGIN ` qui n'ouvre pas une clé privée, la recherche suivante part de `begin + PRIVATE_KEY_BEGIN.length()`. Aucun `-----BEGIN ` situé après ce point n'est sauté.
- Invariant de non-régression : un contenu dont la sortie v9 ne laisse aucun corps de clé en clair garde exactement la même sortie. Seuls les blocs aujourd'hui sautés changent de rendu.
- Invariant de lignes : le remplacement conserve chaque CR et LF du bloc (`.claude/rules/search-and-redaction.md`), donc les ranges persistés ne bougent pas.
- Invariant de coût : la recherche de `-----` lancée depuis `begin + 11` s'arrête au plus tard aux tirets du `-----BEGIN ` suivant. Chaque zone du contenu n'est donc balayée qu'un nombre borné de fois, et le travail reste linéaire.

## Traitements et interactions

Vue organigramme (cible) : où reprend la recherche après un marqueur `-----BEGIN ` ? Couvre `secret-redaction › Un marqueur BEGIN qui n'ouvre pas une clé privée ne fait pas sauter le bloc suivant`.

```mermaid
flowchart TD
    A["begin : prochain -----BEGIN à partir de cursor"] --> B{"trouvé ?"}
    B -- non --> Z["copier le reste, fin"]
    B -- oui --> C["markerEnd : premiers ----- après begin + 11, plus 5"]
    C --> D{"marqueur terminé par PRIVATE KEY----- ou PRIVATE KEY BLOCK----- ?"}
    D -- non --> E["copier jusqu'à begin + 11; cursor = begin + 11 (v9 : markerEnd)"]
    E --> A
    D -- oui --> F{"-----END type----- trouvé après markerEnd ?"}
    F -- oui --> G["bloc remplacé par [REDACTED], CR et LF conservés; cursor = fin du marqueur END"]
    F -- non --> H["reste du contenu remplacé par [REDACTED], CR et LF conservés"]
    G --> A
```

Pour `-----BEGIN \n-----BEGIN RSA PRIVATE KEY-----`, le premier marqueur n'est pas une clé : la recherche reprend juste après son `-----BEGIN `, et retrouve le second marqueur au tour suivant. Pour `-----BEGIN CERTIFICATE-----BEGIN RSA PRIVATE KEY-----`, les tirets de fin du certificat sont aussi les tirets d'ouverture de la clé : reprendre en `markerEnd` les saute, reprendre en `begin + 11` les retrouve. Entre `begin + 11` et le début des tirets trouvés, il n'y a aucun `-----` : la reprise ne peut ni retrouver le même marqueur ni boucler.

## États et transitions

Non applicable : traitement pur, sans état persistant hors version de politique.

## Contrats d'interface

Non applicable : aucune API ne change. La projection publique applique la même redaction aux trois canaux (`ARCH-0085`).

## Persistance et intégrité

- [Décision] Ajouter la migration `core/src/main/resources/db/migration/V021__invalidate_private_key_marker_indexes.sql`, copie du corps de `V020` avec un commentaire SQL en français : `INSERT OR IGNORE` de `project_index_generations`, génération incrémentée, projets `NOT_INDEXED`, `last_indexed_at` à `NULL`, faits canoniques conservés. Elle est enregistrée dans `SchemaMigrator` après `V020` (`:40`). Le numéro est revérifié à l'implémentation (premier libre), conformément à `DATA-0020` (migrations append-only protégées par checksum).
- `POLICY_VERSION` passe à `secret-redaction-v10` et `CONTENT_PROFILE_VERSION` à 9 (`content-v9`). Lucene compare la version de politique de son commit, et le profil sémantique inclut la politique (`DATA-0074`).

## Transactions, concurrence et idempotence

La migration est un lot SQL unique appliqué par le migrateur. Rejouée, elle est ignorée grâce à l'historique des migrations. La redaction reste déterministe et idempotente.

## Sécurité et isolation

- Les index d'une politique antérieure peuvent contenir en clair le corps d'une clé privée placée derrière un marqueur incomplet (fixture de parseur PEM, documentation). Ils sont invalidés puis reconstruits (`DATA-0073` : Lucene est dérivé et reconstructible).
- Les tests n'utilisent qu'un corps factice assemblé à l'exécution, à la manière de `SensitiveContentRedactorTest#rsaPrivateKeyBlock` (`:173-177`). Aucun vrai matériau de clé n'entre dans le dépôt.

## Erreurs et reprise

Non applicable : aucune erreur nouvelle. Le remplacement des blocs ne lève pas `OversizedSecretException`.

## Observabilité

Non applicable : la redaction ne produit pas de diagnostic propre ; les diagnostics existants (`INDEX_REDACTION_LIMIT`, `CONTEXT_REDACTION_LIMIT`) ne changent pas.

## Performance et mesure

- Aucun seuil nouveau : ni l'audit ni le critère de sortie du sprint 15 n'en fixent. Le cas forgé (1 Mo de `-----BEGIN \n` répété, puis 1 Mo de `-----BEGIN CERTIFICATE-----\n` répété) est ajouté au garde-fou existant `#malformedKeysAndOversizedValuesHaveBoundedLookahead` (10 s), sans modifier ce seuil.
- Sonde du 12/11 sur une copie de la boucle (meilleur de 3, indicatif) : 9 ms contre 5 ms pour 1 Mo de `-----BEGIN \n`, 2 ms contre 1 ms pour 1 Mo de `-----BEGIN CERTIFICATE-----\n`. `redact` complet sur le premier contenu : 278 ms.

## Compatibilité, migration et retour arrière

- Mise à niveau : rebuild normal des dérivés de chaque projet au premier passage. Une section « Mise à niveau V021 — marqueur BEGIN incomplet » est ajoutée à `docs/developer/release-and-recovery.md`, sur le modèle de V020 (`:337`).
- Retour arrière : un binaire antérieur refuse une base à version de schéma supérieure ; restaurer la sauvegarde de `NEXUS_HOME`.

## Décisions

- [Décision] Reprendre à `begin + PRIVATE_KEY_BEGIN.length()` quand le marqueur n'ouvre pas une clé privée, comme le propose l'audit en premier. Sonde du 12/11 sur une copie de la boucle : les sept cas de la proposition sont masqués, séparateurs conservés, et les quatre cas sans défaut gardent une sortie identique à `redact` v9.
- [Décision] Ne pas retenir la seconde correction de l'audit (« chercher la fin de marqueur sur la même ligne ») comme correction du défaut. Avec une reprise en `markerEnd`, elle laisse en clair `-----BEGIN CERTIFICATE-----BEGIN RSA PRIVATE KEY-----`, dont la fin de marqueur est sur la même ligne. Elle ne pourrait venir qu'en complément, pour le masquage en trop ([Question] 1 de la proposition).
- [Décision] Ne pas reconnaître le bloc par une expression régulière : la boucle par `indexOf` reste linéaire et sa sortie est comparée à la boucle v9 sur les cas sans défaut, comme le demande le skill `nexus-redaction-change` pour une logique manuelle.
- Aucun nouvel ADR : la politique de redaction et son invalidation suivent un processus établi (skill `nexus-redaction-change`, `DATA-0020`, `DATA-0073`, `DATA-0074`).

## Risques / Compromis

- [Risque] Un contenu aujourd'hui correct changerait de rendu. → Test de non-régression qui compare la boucle corrigée à la boucle v9, recopiée dans le test, sur les cas sans défaut (bloc seul, certificat puis bloc, marqueur incomplet sans bloc, bloc tronqué, jeton collé à la ligne de fin).
- [Risque] Un nouveau cas de masquage en trop : un marqueur incomplet est désormais suivi d'une nouvelle recherche, qui peut trouver un `-----BEGIN … PRIVATE KEY-----` fictif plus loin. → C'est le comportement attendu pour un vrai marqueur ; la limite du marqueur multiligne reste documentée ou corrigée selon la [Question] 1.
- [Risque] Rebuild de tous les projets à la mise à niveau. → Inévitable pour purger les corps de clé en clair ; documenté.

## Stratégie de vérification

- `./mvnw -B -ntp -pl core test -Dtest=SensitiveContentRedactorTest,SecretRedactionProbeTest -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`. Les nouveaux tests de bloc après un marqueur échouent sur le code v9 (corps en clair) et passent après la correction.
- `./mvnw -B -ntp -pl core test -Dtest=SchemaMigratorChecksumTest,SchemaMigrator*UpgradeTest -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`.
- `bash .claude/scripts/contracts.sh` (`content-v9`, `secret-redaction-v10`, `V021`), `archgate check`, `./mvnw clean install`, `pwsh -NoProfile -File scripts/self-smoke.ps1`.
- `openspec validate sprint-15-redaction-begin-incomplet --strict`. Diagramme validé par le contrôle de types de `scripts/release/test-openspec-doc-contracts.sh`.

## Traçabilité

| Exigence (`capacité › titre`) | Scénario | Conception ou diagramme | Tâche | Test ou preuve |
|---|---|---|---|---|
| `secret-redaction › Un marqueur BEGIN qui n'ouvre pas une clé privée ne fait pas sauter le bloc suivant` | Ligne BEGIN incomplète avant un bloc RSA | Organigramme de reprise | 1.1, 1.2 | test non écrit |
| `secret-redaction › Un marqueur BEGIN qui n'ouvre pas une clé privée ne fait pas sauter le bloc suivant` | Littéral de code avant un bloc RSA | Organigramme de reprise | 1.1, 1.2 | test non écrit |
| `secret-redaction › Un marqueur BEGIN qui n'ouvre pas une clé privée ne fait pas sauter le bloc suivant` | Marqueur d'un autre type sans tirets de fin sur sa ligne | Organigramme de reprise | 1.1, 1.2 | test non écrit |
| `secret-redaction › Un marqueur BEGIN qui n'ouvre pas une clé privée ne fait pas sauter le bloc suivant` | Marqueur complet d'un autre type collé au vrai marqueur | Organigramme de reprise, Décisions | 1.1, 1.2 | test non écrit |
| `secret-redaction › Un marqueur BEGIN qui n'ouvre pas une clé privée ne fait pas sauter le bloc suivant` | Bloc PGP après une ligne BEGIN incomplète | Organigramme de reprise | 1.1, 1.2 | test non écrit |
| `secret-redaction › Un marqueur BEGIN qui n'ouvre pas une clé privée ne fait pas sauter le bloc suivant` | Marqueur incomplet sans bloc de clé privée | Modèle de domaine et invariants | 1.3 | test non écrit |
| `secret-redaction › Un changement de politique invalide les index dérivés` | Mise à jour d'une base existante | Persistance et intégrité | 2.2 | test non écrit (test d'upgrade de `V021`) |
| `secret-redaction › Un changement de politique invalide les index dérivés` | Index lexical d'une ancienne politique | Persistance et intégrité | 2.1 | preuve existante partielle à réaligner sur v10 : `LuceneSearchIndexTest#redactionPolicyCheckRejectsMissingOrObsoleteCommitPolicy` |

## Questions ouvertes

Aucune autre que la [Question] 1 de la proposition.
