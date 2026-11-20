# Proposition

## Why

L'audit du 16/11/2026 relève cinq défauts de redaction qui laissent des secrets en clair dans Lucene, dans les embeddings (Ollama éventuellement distant) et dans les sorties CLI, REST et MCP. Le plus grave est `AUD-SEC-01` (Élevée) : une clé sensible placée juste après la quote ouvrante d'une chaîne n'est jamais masquée. C'est un défaut d'une forme que `docs/developer/secret-redaction.md` déclare couverte (clé « éventuellement citée »). Les quatre autres (`AUD-SEC-02` Moyenne, `AUD-SEC-03`, `AUD-SEC-04`, `AUD-SEC-05` Faibles) visent des formes d'affectation courantes en Java, Kotlin, TypeScript, Python et SQL, seuls langages indexés en contenu (`core/src/main/java/com/nexus/index/SourceLanguage.java:18-24`), et des familles de secrets structurés absentes de la liste fermée.

Le sprint S3 regroupe ces corrections sous un seul passage de politique : une version, un profil sémantique, une migration, donc une seule reconstruction des index dérivés. Critère de sortie (`docs/project/audits/2026-11-16/sprints.md`, section S3) : « POLICY_VERSION vaut secret-redaction-v12 et le profil sémantique passe à content-v11. SecretRedactionProbeTest contient un cas par forme listée dans SEC-01 à SEC-05 (le test affirme leur nombre), et docs/developer/secret-redaction.md cite ces formes. Une seule migration Vnnn invalide les dérivés, avec son test d'upgrade. Le banc de débit de redaction passe son seuil dans le workflow branché au sprint 1. »

Sources : `docs/project/audits/2026-11-16/findings.md` et `findings.json` (`AUD-SEC-01` à `AUD-SEC-05`), `sprints.md`, `sprints-meta.json`, `plan-action.md`. Le code de `core/` et `adapters/` n'a pas changé depuis le commit audité `7ec5c5dc` (`git diff --stat 7ec5c5dc HEAD -- core adapters` vide au 20/11).

## What Changes

- [Existant vérifié] `AUD-SEC-01` : dans `SecretAssignmentScanner.assignmentValueStart`, quand une quote ouvrante n'est pas refermée juste après la clé, le curseur saute à la fin de la clé (`core/src/main/java/com/nexus/security/SecretAssignmentScanner.java:63-67`, `cursor = Math.max(cursor + 1, end)`). La clé n'est jamais réexaminée comme clé non citée, alors que la garde `:54-55` l'admettrait : un `"` n'est pas un `identifierCharacter` (`:92-94`). Formes en clair d'après l'audit : `String cs = "password=FAKEpass123;user=sa";`, `docker run -e "POSTGRES_PASSWORD=FAKEpass" postgres`, `- "POSTGRES_PASSWORD=FAKEpass"`, `curl -H "Authorization: token FAKEtok1234"`, `String s = "secret: FAKEsec";`, `env: ["TOKEN=FAKEtok123"]`.
- [Existant vérifié] `AUD-SEC-02` : un composant sensible n'est reconnu que comme segment entier délimité par `_`, `.` ou `-` (`SecretAssignmentScanner.java:15-18`), sans découpage camelCase. Seuls `:` ou `=` suivent la clé (`:72`). `dbPassword`, `jwtSecret`, `keystorePassword`, `myApiKey`, `awsSecretAccessKey`, `os.environ["DB_PASSWORD"] = …`, `config["password"] = …`, `props.put("password", …)`, `System.setProperty("javax.net.ssl.keyStorePassword", …)`, `CREATE USER app IDENTIFIED BY '…'` et `ALTER ROLE app WITH PASSWORD '…'` laissent la valeur en clair.
- [Existant vérifié] `AUD-SEC-03` : une valeur citée s'arrête à la première quote fermante (`SecretAssignmentScanner.java:29-36`, `quotedEnd` `:96-114`). `password = """FAKEpass123"""`, `password = "abc" + "FAKEtail123";` et `requests.get(url, auth=('user', 'FAKEpass'))` laissent tout ou partie de la valeur en clair. Une valeur non citée s'arrête à la première virgule (`scalarEnd`, `:116-126`).
- [Existant vérifié] `AUD-SEC-04` : `URI_CREDENTIAL` exige un mot de passe d'au moins 3 caractères sans `@` (`core/src/main/java/com/nexus/security/SensitiveContentRedactor.java:123-125`, `([^\s/@]{3,…}+)(@)`). `https://admin:p@ssFAKE@db.example.com/` et `https://u:ab@host/` restent inchangés.
- [Existant vérifié] `AUD-SEC-05` : `STRUCTURED_TOKENS` (`SensitiveContentRedactor.java:71-80`) ne connaît ni `hf_`, ni `SG.`, ni `ya29.`, ni l'URL de webhook Slack. `AccountKey` et `SharedAccessKey` ne sont pas des composants sensibles (`SecretAssignmentScanner.java:15-18`). `.ppk` est absent de `SENSITIVE_SUFFIXES` (`core/src/main/java/com/nexus/index/scan/ProjectIgnoreMatcher.java:80-87`).
- [Existant vérifié] Valeurs courantes : `POLICY_VERSION = "secret-redaction-v11"` (`SensitiveContentRedactor.java:21`), `CONTENT_PROFILE_VERSION = 10` (`core/src/main/java/com/nexus/search/semantic/SemanticIndexingService.java:27`, profil `content-v10`), dernière migration `V022__invalidate_end_begin_same_line_indexes.sql`, enregistrée à `core/src/main/java/com/nexus/persistence/sqlite/SchemaMigrator.java:42`.
- [Écart] Le format PuTTY est une limite documentée et figée en clair : `docs/developer/secret-redaction.md:260` et `core/src/test/java/com/nexus/security/SensitiveContentRedactorTest.java:893-900` (`#unrecognizedPrivateKeyMarkerFormsStayInClear`, cas « format PuTTY »). `AUD-SEC-05` demande de le masquer. Ce change lève la limite (voir `[Décision]` ci-dessous) et retire ce cas du test.
- [Écart] `passwordHint="ordinary value"` est figé comme non sensible (`SensitiveContentRedactorTest.java:473`, `#recognizesAllSupportedCompoundSpellingsAndKeepsKeyBoundaries`). Un découpage camelCase qui reconnaît un composant sensible n'importe où dans la clé le masquerait. L'audit demande de mesurer ces faux positifs (`passwordHint`, `tokenizer`).
- [Écart] La spec `secret-redaction › Les formats de secrets courants sont redigés` fixe une sonde de 35 cas (`SecretRedactionProbeTest.java:80-83` affirme 35, 19 et 2). Le critère de sortie impose d'y ajouter un cas par forme d'`AUD-SEC-01` à `AUD-SEC-05` : la spec est réécrite.
- [Exigence] Une clé sensible placée en tête d'une chaîne citée est redigée, et la quote qui ferme cette chaîne reste visible (`AUD-SEC-01`).
- [Exigence] Une clé camelCase qui se termine par un composant sensible est redigée (`AUD-SEC-02`).
- [Exigence] Une clé sensible écrite en indice citée (`["clé"] =`, `['clé'] =`) est redigée (`AUD-SEC-02`).
- [Exigence] Le second argument littéral d'un appel `put`, `setProperty` ou `setenv` dont le premier argument littéral est une clé sensible est redigé (`AUD-SEC-02`).
- [Exigence] Le littéral SQL qui suit `IDENTIFIED BY` ou `PASSWORD` est redigé (`AUD-SEC-02`).
- [Exigence] Une valeur triple-quotée est redigée en entier (`AUD-SEC-03`).
- [Exigence] Les littéraux enchaînés par `+` derrière une valeur sensible, et une valeur parenthésée, sont redigés jusqu'à la fin de l'expression sur la ligne (`AUD-SEC-03`).
- [Exigence] Le mot de passe d'une URL est redigé dès 1 caractère, y compris quand il contient `@` (`AUD-SEC-04`).
- [Exigence] Les clés `AccountKey` et `SharedAccessKey` sont sensibles (`AUD-SEC-05`).
- [Exigence] Les jetons `hf_`, `SG.<22>.<43>` et `ya29.` sont masqués ou refusés comme les autres jetons à préfixe (`AUD-SEC-05`).
- [Exigence] L'URL d'un webhook Slack est masquée, hôte visible (`AUD-SEC-05`).
- [Exigence] Les lignes privées d'un fichier de clé PuTTY, de `Private-Lines:` à `Private-MAC:`, sont masquées en conservant les séparateurs de lignes (`AUD-SEC-05`).
- [Exigence] `SecretRedactionProbeTest` porte un cas par forme d'`AUD-SEC-01` à `AUD-SEC-05` et affirme son nombre de cas.
- [Exigence] Le passage de politique fait passer `POLICY_VERSION` à `secret-redaction-v12` et le profil sémantique à `content-v11`, et ajoute la seule migration d'invalidation `V023`, conformément à `secret-redaction › Un changement de politique invalide les index dérivés` et au skill `nexus-redaction-change`.
- [Décision] Un seul passage de politique pour les cinq constats : une seule version (`v12`), un seul profil (`content-v11`), une seule migration (`V023`). Les corrections arrivent dans l'ordre de l'audit (`AUD-SEC-01`, puis `AUD-SEC-02`, puis `AUD-SEC-05` ; `AUD-SEC-03` et `AUD-SEC-04` sans contrainte), et la version ne change qu'une fois.
- [Décision] Le découpage camelCase ne reconnaît un composant sensible qu'en fin de clé (`dbPassword`, `jwtSecret`, `myApiKey`) : `passwordHint`, `passwordEncoder` ou `tokenService` restent intacts, comme le fige `SensitiveContentRedactorTest.java:473`. Les clés à séparateurs (`password_hint`) gardent leur règle actuelle, qui reconnaît le composant à toute position.
- [Décision] La continuation par `,` est limitée à une valeur ouverte par `(`. Au premier niveau, `{"password": "FAKEa", "token": "FAKEb"}` doit garder la clé `"token"` reconnue : avaler `, "token"` comme littéral laisserait `FAKEb` en clair.
- [Décision] Le format PuTTY quitte la liste des limites : ses lignes privées sont masquées, et `.ppk` rejoint les suffixes exclus du scan, comme `.pem` et `.key`.
- [Décision] `password: |` (scalaire YAML de bloc) et `password hunter2` (clé et valeur séparées par un blanc) restent des limites connues, épinglées `KNOWN_LIMIT` dans la sonde. L'action de l'audit ne propose pas de règle pour ces formes, et la spec épingle déjà la première (scénario « Formats hors périmètre »).
- [Décision] `hf_` et `ya29.` sont des familles bornées : leur corps suit la borne de 255 caractères déjà imposée à chaque famille bornée par `secret-redaction › Le masquage et le refus d'un jeton comptent la même classe de caractères` (motifs `SensitiveContentRedactor.java:72-80`), avec refus au-delà. `SG.` a les longueurs fixes 22 et 43 données par l'audit. L'audit ne fixe pas de longueur minimale pour `hf_` et `ya29.` (voir Questions ouvertes).

## Type de changement

fonctionnel (politique de redaction, format des index dérivés) : profil complet.

## Périmètre et exclusions

**Dans le périmètre :**
- `SecretAssignmentScanner` : réexamen d'une clé citée non refermée, découpage camelCase, indice cité, arguments de `put`/`setProperty`/`setenv`, SQL `IDENTIFIED BY` et `PASSWORD`, triple quote, continuation `+` et valeur parenthésée, composants `account_key` et `shared_access_key`.
- `SensitiveContentRedactor` : `URI_CREDENTIAL`, jetons `hf_`, `SG.`, `ya29.`, webhook Slack, bloc PuTTY.
- `ProjectIgnoreMatcher.SENSITIVE_SUFFIXES` : `.ppk`.
- Sonde `SecretRedactionProbeTest`, tests unitaires, version de politique, profil sémantique, migration `V023`, documentation et contrats (skills `nexus-redaction-change` et `nexus-schema-migration`).

**Hors périmètre :**
- Les formes que l'audit liste en clair sans proposer de règle : `new PasswordAuthentication("user", "FAKE…".toCharArray())`, `PGPASSWORD=FAKE…`, `DB_PASS=FAKE…` (voir Questions ouvertes). Le scalaire YAML de bloc `password: |` et `password hunter2` restent des limites connues.
- Le découpage camelCase des noms d'élément XML et d'option (`<dbPassword>` reste une limite documentée, `docs/developer/secret-redaction.md:262`).
- Un acronyme sans transition minuscule-majuscule (`DBPassword`) : l'audit ne demande que les transitions minuscule→majuscule.
- Le branchement et le seuil du banc `RedactionThroughputBenchmarkTest` : sprint S1 (`AUD-PRF-04`, change `audit-1116-s1-ci-et-mesures`). Ce change en dépend sans le modifier.
- La suppression de la passe de redaction redondante dans les sessions Lucene et sémantique (`AUD-PRF-03`, sprint S5) et `AUD-PRF-01` (sprint S4, qui suit le numéro de migration fixé ici).
- Une exigence OpenSpec pour les exclusions intégrées autres que `.claude/worktrees` : la `[Question]` de `docs/developer/indexing-pipeline.md:416` reste ouverte, `.ppk` est ajouté sans nouvelle exigence.

## Capabilities

### New Capabilities

### Modified Capabilities
- `secret-redaction` : douze exigences ajoutées, une par forme d'affectation ou famille de secrets d'`AUD-SEC-01` à `AUD-SEC-05`. Réécriture de « Les formats de secrets courants sont redigés » (sonde étendue, nouvelle limite `password hunter2`) et de « Un changement de politique invalide les index dérivés » (`secret-redaction-v12`).

## Impact

- Code : `core/src/main/java/com/nexus/security/SecretAssignmentScanner.java` (`assignmentValueStart`, `SENSITIVE_KEY_COMPONENT`, `quotedEnd`, `scalarEnd`, nouvelles formes) ; `core/src/main/java/com/nexus/security/SensitiveContentRedactor.java` (`POLICY_VERSION`, `URI_CREDENTIAL`, `STRUCTURED_TOKENS` et `STRUCTURED_TOKEN_PREFIXES`, webhook Slack, bloc PuTTY) ; `core/src/main/java/com/nexus/search/semantic/SemanticIndexingService.java:27` (`CONTENT_PROFILE_VERSION` de 10 à 11) ; `core/src/main/java/com/nexus/persistence/sqlite/SchemaMigrator.java:42` (enregistrement de `V023`) ; `core/src/main/java/com/nexus/index/scan/ProjectIgnoreMatcher.java:80-87` (`.ppk`).
- Schéma : `core/src/main/resources/db/migration/V023__invalidate_assignment_form_indexes.sql`, sur le modèle de `V022`.
- Tests : `SecretRedactionProbeTest`, `SensitiveContentRedactorTest` (dont `:473` et `:893-900`), test du scanner, nouveau `SchemaMigratorAssignmentFormUpgradeTest`, `SchemaMigratorChecksumTest`, tests d'upgrade qui comptent les migrations ou citent `secret-redaction-v11` ou `content-v10` (`SemanticIndexingServiceTest`), `RedactionThroughputBenchmarkTest` (il affirme autant de familles de préfixes que de motifs, `:49`), test de `ProjectIgnoreMatcher`.
- Documentation : `docs/developer/secret-redaction.md`, `docs/developer/release-and-recovery.md`, `docs/developer/indexing-pipeline.md` (`.ppk`), `docs/developer/openspec-traceability.md`. `content-v11` et `secret-redaction-v12` dans les pages qui citent aujourd'hui `content-v10` (`git grep -l content-v10` hors `docs/project` et archives : `README.md`, `docs/architecture/README.md`, `docs/architecture/overview.md`, `docs/architecture/quality/scenarios.md`, `docs/architecture/arc42/07-vue-deploiement.md`, `08-concepts-transverses.md`, `10-exigences-qualite.md`, `11-risques-dette.md`, `docs/developer/README.md`, `architecture-implementation.md`, `ci-and-supply-chain.md`, `context-building.md`, `current-limitations.md`, `release-and-recovery.md`, `semantic-search.md`). Contrat : `scripts/release/test-operational-doc-contracts.sh` (`:81`, `:92`, `:253-271`).
- Dépendance : le banc de débit de redaction branché par le sprint S1 (`AUD-PRF-04`, change `audit-1116-s1-ci-et-mesures`) mesure les nouvelles expressions ; le critère de sortie exige qu'il passe son seuil.
- ADR applicables, sans nouvel ADR : `DATA-0020` (migrations embarquées, append-only), `DATA-0073` (Lucene dérivé et reconstructible), `DATA-0074` (version de redaction dans le commit Lucene et le profil sémantique), `ARCH-0085` (une correction de redaction vaut pour CLI, REST et MCP).
- Effet utilisateur : rebuild des index dérivés de tous les projets à la mise à niveau.

## Questions ouvertes

- [Question] `new PasswordAuthentication("user", "FAKE….toCharArray())`, `PGPASSWORD=FAKE…` (clé sans séparateur ni transition camelCase) et `DB_PASS=FAKE…` (`pass` n'est pas un composant sensible) sont listés en clair par `AUD-SEC-02`, sans règle proposée par son action. Les rendre sensibles (constructeur nommé, composants `pgpassword` et `pass`) ou les épingler `KNOWN_LIMIT` ? Le nombre de cas de la sonde ne change pas, seule leur attente. `pass` seul risque des faux positifs (`passThrough`, `bypass`) qui restent à mesurer.
- [Question] Longueur minimale du corps de `hf_` et de `ya29.` : l'audit n'en donne pas. Faut-il reprendre le minimum de 20 caractères de `sk-`, `glpat-` et `gh?_` (`SensitiveContentRedactor.java:72-77`), ou une valeur sourcée par le format publié du fournisseur ?
