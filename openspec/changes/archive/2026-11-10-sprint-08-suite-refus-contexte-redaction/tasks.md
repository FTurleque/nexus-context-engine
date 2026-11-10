# Tasks

## 1. Refus et masquage alignés

- [x] 1.1 Dans `core/src/main/java/com/nexus/security/SensitiveContentRedactor.java`, lancer `OVERSIZED_TOKEN`, `OVERSIZED_VALUE` et `OVERSIZED_URI` sur `replacePrivateKeyBlocks(content)` et masquer sur ce même texte ; couvre `secret-redaction › Un jeton collé à un bloc de clé privée est masqué ou refusé` ; vérifier : `./mvnw -B -ntp -pl core test -Dtest=SensitiveContentRedactorTest -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
- [x] 1.2 Aligner les classes de corps : masquage `xox[abposr]-[A-Za-z0-9_-]{10,255}+`, `gh[pousr]_\w{20,255}+`, `(?:sk|rk)_(?:live|test)_\w{16,255}+` ; refus `\w{256}` après le préfixe pour `gh?_`, `github_pat_`, `sk_`/`rk_` ; Javadoc d'`OVERSIZED_TOKEN` (« borne de refus = borne de masquage + 1 sur le même corps ») étendue à toutes les familles ; couvre `secret-redaction › Le masquage et le refus d'un jeton comptent la même classe de caractères` ; vérifier : même commande
- [x] 1.3 Écrire dans `core/src/test/java/com/nexus/security/SensitiveContentRedactorTest.java` les tests 255/256 : `sk-`, `sk-ant-`, `glpat-`, `xoxb-` collés après `-----END RSA PRIVATE KEY-----` (255 masqué sans fragment, 256 refusé sans fragment dans le message) ; `xoxb-`, `ghp_`, `sk_live_` avec un `_` (court masqué, 255 masqué, 256 refusé) ; couvre tous les scénarios des deux exigences ajoutées ; vérifier : même commande, et échec des nouveaux tests sur le code de `secret-redaction-v7`
- [x] 1.4 Ajouter les deux voisinages à la sonde versionnée si `SecretRedactionProbeTest` porte une liste de cas (non applicable : la liste fixe de 35 cas échantillonne des longueurs nominales, les bornes vivent dans `SensitiveContentRedactorTest`) ; vérifier : `./mvnw -B -ntp -pl core test -Dtest=SecretRedactionProbeTest -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`

## 2. Invalidation des index dérivés

- [x] 2.1 Passer `POLICY_VERSION` à `secret-redaction-v8` et `CONTENT_PROFILE_VERSION` à 7 (`SemanticIndexingService`) ; mettre à jour les tests qui citent `secret-redaction-v7` ou `content-v6` ; couvre `secret-redaction › Un changement de politique invalide les index dérivés` (scénario « Index lexical d'une ancienne politique ») ; vérifier : `./mvnw clean install`
- [x] 2.2 Ajouter `core/src/main/resources/db/migration/V019__invalidate_token_context_bound_indexes.sql` (modèle V018), l'enregistrer dans `SchemaMigrator` et écrire son test d'upgrade sur le modèle de `SchemaMigratorSkTokenBoundUpgradeTest` (génération incrémentée, projets `NOT_INDEXED`, registre conservé), skill `nexus-schema-migration` ; couvre le scénario « Mise à jour d'une base existante » ; vérifier : `./mvnw -B -ntp -pl core test -Dtest=SchemaMigratorChecksumTest,SchemaMigrator*UpgradeTest -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
- [x] 2.3 Remplacer `content-v6` par `content-v7` dans la documentation courante et les `require(...)` de `scripts/release/test-operational-doc-contracts.sh` ; ajouter la section « Mise à niveau » de V019 à `docs/developer/release-and-recovery.md` ; vérifier : `bash .claude/scripts/contracts.sh` et `pwsh -NoProfile -File scripts/self-smoke.ps1`

## 3. Documentation durable

- [x] 3.1 Mettre à jour `docs/developer/secret-redaction.md` : texte des bornes (même texte lu par le refus et le masquage, classes communes), lignes « Existant vérifié » (`POLICY_VERSION` v8), organigramme de `redact` de ce design dans la section « OpenSpec secret-redaction » ; vérifier : `bash .claude/scripts/contracts.sh`
- [x] 3.2 Mettre à jour `docs/developer/openspec-traceability.md` : lignes des deux exigences ajoutées avec les tests de 1.3, ligne « Un changement de politique invalide les index dérivés » avec le test de 2.2 ; vérifier : `bash scripts/release/test-openspec-doc-contracts.sh` après synchronisation des specs

## Workflow follow-up

- Une PR vers `develop` (skill `nexus-pr-workflow`), merge exact-head ; revue `nexus-security-auditor` (zone `security/`).
- Synchroniser les specs et archiver le changement une fois les tâches vérifiées.
