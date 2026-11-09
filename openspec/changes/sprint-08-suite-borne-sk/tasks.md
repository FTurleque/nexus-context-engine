# Tasks

## 1. Borne des jetons sk-

- [ ] 1.1 `AUD-SEC-20` — Dans `core/src/main/java/com/nexus/security/SensitiveContentRedactor.java:89-91`, remplacer `sk-[A-Za-z0-9_-]{261}` par `sk-(?:proj-|ant-)?+[A-Za-z0-9_-]{256}` dans `OVERSIZED_TOKEN`, en suivant le skill `nexus-redaction-change` ; couvre `secret-redaction › Un jeton sk- est masqué ou refusé à toute longueur de corps` (scénarios « Corps de 256 caractères », « Fenêtre sans fuite ») ; vérifier : `./mvnw -B -ntp -pl core test -Dtest=SensitiveContentRedactorTest -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
- [ ] 1.2 `AUD-SEC-20` — Écrire dans `core/src/test/java/com/nexus/security/SensitiveContentRedactorTest.java` les tests N/N+1 : `sk-`, `sk-proj-` et `sk-ant-` avec un corps de 255 caractères (masqué) et de 256 (refusé, message sans aucune partie du jeton), et `sk-` de 257 à 260 (refusé) ; couvre tous les scénarios de l'exigence ; vérifier : même commande, puis les tests de 256 à 260 en échec sur une copie qui garde l'ancien motif
- [ ] 1.3 `AUD-SEC-20` — Ajouter un cas `sk-` à la borne dans la sonde versionnée si `SecretRedactionProbeTest` en porte la liste des formats ; vérifier : `./mvnw -B -ntp -pl core test -Dtest=SecretRedactionProbeTest -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`

## 2. Invalidation des index dérivés

- [ ] 2.1 `AUD-SEC-20` — Passer `POLICY_VERSION` à `secret-redaction-v7` (`SensitiveContentRedactor.java:19`) et `CONTENT_PROFILE_VERSION` à 6 (`SemanticIndexingService.java:27`) ; mettre à jour les tests qui citent `secret-redaction-v6` ou `content-v5` ; couvre `secret-redaction › Un changement de politique invalide les index dérivés` (scénario « Index lexical d'une ancienne politique ») ; vérifier : `./mvnw clean install`
- [ ] 2.2 `AUD-SEC-20` — Ajouter la migration `core/src/main/resources/db/migration/V0nn__invalidate_sk_token_bound_indexes.sql` (modèle V011, numéro suivant libre) et son test d'upgrade sur le modèle de `SchemaMigratorSecretFormatUpgradeTest` (génération incrémentée, projets `NOT_INDEXED`, registre conservé), skill `nexus-schema-migration` ; couvre le scénario « Mise à jour d'une base existante » ; vérifier : `./mvnw -B -ntp -pl core test -Dtest=SchemaMigratorChecksumTest,SchemaMigrator*UpgradeTest -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
- [ ] 2.3 `AUD-SEC-20` — Remplacer `content-v5` par `content-v6` dans `README.md`, `docs/architecture/overview.md`, `docs/architecture/arc42/08-concepts-transverses.md`, `docs/architecture/arc42/10-exigences-qualite.md`, `docs/developer/current-limitations.md`, `docs/developer/release-and-recovery.md`, `docs/developer/semantic-search.md` et dans les `require(...)` de `scripts/release/test-operational-doc-contracts.sh` (`:248`) ; ajouter la section « Mise à niveau » de la nouvelle migration à `docs/developer/release-and-recovery.md` ; vérifier : `bash .claude/scripts/contracts.sh` et `pwsh -NoProfile -File scripts/self-smoke.ps1`

## 3. Documentation durable

- [ ] 3.1 `AUD-SEC-20` — Mettre à jour `docs/developer/secret-redaction.md` : texte des bornes (`:40-46`), lignes « Existant vérifié » (`:73-77`, `POLICY_VERSION` v7, borne `sk-` alignée), organigramme `sk-` de ce design dans la section « OpenSpec secret-redaction », et retrait de l'écart `sk-` dans « Écarts et limites » ; vérifier : `bash .claude/scripts/contracts.sh`
- [ ] 3.2 `AUD-SEC-20` — Mettre à jour `docs/developer/openspec-traceability.md` : lignes de l'exigence ajoutée avec les tests de 1.2, ligne réécrite « Un changement de politique invalide les index dérivés » avec le test de 2.2 ; vérifier : `bash scripts/release/test-openspec-doc-contracts.sh` après synchronisation des specs

## Workflow follow-up

- Une PR vers `develop` (skill `nexus-pr-workflow`), merge exact-head ; revue `nexus-security-auditor` (zone `security/`).
- Mettre à jour le statut de `AUD-SEC-20` dans `docs/project/audits/2026-10-17/findings.json` avec la PR.
- Synchroniser les specs et archiver le changement une fois les tâches vérifiées.
