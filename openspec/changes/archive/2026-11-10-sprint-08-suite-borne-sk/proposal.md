# Proposition

## Why

Un jeton `sk-` dont le corps compte 256 à 260 caractères n'est ni masqué ni refusé : le motif de redaction s'arrête à 255 caractères de corps, le refus ne commence qu'à 261 caractères après `sk-`. Ce jeton part en clair dans Lucene, les embeddings et les sorties CLI/REST/MCP, alors que `docs/developer/secret-redaction.md` promet un refus « juste au-dessus » de 255. Les formats réels connus (OpenAI ~164, Anthropic ~108 caractères) sont hors de cette fenêtre, mais la garantie « jamais publié en clair » est fausse pour toute la famille `sk-`.

Source : `docs/project/audits/2026-10-17/findings.json` (`AUD-SEC-20`, sprint 8), `etat-2026-11-09.md`.

## What Changes

- [Existant vérifié] Motif de redaction `sk-(?:proj-|ant-)?+[A-Za-z0-9_-]{20,255}+` suivi d'une garde négative (`core/src/main/java/com/nexus/security/SensitiveContentRedactor.java:47-49`) ; refus `OVERSIZED_TOKEN` par `sk-[A-Za-z0-9_-]{261}` (`:89-91`) ; `POLICY_VERSION = "secret-redaction-v6"` (`:19`).
- [Existant vérifié] Sonde du 09/11 (java 21, copie des classes) : `sk-` corps 255 masqué, 256 à 260 en clair, 261 refusé ; `sk-ant-` corps 256 en clair, 257 refusé ; `sk-proj-` corps 256 refusé.
- [Existant vérifié] Seul test `sk-` : 300 caractères (`core/src/test/java/com/nexus/security/SensitiveContentRedactorTest.java:187`). Le changement archivé `2026-11-09-preciser-bornes-redaction-recence-git` n'a chiffré que `ghp_` et a écarté `sk-` (« borne composée »).
- [Écart] `docs/developer/secret-redaction.md:42-43` et `:75` annoncent un corps de jeton à préfixe borné à 255 caractères avec refus au-delà ; le code laisse passer `sk-` de 256 à 260 et `sk-ant-` à 256.
- [Exigence] Pour `sk-`, `sk-proj-` et `sk-ant-`, un corps (après le préfixe optionnel) de 20 à 255 caractères est masqué, un corps de 256 caractères ou plus est refusé.
- [Exigence] La correction incrémente `POLICY_VERSION`, le profil sémantique `content-vN`, et ajoute une migration d'invalidation, conformément à `secret-redaction › Un changement de politique invalide les index dérivés`.

## Type de changement

fonctionnel (politique de redaction, format des index dérivés) : profil complet.

## Périmètre et exclusions

**Dans le périmètre :**
- Motif de refus `OVERSIZED_TOKEN` pour la famille `sk-` et tests N/N+1 des trois préfixes.
- Version de politique, profil sémantique, migration d'invalidation, documentation et contrats associés (skill `nexus-redaction-change`).

**Hors périmètre :**
- Les autres préfixes (`ghp_`, `glpat-`, `xox?-`, `sk_live_`…) : alignés au 09/11 (sonde : 255 masqué, 256 refusé).
- La borne basse de 20 caractères et la liste des formats redigés.

## Capabilities

### New Capabilities

### Modified Capabilities
- `secret-redaction`: ajout d'une exigence sur la borne des jetons `sk-` ; réécriture de « Un changement de politique invalide les index dérivés » pour la nouvelle version de politique.

## Impact

- Code : `core/src/main/java/com/nexus/security/SensitiveContentRedactor.java` (`OVERSIZED_TOKEN`, `POLICY_VERSION`), `SemanticIndexingService.CONTENT_PROFILE_VERSION`.
- Schéma : migration d'invalidation `core/src/main/resources/db/migration/V0nn__invalidate_sk_token_bound_indexes.sql` (numéro suivant libre ; `sprint-06-suite-performance-sqlite` ajoute aussi une migration).
- Tests : `SensitiveContentRedactorTest`, `SecretRedactionProbeTest` si la sonde versionnée change, test d'upgrade de la migration.
- Documentation : `docs/developer/secret-redaction.md`, profil `content-vN` dans `README.md`, `docs/architecture/overview.md`, `docs/architecture/arc42/08-concepts-transverses.md`, `docs/architecture/arc42/10-exigences-qualite.md`, `docs/developer/current-limitations.md`, `docs/developer/release-and-recovery.md`, `docs/developer/semantic-search.md`, `docs/developer/openspec-traceability.md` ; contrat `scripts/release/test-operational-doc-contracts.sh`.
- Effet utilisateur : rebuild des index dérivés de tous les projets à la mise à niveau.
