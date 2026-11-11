# Proposition

## Why

Deux défauts de `SensitiveContentRedactor`, antérieurs aux sprints 8, ont été relevés à la vérification du 11/11. D'abord, un fichier forgé de quelques Mo bloque l'indexation : un contenu de 1 Mo fait de « eyJ- » répété coûte environ 12 s par passe de redaction. Ensuite, un mot de passe placé dans un élément XML au nom composé (`<db-password>`, `<db_password>`) part en clair dans Lucene, dans les embeddings et dans les sorties CLI/REST/MCP. Le sprint 14 exige « 1 Mo de « eyJ- » redigé en moins d'1 s (test) ; <db-password> et <db_password> masqués » (`docs/project/audits/2026-10-17/sprints.md:332`).

Sources : `docs/project/audits/2026-10-17/findings.json` (`AUD-SEC-24`, `AUD-SEC-25`, `AUD-PRF-30`), `docs/project/audits/2026-10-17/etat-2026-11-11.md:83-101`, `:153-161`.

## What Changes

- [Existant vérifié] Le motif `JWT` `\beyJ[A-Za-z0-9_-]{10,4096}+\.` (`core/src/main/java/com/nexus/security/SensitiveContentRedactor.java:78-81`, appliqué en `:132`) est retenté à chaque `eyJ` précédé d'un non-mot. Chaque tentative balaie jusqu'à 4 096 caractères : 1 Mo de « eyJ- » coûte 11,9 à 13,8 s selon l'audit (2 vCPU). La sonde du 11/11, compilée en `--release 21` depuis l'arbre courant et lancée sur un JDK 24 (meilleur de 3), mesure 16 479 ms. L'alternative CDATA de `XML_SECRET_ELEMENT` (`:72-74`) coûte 1 341 ms selon l'audit et 1 614 ms à la sonde sur « <password><![CDATA[ » répété, contre 285 à 318 ms pour 1 Mo de code ordinaire. Un fichier peut atteindre 8 Mo par défaut (`core/src/main/java/com/nexus/security/ProjectFileLimits.java:18`).
- [Existant vérifié] `XML_OPEN` `(?:[A-Za-z0-9_-]++[._-])*+` (`SensitiveContentRedactor.java:38-39`) : le possessif avale `db-password` puis exige un séparateur, et le nom échoue. Résultat de la sonde du 11/11 : `<db-password>`, `<db_password>`, `<my-token>` et `<app.db-password>` restent en clair ; `<db.password>`, `<api_key>`, `<access_key>` et `<private_key>` sont masqués. `<db-password>` suivi de 5 000 caractères n'est ni masqué ni refusé, car `OVERSIZED_VALUE` réutilise `XML_OPEN` (`:95`).
- [Écart] La correction proposée par l'audit, `(?:[A-Za-z0-9]++[._-])*+`, casse les clés sensibles qui contiennent elles-mêmes un séparateur. Sonde du 11/11 sur ce motif : `<db-password>` et `<db_password>` reconnus, mais `<api_key>`, `<api-key>`, `<access_key>` et `<private_key>` ne le sont plus, alors qu'ils sont masqués aujourd'hui. Elle n'est pas reprise telle quelle.
- [Écart] Même construction possessive dans `OPTION_NAME` (`SensitiveContentRedactor.java:36`) : à la sonde du 11/11, `--api-key`, `--private-key` et `--access-key` gardent leur valeur en clair, alors que `docs/developer/secret-redaction.md:12` annonce les options `api-key` et `access-token`. Ce défaut est absent de l'audit (voir [Question] 1).
- [Écart] La garde proposée par l'audit, `(?<![A-Za-z0-9_-])` devant `eyJ`, laisserait en clair un JWT précédé d'un `-` : à la sonde du 11/11, `id-<jwt>` est masqué sous `secret-redaction-v8`.
- [Exigence] Une répétition forgée de débuts de JWT ou d'ouvertures CDATA sur 1 Mo est redigée en moins d'une seconde. Seuil `eyJ-` : `sprints.md:332`. Seuil CDATA : action de `AUD-SEC-24` dans `findings.json`.
- [Exigence] Un JWT reste masqué quel que soit le séparateur qui le précède, `-` compris : la correction du coût ne réduit pas le masquage.
- [Exigence] Un élément XML ou une option dont le nom se termine par une clé sensible, en début de nom ou après un séparateur, est masqué. Au-delà de 4 096 caractères de valeur, l'élément XML est refusé.
- [Exigence] La correction passe `POLICY_VERSION` à `secret-redaction-v9` et le profil sémantique à `content-v8`, et ajoute une migration d'invalidation `V020`, conformément à `secret-redaction › Un changement de politique invalide les index dérivés` et au skill `nexus-redaction-change`.
- [Proposition] `AUD-PRF-30` (Info) : mesurer le coût des 9 passes de `STRUCTURED_TOKENS` (`SensitiveContentRedactor.java:53-62`, `:129-131`) sur un corpus d'indexation. Si le surcoût est confirmé, pré-filtrer chaque passe par `indexOf` de son préfixe littéral, à sortie identique.
- [Écart] `AUD-PRF-30` demande une mesure « dans le benchmark d'indexation », mais aucun banc opt-in de `core/src/test/java/com/nexus/benchmark/` n'isole aujourd'hui la redaction. `IndexingHeapPeakBenchmarkTest` mesure le heap et `singleWriteTransactionMs`, et `ScaleRegressionBenchmarkTest` n'appelle pas le redactor.

## Type de changement

fonctionnel (politique de redaction, format des index dérivés) : profil complet.

## Périmètre et exclusions

**Dans le périmètre :**
- Coût linéaire des motifs `JWT` et CDATA de `XML_SECRET_ELEMENT`, à sortie inchangée pour les contenus déjà masqués.
- Noms composés de `XML_OPEN` (masquage et refus `OVERSIZED_VALUE`) et de `OPTION_NAME`, sous réserve de la [Question] 1.
- Version de politique, profil sémantique, migration `V020`, documentation et contrats associés (skills `nexus-redaction-change` et `nexus-schema-migration`).
- Mesure conditionnelle de `AUD-PRF-30` ; le pré-filtre n'est livré que si la mesure le justifie.

**Hors périmètre :**
- Les autres familles de jetons et leurs bornes (sprints 8), ainsi que `SecretAssignmentScanner`.
- La liste des clés sensibles (`SENSITIVE_NAME`, `:33-34`) et la sonde versionnée de 35 cas (`SecretRedactionProbeTest`), dont le compte est épinglé par la spec.
- Un JWT multiligne, un élément XML sur plusieurs lignes, et un nom dont la clé sensible n'est pas le dernier composant (`<password_hint>`).
- Un nouveau plafond de performance dans les gates de benchmark.

## Capabilities

### New Capabilities

### Modified Capabilities
- `secret-redaction` : quatre exigences ajoutées (coût linéaire face aux répétitions forgées, JWT après un séparateur, éléments XML et options au nom composé) ; réécriture de « Un changement de politique invalide les index dérivés » pour `secret-redaction-v9`.

## Impact

- Code : `core/src/main/java/com/nexus/security/SensitiveContentRedactor.java` (`JWT`, `XML_SECRET_ELEMENT`, `XML_OPEN`, `OPTION_NAME`, `POLICY_VERSION`, éventuel pré-filtre de `STRUCTURED_TOKENS`) ; `core/src/main/java/com/nexus/search/semantic/SemanticIndexingService.java:27` (`CONTENT_PROFILE_VERSION` de 7 à 8) ; `core/src/main/java/com/nexus/persistence/sqlite/SchemaMigrator.java:39` (enregistrement de `V020`).
- Schéma : `core/src/main/resources/db/migration/V020__invalidate_composite_name_indexes.sql`, sur le modèle de `V018`/`V019`.
- Tests : `SensitiveContentRedactorTest`, nouveau `SchemaMigratorCompositeNameUpgradeTest`, `SchemaMigratorChecksumTest`, tests qui citent `secret-redaction-v8` ou `content-v7`, et un banc opt-in de mesure de la redaction (`AUD-PRF-30`).
- Documentation : `docs/developer/secret-redaction.md`, `docs/developer/openspec-traceability.md`, `docs/developer/release-and-recovery.md`, et `content-v8` dans `README.md`, `docs/architecture/overview.md`, `docs/architecture/arc42/08-concepts-transverses.md`, `docs/architecture/arc42/10-exigences-qualite.md`, `docs/developer/current-limitations.md`, `docs/developer/semantic-search.md`. Contrat : `scripts/release/test-operational-doc-contracts.sh:240-254`.
- ADR applicables, sans nouvel ADR : `DATA-0020` (migrations embarquées, append-only), `DATA-0073` (Lucene dérivé et reconstructible), `DATA-0074` (version de redaction dans le commit Lucene et le profil sémantique), `ARCH-0085` (une correction de redaction vaut pour CLI, REST et MCP).
- Effet utilisateur : rebuild des index dérivés de tous les projets à la mise à niveau.

## Questions ouvertes

- [Question] 1 — Corriger `OPTION_NAME` (`--api-key`, `--private-key`, `--access-key`) dans ce changement ? Ce défaut ne figure pas dans l'audit. Le corriger plus tard imposerait une politique `v10` et une seconde migration. Si la réponse est non, retirer l'exigence « Une option de ligne de commande au nom composé est redigée » et le groupe de tâches 2.3, puis ouvrir un constat.
- [Question] 2 — Préférer la garde de l'audit, plus simple et alignée sur l'ancrage des jetons `sk-`, en acceptant qu'un JWT précédé d'un `-` passe en clair ? Il faudrait alors retirer le scénario « JWT après un tiret » et ajouter cette limite à `docs/developer/secret-redaction.md`.
