# Proposition

## Why

Deux défauts de `SensitiveContentRedactor`, antérieurs à `secret-redaction-v7`, laissent passer en clair un jeton à préfixe que la politique promet de masquer ou de refuser :

1. Un jeton `sk-`, `glpat-` ou `xox?-` de plus de 255 caractères collé juste après `-----END … PRIVATE KEY-----` n'est ni refusé ni masqué. Le refus tourne sur le contenu brut, où le jeton est précédé d'un `-` ; le masquage tourne après le remplacement du bloc de clé par `[REDACTED]`, où il est précédé d'un `]`.
2. Les classes de caractères du masquage et du refus diffèrent pour `xox?-`, `gh?_` et `sk_`/`rk_` : un jeton de ces familles qui contient un `_` n'est ni masqué ni refusé.

Le jeton part alors en clair dans Lucene, les embeddings et les sorties CLI/REST/MCP. Les formats réels connus n'ont pas cette forme ; la gravité est faible, mais la garantie « jamais publié en clair » est fausse pour ces familles.

Source : revue `nexus-security-auditor` du change `sprint-08-suite-borne-sk` (`AUD-SEC-20`), sonde du 10/11/2026 (java 21, classes de `d193fb05`).

## What Changes

- [Existant vérifié] `redact` lance `OVERSIZED_TOKEN`, `OVERSIZED_VALUE` et `OVERSIZED_URI` sur `content`, puis masque sur `replacePrivateKeyBlocks(content)` (`core/src/main/java/com/nexus/security/SensitiveContentRedactor.java:105-110`).
- [Existant vérifié] Les familles à tirets sont ancrées par `(?<![A-Za-z0-9_-])` au masquage (`:47`) comme au refus (`:95`).
- [Existant vérifié] Sonde : `-----END RSA PRIVATE KEY-----` suivi de `sk-`, `sk-ant-`, `glpat-` ou `xoxb-` et d'un corps de 256 ou 300 caractères sort en clair ; un corps de 30 ou 255 caractères est masqué.
- [Existant vérifié] Masquage `xox[abposr]-[A-Za-z0-9-]{10,255}+` (`:48`) sous la garde `(?![A-Za-z0-9_-])` (`:49`), refus `[A-Za-z0-9_-]{256}` (`:96`) ; masquage `gh[pousr]_[A-Za-z0-9]{20,255}+` et `(?:sk|rk)_(?:live|test)_[A-Za-z0-9]{16,255}+` suivis de `\b` (`:45-46`), refus `\w{255}` (`:94`).
- [Existant vérifié] Sonde : `xoxb-`, `ghp_` ou `sk_live_` suivi de 25 caractères, d'un `_` et de 10 caractères sort en clair ; `github_pat_`, dont le masquage utilise déjà `\w`, est masqué.
- [Écart] `docs/developer/secret-redaction.md` et la Javadoc d'`OVERSIZED_TOKEN` annoncent que la borne de refus vaut la borne de masquage plus un sur le même corps ; ce n'est vrai ni pour les classes de `xox?-`, `gh?_`, `sk_`/`rk_`, ni pour un jeton collé à un marqueur de clé privée.
- [Exigence] Un jeton à préfixe reconnu par le masquage est refusé dans le même voisinage dès que son corps dépasse la borne, y compris collé à un bloc de clé privée.
- [Exigence] Pour chaque famille à préfixe, le masquage et le refus comptent le corps sur la même classe de caractères.
- [Exigence] La correction incrémente `POLICY_VERSION` (`secret-redaction-v8`), le profil sémantique (`content-v7`) et ajoute une migration d'invalidation.

## Type de changement

fonctionnel (politique de redaction, format des index dérivés) : profil complet.

## Périmètre et exclusions

**Dans le périmètre :**
- Ordre des contrôles de refus dans `redact`, classes de masquage de `xox?-`, `gh?_`, `sk_`/`rk_`, Javadoc d'`OVERSIZED_TOKEN`.
- Tests aux bornes 255/256 dans les deux voisinages, version de politique, profil sémantique, migration, documentation et contrats (skill `nexus-redaction-change`).

**Hors périmètre :**
- L'ancrage des familles à tirets : un jeton précédé d'un identifiant à tirets (`ma-sk-…`) reste ignoré au masquage comme au refus, par choix contre les faux positifs.
- Les formats à longueur fixe sans refus (`npm_`, `AKIA`/`ASIA`, `AIza`) et les bornes basses.

## Capabilities

### New Capabilities

### Modified Capabilities
- `secret-redaction` : ajout de deux exigences (voisinage d'un bloc de clé privée, classe de caractères commune) ; réécriture de « Un changement de politique invalide les index dérivés » pour `secret-redaction-v8`.

## Impact

- Code : `core/src/main/java/com/nexus/security/SensitiveContentRedactor.java` (`redact`, `STRUCTURED_TOKEN`, `OVERSIZED_TOKEN`, `POLICY_VERSION`), `SemanticIndexingService.CONTENT_PROFILE_VERSION`, `SchemaMigrator`.
- Schéma : migration d'invalidation `V019__invalidate_token_context_bound_indexes.sql` (numéro suivant V018 du sprint 8).
- Tests : `SensitiveContentRedactorTest`, `SecretRedactionProbeTest`, test d'upgrade de la migration, tests qui citent la version de politique ou du profil.
- Documentation : `docs/developer/secret-redaction.md`, `content-vN` dans la documentation courante, `docs/developer/release-and-recovery.md`, `docs/developer/openspec-traceability.md` ; contrat `scripts/release/test-operational-doc-contracts.sh`.
- Effet utilisateur : rebuild des index dérivés de tous les projets à la mise à niveau.
