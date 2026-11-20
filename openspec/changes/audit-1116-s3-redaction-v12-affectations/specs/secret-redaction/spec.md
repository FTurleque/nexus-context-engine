# Spec Delta

## ADDED Requirements

### Requirement: Une clé sensible placée en tête d'une chaîne citée est redigée
Quand une clé sensible suivie de `:` ou `=` commence juste après la quote ouvrante d'une chaîne qui ne se referme pas après la clé, la redaction SHALL remplacer la valeur par `[REDACTED]`, comme pour la même clé non citée. La valeur s'arrête au plus tard à la quote non échappée qui ferme la chaîne, et cette quote reste visible. Une clé citée suivie de sa quote fermante garde son traitement actuel.

#### Scenario: Six formes de l'audit
- **GIVEN** les contenus `String cs = "password=FAKEpass123;user=sa";`, `docker run -e "POSTGRES_PASSWORD=FAKEpass" postgres`, `- "POSTGRES_PASSWORD=FAKEpass"`, `curl -H "Authorization: token FAKEtok1234"`, `String s = "secret: FAKEsec";` et `env: ["TOKEN=FAKEtok123"]`
- **WHEN** chacun est redigé
- **THEN** aucune des valeurs factices n'apparaît dans le texte rendu et chaque clé reste visible

#### Scenario: Quote fermante conservée
- **GIVEN** le contenu `docker run -e "POSTGRES_PASSWORD=FAKEpass" postgres`
- **WHEN** il est redigé
- **THEN** le texte rendu vaut `docker run -e "POSTGRES_PASSWORD=[REDACTED]" postgres`

#### Scenario: Clés citées JSON inchangées
- **GIVEN** le contenu `{"password": "FAKEa", "token": "FAKEb"}`
- **WHEN** il est redigé
- **THEN** le texte rendu vaut `{"password": "[REDACTED]", "token": "[REDACTED]"}`

### Requirement: Une clé camelCase qui se termine par un composant sensible est redigée
Une clé d'affectation SHALL être sensible quand ses derniers segments, découpés aux transitions d'une minuscule vers une majuscule, forment un composant sensible. Un composant sensible suivi d'un autre segment camelCase SHALL ne pas rendre la clé sensible. Les clés à séparateurs `_`, `.` ou `-` gardent leur règle.

#### Scenario: Champs camelCase de l'audit
- **GIVEN** les affectations `String dbPassword = "FAKEdb1";`, `private String jwtSecret = "FAKEjwt1";`, `String keystorePassword = "FAKEks1";`, `String myApiKey = "FAKEapi1";` et `awsSecretAccessKey = "FAKEaws1"`
- **WHEN** chacune est redigée
- **THEN** chaque valeur est remplacée par `[REDACTED]` et le nom du champ reste visible

#### Scenario: Composant sensible suivi d'un autre segment
- **GIVEN** les affectations `passwordHint="ordinary value"`, `passwordEncoder = "bcrypt"`, `tokenService = "svc"`, `maxTokens = 4096` et `Tokenizer tokenizer = new Tokenizer();`
- **WHEN** chacune est redigée
- **THEN** le texte rendu est identique au contenu

### Requirement: Une clé sensible écrite en indice cité est redigée
Une clé sensible écrite entre crochets comme littéral cité (`["clé"]` ou `['clé']`) et suivie de `=` SHALL voir sa valeur remplacée par `[REDACTED]`. L'indice reste visible.

#### Scenario: Indice de dictionnaire
- **GIVEN** les contenus `os.environ["DB_PASSWORD"] = "FAKEenv1"`, `config["password"] = "FAKEcfg1"` et `config['password'] = 'FAKEcfg2'`
- **WHEN** chacun est redigé
- **THEN** aucune valeur factice n'apparaît dans le texte rendu et chaque indice reste visible

#### Scenario: Indice non sensible
- **GIVEN** le contenu `config["timeout"] = "30"`
- **WHEN** il est redigé
- **THEN** le texte rendu est identique au contenu

### Requirement: Le second argument d'un appel put, setProperty ou setenv à clé sensible est redigé
Pour un appel `put`, `setProperty` ou `setenv` dont le premier argument est un littéral cité formant une clé sensible, la redaction SHALL remplacer par `[REDACTED]` le contenu du second argument quand c'est un littéral cité, quotes conservées. Un appel dont le premier argument n'est pas une clé sensible reste inchangé.

#### Scenario: Appels de l'audit
- **GIVEN** les contenus `props.put("password", "FAKEput1")`, `System.setProperty("javax.net.ssl.keyStorePassword", "FAKEprop1")` et `setenv("API_TOKEN", "FAKEenv2", 1)`
- **WHEN** chacun est redigé
- **THEN** aucune valeur factice n'apparaît dans le texte rendu et le premier argument reste visible

#### Scenario: Premier argument non sensible
- **GIVEN** le contenu `props.put("timeout", "30")`
- **WHEN** il est redigé
- **THEN** le texte rendu est identique au contenu

### Requirement: Le littéral SQL d'un mot de passe est redigé
Le littéral entre apostrophes qui suit `IDENTIFIED BY` ou le mot `PASSWORD`, sans distinction de casse et séparé par des blancs, SHALL être remplacé par `[REDACTED]`, apostrophes conservées.

#### Scenario: Création et modification d'un rôle
- **GIVEN** les contenus `CREATE USER app IDENTIFIED BY 'FAKEsql1';` et `ALTER ROLE app WITH PASSWORD 'FAKEsql2';`
- **WHEN** chacun est redigé
- **THEN** les textes rendus valent `CREATE USER app IDENTIFIED BY '[REDACTED]';` et `ALTER ROLE app WITH PASSWORD '[REDACTED]';`

### Requirement: Une valeur sensible triple-quotée est redigée en entier
Une valeur sensible ouverte par `"""` ou `'''` SHALL être remplacée par `[REDACTED]` jusqu'au même délimiteur sur sa ligne, délimiteurs conservés. Sans délimiteur fermant sur la ligne, la valeur est masquée jusqu'à la fin de la ligne.

#### Scenario: Chaîne Python triple-quotée
- **GIVEN** le contenu `password = """FAKEpass123"""`
- **WHEN** il est redigé
- **THEN** le texte rendu vaut `password = """[REDACTED]"""`

#### Scenario: Délimiteur absent de la ligne
- **GIVEN** le contenu `password = '''FAKEpass123` suivi d'une ligne `visible`
- **WHEN** il est redigé
- **THEN** `FAKEpass123` n'apparaît pas dans le texte rendu et la ligne `visible` est conservée

### Requirement: Une valeur sensible concaténée ou parenthésée est redigée jusqu'à la fin de l'expression
Après une valeur sensible citée, chaque `+` suivi sur la même ligne d'un littéral cité SHALL étendre le masquage à ce littéral. Une valeur sensible ouverte par `(` SHALL être masquée jusqu'à la `)` correspondante sur sa ligne, ou jusqu'à la fin de la ligne. Une virgule hors parenthèse de la valeur SHALL terminer la valeur, comme aujourd'hui.

#### Scenario: Concaténation
- **GIVEN** le contenu `password = "abc" + "FAKEtail123";`
- **WHEN** il est redigé
- **THEN** ni `abc` ni `FAKEtail123` n'apparaissent dans le texte rendu et le `;` final est conservé

#### Scenario: Tuple d'authentification
- **GIVEN** le contenu `requests.get(url, auth=('user', 'FAKEpass'))`
- **WHEN** il est redigé
- **THEN** `FAKEpass` n'apparaît pas dans le texte rendu et `requests.get(url, auth=` reste visible

#### Scenario: Virgule de premier niveau
- **GIVEN** le contenu `{"password": "FAKEa", "token": "FAKEb"}`
- **WHEN** il est redigé
- **THEN** la clé `"token"` reste visible et `FAKEb` n'apparaît pas dans le texte rendu

### Requirement: Le mot de passe d'une URL est redigé quels que soient sa longueur et ses @
Dans une URL `scheme://utilisateur:motdepasse@hôte`, la redaction SHALL remplacer par `[REDACTED]` le mot de passe dès 1 caractère, jusqu'au dernier `@` qui précède le premier `/`, `?`, `#` ou blanc de l'autorité. L'utilisateur et l'hôte restent visibles. La borne de 4 096 caractères de la valeur est inchangée.

#### Scenario: Mot de passe contenant @
- **GIVEN** le contenu `https://admin:p@ssFAKE@db.example.com/`
- **WHEN** il est redigé
- **THEN** le texte rendu vaut `https://admin:[REDACTED]@db.example.com/`

#### Scenario: Mot de passe court
- **GIVEN** les contenus `https://u:ab@host/` et `https://u:a@host/`
- **WHEN** chacun est redigé
- **THEN** les textes rendus valent `https://u:[REDACTED]@host/`

#### Scenario: @ dans le chemin
- **GIVEN** le contenu `https://u:FAKEpw@host/path@v2`
- **WHEN** il est redigé
- **THEN** le texte rendu vaut `https://u:[REDACTED]@host/path@v2`

### Requirement: Les clés AccountKey et SharedAccessKey sont sensibles
Les composants `account_key` et `shared_access_key`, avec séparateurs facultatifs comme les autres composants composés, SHALL être des composants de clé sensibles. La valeur d'une affectation `AccountKey=` ou `SharedAccessKey=` dans une chaîne de connexion est masquée jusqu'au `;` suivant.

#### Scenario: Chaînes de connexion Azure
- **GIVEN** les contenus `DefaultEndpointsProtocol=https;AccountName=acct;AccountKey=FAKEkey0==;EndpointSuffix=core.windows.net` et `Endpoint=sb://ns.servicebus.windows.net/;SharedAccessKeyName=root;SharedAccessKey=FAKEsak0=`
- **WHEN** chacun est redigé
- **THEN** ni `FAKEkey0==` ni `FAKEsak0=` n'apparaissent dans le texte rendu, et `AccountName=acct` et `EndpointSuffix=core.windows.net` restent visibles

### Requirement: Les jetons hf_, SG. et ya29. sont masqués ou refusés
Un jeton `hf_`, un jeton `SG.` suivi de 22 caractères, d'un `.` et de 43 caractères, ou un jeton `ya29.` SHALL être remplacé par `[REDACTED]`. Les familles `hf_` et `ya29.` SHALL appliquer la borne de corps des jetons à préfixe : masquage jusqu'à 255 caractères de corps, refus du contenu au-delà, sans publier le jeton.

#### Scenario: Jetons nominaux
- **GIVEN** des contenus portant un jeton factice `hf_`, `SG.` ou `ya29.` de longueur nominale, assemblé à l'exécution du test
- **WHEN** chacun est redigé
- **THEN** le jeton est remplacé par `[REDACTED]`

#### Scenario: Corps trop long
- **GIVEN** un contenu portant `hf_` ou `ya29.` suivi de 256 caractères de corps
- **WHEN** il est redigé
- **THEN** la redaction est refusée et le message d'erreur ne contient aucune partie du jeton

### Requirement: L'URL d'un webhook Slack est masquée
Dans une URL `https://hooks.slack.com/services/…`, la redaction SHALL remplacer par `[REDACTED]` le chemin qui suit `/services/`, jusqu'au premier blanc, quote ou fin de ligne. Le schéma et l'hôte restent visibles.

#### Scenario: Webhook dans du code
- **GIVEN** le contenu `url = "https://hooks.slack.com/services/TFAKE0001/BFAKE0001/FAKEhookSecret0001"` placé dans un fichier JavaScript
- **WHEN** il est redigé
- **THEN** `FAKEhookSecret0001` n'apparaît pas dans le texte rendu et `https://hooks.slack.com/services/` reste visible

### Requirement: Les lignes privées d'une clé PuTTY sont masquées
Dans un contenu qui porte une ligne `PuTTY-User-Key-File-`, les lignes comprises entre une ligne `Private-Lines:` et la ligne `Private-MAC:` suivante SHALL être remplacées par `[REDACTED]`, ligne `Private-MAC:` comprise, en conservant chaque séparateur de lignes. Sans ligne `Private-MAC:`, le masquage va jusqu'à la fin du contenu. Les lignes publiques restent visibles.

#### Scenario: Fichier PuTTY dans un README
- **GIVEN** un bloc `PuTTY-User-Key-File-3: ssh-rsa`, `Encryption: none`, `Comment: fixture`, `Public-Lines: 1`, une ligne publique, `Private-Lines: 1`, un corps factice et `Private-MAC: 00`
- **WHEN** le contenu est redigé, avec des séparateurs LF, CRLF ou CR
- **THEN** le corps factice n'apparaît pas dans le texte rendu, la ligne publique reste visible et chaque CR et LF du contenu est conservé

#### Scenario: Ligne Private-MAC absente
- **GIVEN** le même bloc sans ligne `Private-MAC:`
- **WHEN** le contenu est redigé
- **THEN** le corps factice n'apparaît pas dans le texte rendu et la ligne publique reste visible

## MODIFIED Requirements

### Requirement: Les formats de secrets courants sont redigés
La redaction SHALL masquer la valeur des 19 formats retenus par l'audit : en-tête `Authorization: Bearer`, assignations `token=`, `"token": "…"`, `private_key:`, `pwd=`, `credentials:`, `secretKey = "…"`, `access_key=`, `auth=`, option `--password`, balise `<password>…</password>`, jetons `sk-`, `sk_live_`, `xoxb-`, `AIza`, `glpat-`, `npm_`, identifiant dans une URL `https://<jeton>@hôte`, et bloc `PGP PRIVATE KEY BLOCK`.

#### Scenario: Sonde versionnée
- **WHEN** le test de sonde de 69 cas s'exécute
- **THEN** aucun des 19 formats retenus ne laisse sa valeur en clair dans le texte redigé
- **AND** le test affirme 69 cas, dont un par forme listée dans les constats `AUD-SEC-01` à `AUD-SEC-05` de l'audit du 16/11/2026

#### Scenario: Cas négatifs
- **WHEN** le texte contient des identifiants ordinaires comme `tokenizer`, `author` ou `auth()` sans valeur assignée
- **THEN** le texte est inchangé

#### Scenario: Formats hors périmètre
- **WHEN** la sonde contient un scalaire YAML de bloc `password: |`, une valeur placée sur la ligne suivant sa clé, ou une clé séparée de sa valeur par un blanc (`password hunter2`)
- **THEN** le test les épingle comme limite connue, conformément à `docs/developer/secret-redaction.md`

### Requirement: Un changement de politique invalide les index dérivés
Toute modification du comportement de redaction SHALL incrémenter `SensitiveContentRedactor.POLICY_VERSION` et le profil sémantique `content-vN`, et SHALL être accompagnée d'une migration qui repasse les projets indexés en `NOT_INDEXED`.

#### Scenario: Mise à jour d'une base existante
- **GIVEN** une base migrée avec la politique `secret-redaction-v11`
- **WHEN** la nouvelle version l'ouvre
- **THEN** la migration d'invalidation incrémente la génération d'index des projets, les repasse en `NOT_INDEXED` et conserve le registre des projets

#### Scenario: Index lexical d'une ancienne politique
- **GIVEN** un index Lucene dont la version de politique diffère de `secret-redaction-v12`
- **WHEN** l'indexation d'un projet vérifie la présence de l'index lexical
- **THEN** il est traité comme invalide et reconstruit
