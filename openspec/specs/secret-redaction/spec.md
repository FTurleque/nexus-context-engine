# secret-redaction Specification

## Purpose
Définit les formats de secrets que NEXUS redige avant indexation, embedding et sortie, le comportement face à une valeur trop longue, et l'invalidation des index dérivés quand la politique change.
Documentation durable : docs/developer/secret-redaction.md#openspec-secret-redaction

## Requirements

### Requirement: Les formats de secrets courants sont redigés
La redaction SHALL masquer la valeur des 19 formats retenus par l'audit : en-tête `Authorization: Bearer`, assignations `token=`, `"token": "…"`, `private_key:`, `pwd=`, `credentials:`, `secretKey = "…"`, `access_key=`, `auth=`, option `--password`, balise `<password>…</password>`, jetons `sk-`, `sk_live_`, `xoxb-`, `AIza`, `glpat-`, `npm_`, identifiant dans une URL `https://<jeton>@hôte`, et bloc `PGP PRIVATE KEY BLOCK`.

#### Scenario: Sonde versionnée
- **WHEN** le test de sonde de 35 cas s'exécute
- **THEN** aucun des 19 formats retenus ne laisse sa valeur en clair dans le texte redigé

#### Scenario: Cas négatifs
- **WHEN** le texte contient des identifiants ordinaires comme `tokenizer`, `author` ou `auth()` sans valeur assignée
- **THEN** le texte est inchangé

#### Scenario: Formats hors périmètre
- **WHEN** la sonde contient un scalaire YAML de bloc `password: |` ou une valeur placée sur la ligne suivant sa clé
- **THEN** le test les épingle comme limite connue, conformément à `docs/developer/secret-redaction.md`

### Requirement: Une valeur sensible trop longue n'exclut que son fichier
Quand la redaction refuse le contenu d'un fichier parce qu'une valeur sensible dépasse la borne de son motif (4 096 caractères pour une assignation, une option, un élément XML ou un jeton `Bearer` ; les bornes étroites de l'exigence « Les bornes étroites de redaction refusent le contenu »), NEXUS SHALL exclure ce seul fichier des index lexical et sémantique, SHALL le conserver dans l'index structurel, SHALL ajouter au rapport un diagnostic public `INDEX_REDACTION_LIMIT` sans valeur ni contenu, et SHALL poursuivre l'indexation des autres fichiers.

#### Scenario: Indexation d'un projet
- **WHEN** un projet de trois fichiers contient un fichier avec `password = "` suivi de 5 000 caractères
- **THEN** l'indexation réussit, les deux autres fichiers sont recherchables et le rapport signale l'exclusion du fichier fautif

#### Scenario: Fichier conservé dans l'index structurel
- **GIVEN** un fichier exclu parce qu'il porte une valeur sensible trop longue
- **WHEN** l'indexation se termine
- **THEN** le fichier reste enregistré dans l'index structurel
- **AND** la recherche lexicale ne renvoie pas son contenu

#### Scenario: Aucune fuite
- **GIVEN** un fichier exclu parce qu'il porte une valeur sensible trop longue
- **WHEN** l'index lexical, l'index sémantique et un contexte sont construits
- **THEN** ni l'index lexical, ni l'index sémantique, ni un contexte ne contient sa valeur

#### Scenario: Construction de contexte
- **GIVEN** un contexte qui inclurait un fragment portant une valeur sensible de plus de 4 096 caractères
- **WHEN** le contexte est construit
- **THEN** ce fragment est écarté et le contexte est construit avec les autres fragments

#### Scenario: Valeur à la borne
- **GIVEN** une assignation sensible dont la valeur compte exactement 4 096 caractères
- **WHEN** le texte est redigé
- **THEN** la valeur est masquée par `[REDACTED]` et le contenu n'est pas refusé
- **AND** la même assignation avec une valeur de 4 097 caractères est refusée et son contenu n'est pas publié

### Requirement: Un changement de politique invalide les index dérivés
Toute modification du comportement de redaction SHALL incrémenter `SensitiveContentRedactor.POLICY_VERSION` et le profil sémantique `content-vN`, et SHALL être accompagnée d'une migration qui repasse les projets indexés en `NOT_INDEXED`.

#### Scenario: Mise à jour d'une base existante
- **GIVEN** une base migrée avec la politique `secret-redaction-v10`
- **WHEN** la nouvelle version l'ouvre
- **THEN** la migration d'invalidation incrémente la génération d'index des projets, les repasse en `NOT_INDEXED` et conserve le registre des projets

#### Scenario: Index lexical d'une ancienne politique
- **GIVEN** un index Lucene dont la version de politique diffère de `secret-redaction-v11`
- **WHEN** l'indexation d'un projet vérifie la présence de l'index lexical
- **THEN** il est traité comme invalide et reconstruit

### Requirement: Chaque document est redigé une fois avant l'embedding
L'indexation sémantique SHALL rediger chaque document une seule fois avant normalisation, troncature et envoi au fournisseur d'embeddings.

#### Scenario: Document envoyé au fournisseur
- **GIVEN** un document contenant un secret
- **WHEN** il est préparé pour l'embedding
- **THEN** le texte envoyé est redigé et la redaction n'a été appliquée qu'une fois au document

### Requirement: Les bornes étroites de redaction refusent le contenu
La redaction SHALL refuser le contenu qui porte une partie utilisateur d'URL de plus de 1 024 caractères ou un jeton à préfixe fixe dont le corps dépasse la borne de son motif, sans le tronquer ni le publier en clair.

#### Scenario: Partie utilisateur d'URL trop longue
- **GIVEN** un contenu contenant `https://` suivi de 1 025 caractères d'identifiant puis `@hôte`
- **WHEN** le contenu est redigé
- **THEN** la redaction est refusée et le message d'erreur ne contient aucune partie de la valeur
- **AND** aucun contenu partiellement redigé n'est publié

#### Scenario: Partie utilisateur d'URL à la borne
- **GIVEN** un contenu contenant `https://` suivi de 1 024 caractères d'identifiant puis `@hôte`
- **WHEN** le contenu est redigé
- **THEN** la valeur est masquée par `[REDACTED]`

#### Scenario: Jeton à préfixe trop long
- **GIVEN** un contenu contenant un jeton `ghp_` suivi de 256 caractères alphanumériques
- **WHEN** le contenu est redigé
- **THEN** la redaction est refusée et le message d'erreur ne contient aucune partie du jeton

### Requirement: Un jeton sk- est masqué ou refusé à toute longueur de corps
Pour un jeton `sk-`, `sk-proj-` ou `sk-ant-`, la redaction SHALL masquer un corps, compté après le préfixe, de 20 à 255 caractères et SHALL refuser le contenu dès 256 caractères de corps, sans jamais publier le jeton en clair.

#### Scenario: Corps de 255 caractères
- **GIVEN** un contenu contenant `sk-` suivi de 255 caractères alphanumériques
- **WHEN** le contenu est redigé
- **THEN** le jeton est remplacé par `[REDACTED]`

#### Scenario: Corps de 256 caractères
- **GIVEN** un contenu contenant `sk-` suivi de 256 caractères alphanumériques
- **WHEN** le contenu est redigé
- **THEN** la redaction est refusée et le message d'erreur ne contient aucune partie du jeton

#### Scenario: Préfixe sk-ant- à la borne
- **GIVEN** un contenu contenant `sk-ant-` suivi de 255 caractères, puis un autre contenu avec `sk-ant-` suivi de 256 caractères
- **WHEN** chacun est redigé
- **THEN** le premier jeton est masqué et le second contenu est refusé

#### Scenario: Préfixe sk-proj- à la borne
- **GIVEN** un contenu contenant `sk-proj-` suivi de 255 caractères, puis un autre contenu avec `sk-proj-` suivi de 256 caractères
- **WHEN** chacun est redigé
- **THEN** le premier jeton est masqué et le second contenu est refusé

#### Scenario: Fenêtre sans fuite
- **WHEN** des contenus portant `sk-` suivi de 256 à 260 caractères sont redigés
- **THEN** chacun est refusé et aucun n'est publié en clair

### Requirement: Un jeton collé à un bloc de clé privée est masqué ou refusé
Pour un jeton à préfixe placé juste après la ligne de fin d'un bloc de clé privée, sans séparateur, la redaction SHALL masquer un corps de 255 caractères au plus et SHALL refuser le contenu dès 256 caractères de corps, comme pour le même jeton isolé.

#### Scenario: Corps de 255 caractères après le bloc
- **GIVEN** un bloc de clé privée complet suivi immédiatement de `sk-`, `glpat-` ou `xoxb-` et d'un corps de 255 caractères
- **WHEN** le contenu est redigé
- **THEN** le bloc et le jeton sont remplacés par `[REDACTED]`
- **AND** aucun fragment du corps n'apparaît dans le contenu publié

#### Scenario: Corps de 256 caractères après le bloc
- **GIVEN** un bloc de clé privée complet suivi immédiatement de `sk-`, `sk-ant-`, `glpat-` ou `xoxb-` et d'un corps de 256 caractères
- **WHEN** le contenu est redigé
- **THEN** la redaction est refusée et le message d'erreur ne contient aucune partie du jeton

### Requirement: Le masquage et le refus d'un jeton comptent la même classe de caractères
Pour chaque famille de jetons à préfixe dont la longueur est bornée, la redaction SHALL compter le corps sur la même classe de caractères au masquage et au refus, de sorte qu'un corps de 255 caractères au plus est masqué et qu'un corps de 256 caractères ou plus est refusé, y compris quand il contient un `_`.

#### Scenario: Jeton court contenant un tiret bas
- **GIVEN** un contenu contenant `xoxb-`, `ghp_` ou `sk_live_` suivi de 25 caractères, d'un `_` et de 10 caractères
- **WHEN** le contenu est redigé
- **THEN** le jeton entier est remplacé par `[REDACTED]`

#### Scenario: Corps de 255 caractères contenant un tiret bas
- **GIVEN** un contenu contenant `xoxb-`, `ghp_` ou `sk_live_` suivi d'un corps de 255 caractères dont un `_`
- **WHEN** le contenu est redigé
- **THEN** le jeton entier est remplacé par `[REDACTED]`

#### Scenario: Corps de 256 caractères contenant un tiret bas
- **GIVEN** un contenu contenant `xoxb-`, `ghp_` ou `sk_live_` suivi d'un corps de 256 caractères dont un `_`
- **WHEN** le contenu est redigé
- **THEN** la redaction est refusée et le message d'erreur ne contient aucune partie du jeton

### Requirement: Une répétition forgée de débuts de JWT ou de CDATA ne bloque pas la redaction
La redaction d'un contenu de 1 Mo formé de la répétition de `eyJ-`, ou de la répétition de `<password><![CDATA[`, SHALL se terminer en moins d'une seconde et SHALL rendre ce contenu inchangé, sans le refuser.

#### Scenario: Répétition de débuts de JWT
- **GIVEN** un contenu formé de 262 144 répétitions de `eyJ-` (1 Mo)
- **WHEN** le contenu est redigé
- **THEN** la redaction se termine en moins d'une seconde
- **AND** le texte rendu est identique au contenu, sans `OversizedSecretException`

#### Scenario: Répétition d'ouvertures CDATA
- **GIVEN** un contenu de 1 Mo formé de la répétition de `<password><![CDATA[`, sans `]]>` ni élément fermant
- **WHEN** le contenu est redigé
- **THEN** la redaction se termine en moins d'une seconde
- **AND** le texte rendu est identique au contenu, sans `OversizedSecretException`

#### Scenario: CDATA légitime contenant un chevron
- **GIVEN** un contenu `<password><![CDATA[a<b-secret]]></password>`
- **WHEN** le contenu est redigé
- **THEN** le texte rendu est `<password>[REDACTED]</password>`

### Requirement: Un JWT reste masqué quel que soit le séparateur qui le précède
Un JWT, formé de trois segments base64url séparés par `.` dont le premier commence par `eyJ`, SHALL être remplacé par `[REDACTED]` lorsqu'il est précédé d'un début de texte, d'une espace, d'un `=`, d'un `:` ou d'un `-`.

#### Scenario: JWT isolé
- **GIVEN** un contenu `jwt ` suivi d'un JWT de trois segments de 10 caractères ou plus
- **WHEN** le contenu est redigé
- **THEN** le texte rendu est `jwt [REDACTED]`

#### Scenario: JWT après un tiret
- **GIVEN** un contenu `id-` suivi immédiatement d'un JWT de trois segments de 10 caractères ou plus
- **WHEN** le contenu est redigé
- **THEN** le texte rendu est `id-[REDACTED]`
- **AND** aucun segment du JWT n'apparaît dans le texte rendu

### Requirement: Un élément XML au nom composé est redigé
Un élément XML sur une ligne, dont le nom se termine par une clé sensible placée en début de nom ou après `.`, `-` ou `_`, SHALL voir sa valeur ou son CDATA remplacé par `[REDACTED]`. Si cette valeur dépasse 4 096 caractères, la redaction SHALL refuser le contenu sans publier la valeur.

#### Scenario: Nom composé par un tiret ou un tiret bas
- **GIVEN** les contenus `<db-password>v</db-password>`, `<db_password>v</db_password>`, `<my-token>v</my-token>` et `<app.db-password>v</app.db-password>`, où `v` vaut `hunter2-value`
- **WHEN** chacun est redigé
- **THEN** la valeur de chaque élément est remplacée par `[REDACTED]` et les balises sont conservées

#### Scenario: Clé sensible contenant un séparateur
- **GIVEN** les éléments `<api_key>`, `<api-key>`, `<access_key>`, `<private_key>`, `<client_secret>` et `<db.password>` portant chacun la valeur `hunter2-value`
- **WHEN** chacun est redigé
- **THEN** la valeur de chaque élément est remplacée par `[REDACTED]`

#### Scenario: Nom qui ne se termine pas par une clé sensible
- **GIVEN** les éléments `<tokenizer>`, `<mytoken>` et `<password_hint>` portant chacun une valeur
- **WHEN** chacun est redigé
- **THEN** le texte rendu est identique au contenu

#### Scenario: Valeur d'un nom composé au-delà de la borne
- **GIVEN** un élément `<db-password>` dont la valeur compte 4 097 caractères, puis le même élément avec 4 096 caractères
- **WHEN** chacun est redigé
- **THEN** le premier contenu est refusé et le message d'erreur ne contient aucune partie de la valeur
- **AND** la valeur du second est remplacée par `[REDACTED]`

### Requirement: Une option de ligne de commande au nom composé est redigée
Une option `-` ou `--` dont le nom se termine par une clé sensible placée en début de nom ou après un `-` SHALL voir sa valeur, séparée par des espaces, remplacée par `[REDACTED]`.

#### Scenario: Clé sensible composée dans une option
- **GIVEN** les contenus `--api-key hunter2-value`, `--private-key hunter2-value`, `--access-key hunter2-value` et `--db-password hunter2-value`
- **WHEN** chacun est redigé
- **THEN** chaque valeur est remplacée par `[REDACTED]` et le nom de l'option est conservé

#### Scenario: Option qui ne se termine pas par une clé sensible
- **GIVEN** les contenus `--password-file conf.txt` et `--tokenizer simple`
- **WHEN** chacun est redigé
- **THEN** le texte rendu est identique au contenu

### Requirement: Un marqueur BEGIN qui n'ouvre pas une clé privée ne fait pas sauter le bloc suivant
Un bloc de clé privée complet, PGP compris, SHALL être remplacé par `[REDACTED]` en conservant ses séparateurs de lignes quand un marqueur `-----BEGIN ` qui n'ouvre pas une clé privée le précède : marqueur incomplet, littéral de code ou marqueur complet d'un autre type collé au sien. Un contenu sans bloc de clé privée SHALL rester inchangé par le remplacement des blocs.

#### Scenario: Ligne BEGIN incomplète avant un bloc RSA
- **GIVEN** un contenu formé d'une ligne `-----BEGIN `, puis d'un bloc `RSA PRIVATE KEY` complet dont le corps vaut `MIIfakebodyNOTAKEY0123`
- **WHEN** le contenu est redigé
- **THEN** le corps n'apparaît pas dans le texte rendu et le bloc est remplacé par `[REDACTED]`
- **AND** le texte rendu compte autant de séparateurs de lignes que le contenu

#### Scenario: Littéral de code avant un bloc RSA
- **GIVEN** un contenu formé de la ligne `if (line.startsWith("-----BEGIN ")) {}`, puis d'un bloc `RSA PRIVATE KEY` complet
- **WHEN** le contenu est redigé
- **THEN** la ligne de code est conservée et le corps du bloc n'apparaît pas dans le texte rendu

#### Scenario: Marqueur d'un autre type sans tirets de fin sur sa ligne
- **GIVEN** un contenu formé d'une ligne `-----BEGIN CERT`, puis d'un bloc `RSA PRIVATE KEY` complet
- **WHEN** le contenu est redigé
- **THEN** le corps du bloc n'apparaît pas dans le texte rendu

#### Scenario: Marqueur complet d'un autre type collé au vrai marqueur
- **GIVEN** un contenu dont la première ligne est `-----BEGIN CERTIFICATE-----BEGIN RSA PRIVATE KEY-----`, les deux marqueurs se terminant sur cette ligne, suivie d'un corps et de la ligne `-----END RSA PRIVATE KEY-----`
- **WHEN** le contenu est redigé
- **THEN** le corps n'apparaît pas dans le texte rendu

#### Scenario: Bloc PGP après une ligne BEGIN incomplète
- **GIVEN** un contenu formé d'une ligne `-----BEGIN `, puis d'un bloc `PGP PRIVATE KEY BLOCK` complet
- **WHEN** le contenu est redigé
- **THEN** le corps du bloc n'apparaît pas dans le texte rendu

#### Scenario: Marqueur incomplet sans bloc de clé privée
- **GIVEN** le contenu `x -----BEGIN foo` suivi d'une ligne `bar`
- **WHEN** le contenu est redigé
- **THEN** le texte rendu est identique au contenu, sans `OversizedSecretException`

### Requirement: La fin d'un marqueur BEGIN est cherchée sur sa ligne
Un marqueur `-----BEGIN ` SHALL n'ouvrir une clé privée que si ses tirets de fin `-----` se trouvent avant le premier CR ou LF qui le suit. Un marqueur dont la fin n'est pas sur sa ligne SHALL ne déclencher aucun masquage au-delà de sa ligne, quel que soit le séparateur (LF, CRLF ou CR).

#### Scenario: Marqueur incomplet suivi plus loin d'un suffixe de clé
- **GIVEN** un contenu formé d'une ligne `-----BEGIN `, puis d'une ligne `"PRIVATE KEY-----"`, puis d'une ligne `visible`, sans ligne `-----END `
- **WHEN** le contenu est redigé
- **THEN** le texte rendu est identique au contenu et ne contient pas `[REDACTED]`

#### Scenario: Suffixe de clé plus loin puis vrai bloc
- **GIVEN** le contenu du scénario précédent, suivi d'un bloc `RSA PRIVATE KEY` complet dont le corps vaut `MIIfakebodyNOTAKEY0123`
- **WHEN** le contenu est redigé
- **THEN** les trois premières lignes sont rendues inchangées, et le bloc seul est remplacé par `[REDACTED]`
- **AND** le corps n'apparaît pas dans le texte rendu et le texte rendu compte autant de séparateurs de lignes que le contenu

#### Scenario: Séparateurs CRLF et CR
- **GIVEN** le contenu de « Suffixe de clé plus loin puis vrai bloc », dont chaque séparateur est CRLF, ou chaque séparateur est CR
- **WHEN** le contenu est redigé
- **THEN** les trois premières lignes sont rendues inchangées, le corps n'apparaît pas dans le texte rendu, et chaque CR et LF du contenu est conservé

#### Scenario: Marqueur coupé sur deux lignes
- **GIVEN** un bloc dont le marqueur d'ouverture est coupé sur deux lignes : concaténation Java `"-----BEGIN RSA " +` puis `"PRIVATE KEY-----\n" +`, chaîne Python implicite, ou coupure par CRLF, avec un corps factice `MIIfakebodyNOTAKEY0123` et une ligne de fin
- **WHEN** le contenu est redigé
- **THEN** le texte rendu est identique au contenu : ce marqueur n'ouvre pas de clé, limite connue et documentée

### Requirement: Un marqueur de clé privée est reconnu quelles que soient sa casse et ses blancs
Les mots `BEGIN`, `END` et le type d'un marqueur de clé privée SHALL être comparés sans tenir compte de la casse ASCII, et toute suite d'espaces, de tabulations ou d'espaces insécables (U+00A0) SHALL valoir un seul blanc après `BEGIN` ou `END` et entre les mots du type. La reconnaissance SHALL garder un coût linéaire et la règle de la fin du marqueur sur sa ligne.

#### Scenario: Marqueurs en minuscules
- **GIVEN** un bloc dont les marqueurs sont `-----begin rsa private key-----` et `-----end rsa private key-----`, avec le corps `MIIfakebodyNOTAKEY0123`
- **WHEN** le contenu est redigé
- **THEN** le bloc est remplacé par `[REDACTED]`, le corps n'apparaît pas dans le texte rendu et chaque CR et LF du contenu est conservé

#### Scenario: Tabulation ou espace insécable comme blanc
- **GIVEN** un bloc dont le marqueur d'ouverture porte une tabulation après `BEGIN`, ou une espace insécable entre les mots du type, et dont la ligne de fin porte le même type avec des espaces
- **WHEN** le contenu est redigé
- **THEN** le bloc est remplacé par `[REDACTED]` et le corps n'apparaît pas dans le texte rendu

#### Scenario: Suites de blancs différentes
- **GIVEN** un bloc dont le marqueur d'ouverture porte deux espaces entre `PRIVATE` et `KEY`, ou dont les marqueurs d'ouverture et de fin portent des suites de blancs différentes entre les mêmes mots
- **WHEN** le contenu est redigé
- **THEN** le bloc seul est remplacé par `[REDACTED]` et le corps n'apparaît pas dans le texte rendu

#### Scenario: Séparateur CRLF
- **GIVEN** le contenu de « Marqueurs en minuscules », dont chaque séparateur est CRLF
- **WHEN** le contenu est redigé
- **THEN** le corps n'apparaît pas dans le texte rendu et chaque CR et LF du contenu est conservé

#### Scenario: Variantes non reconnues
- **GIVEN** un bloc dont les marqueurs ont moins de cinq tirets, une espace avant les tirets de fin, `BEGIN` collé au type, des tirets demi-cadratin, une lettre cyrillique ou un autre blanc Unicode (U+2003, U+3000, U+200B), ou un fichier au format PuTTY
- **WHEN** le contenu est redigé
- **THEN** le texte rendu est identique au contenu

### Requirement: Un marqueur END hors début de ligne ferme le bloc à la fin de sa ligne
Un marqueur `-----END <type>-----` du type du bloc SHALL fermer ce bloc où qu'il se trouve sur sa ligne. S'il commence sa ligne, précédé au plus d'espaces, de tabulations, d'espaces insécables ou de tirets, le bloc SHALL s'arrêter après ses tirets de fin. Sinon, le bloc SHALL s'arrêter à la fin de la ligne du marqueur, avant son CR ou son LF, et le texte des lignes suivantes SHALL rester visible.

#### Scenario: Marqueur END au milieu d'une ligne
- **GIVEN** un bloc `RSA PRIVATE KEY` dont une ligne vaut `AAA-----END RSA PRIVATE KEY-----SECRETBODY`, suivie d'une ligne `-----END RSA PRIVATE KEY-----` puis d'une ligne `after`
- **WHEN** le contenu est redigé
- **THEN** le bloc est remplacé par `[REDACTED]` jusqu'à la fin de la ligne qui porte `SECRETBODY`, `SECRETBODY` n'apparaît pas dans le texte rendu, les deux lignes suivantes restent visibles et chaque CR et LF est conservé

#### Scenario: Clé de compte de service sur une ligne
- **GIVEN** un fichier JSON dont la ligne `"private_key"` porte tout le bloc, ses retours à la ligne échappés en `\n`, suivie d'une ligne `"client_email"`
- **WHEN** le contenu est redigé
- **THEN** le corps n'apparaît pas dans le texte rendu et la ligne `"client_email"` reste visible

#### Scenario: Concaténation de code et commentaire
- **GIVEN** un bloc écrit en concaténation de chaînes Java, ou en commentaire `//`, dont la ligne de fin porte `-----END RSA PRIVATE KEY-----` après d'autres caractères
- **WHEN** le contenu est redigé
- **THEN** le bloc est remplacé par `[REDACTED]` jusqu'à la fin de la ligne de fin, et la ligne suivante reste visible

#### Scenario: Blancs avant le marqueur END
- **GIVEN** un bloc dont la ligne de fin commence par une espace, une tabulation et une espace insécable avant `-----END RSA PRIVATE KEY-----`
- **WHEN** le contenu est redigé
- **THEN** le bloc est fermé par cette ligne et le texte qui la suit reste visible

### Requirement: Un marqueur END n'avale pas le marqueur BEGIN qui le suit sur sa ligne
Quand un marqueur `-----BEGIN ` qui ouvre une clé privée suit un `-----END <type>-----` sur la même ligne, le bloc fermé par ce `END` SHALL s'arrêter avant ce `BEGIN`, et le bloc suivant SHALL être remplacé par `[REDACTED]`. Les tirets qui suivent le type du `END` forment une seule suite, dont les cinq derniers valent tirets d'ouverture du `BEGIN`. Le texte entre le `END` et le `BEGIN` reste dans le bloc masqué.

#### Scenario: END au milieu d'une ligne puis BEGIN
- **GIVEN** un bloc `RSA PRIVATE KEY` dont la ligne de fin vaut `# -----END RSA PRIVATE KEY-----`, suivie sur la même ligne de `-----BEGIN EC PRIVATE KEY-----`, puis d'un corps `MIIfakebodyNOTAKEY0123` et d'une ligne `-----END EC PRIVATE KEY-----`
- **WHEN** le contenu est redigé, avec des séparateurs LF, CRLF ou CR
- **THEN** le corps des deux blocs n'apparaît pas dans le texte rendu et chaque CR et LF du contenu est conservé

#### Scenario: END en début de ligne puis BEGIN sur la même ligne
- **GIVEN** un bloc dont la ligne de fin commence par `-----END RSA PRIVATE KEY-----`, suivie sur la même ligne de `-----BEGIN EC PRIVATE KEY-----`, puis d'un corps et de la ligne de fin du second bloc
- **WHEN** le contenu est redigé
- **THEN** le corps du second bloc n'apparaît pas dans le texte rendu

#### Scenario: Tirets de fin partagés avec l'ouverture
- **GIVEN** un contenu où les tirets de fin de `-----END RSA PRIVATE KEY-----` sont aussi les tirets d'ouverture de `BEGIN EC PRIVATE KEY-----`, que le `END` commence sa ligne ou non
- **WHEN** le contenu est redigé
- **THEN** le corps du second bloc n'apparaît pas dans le texte rendu

#### Scenario: Six à neuf tirets entre le END et le BEGIN
- **GIVEN** un contenu où `-----END RSA PRIVATE KEY` est suivi, sur la même ligne, de six, sept, huit ou neuf tirets puis de `BEGIN EC PRIVATE KEY-----`, que le `END` commence sa ligne ou non
- **WHEN** le contenu est redigé, avec des séparateurs LF, CRLF ou CR
- **THEN** le corps des deux blocs n'apparaît pas dans le texte rendu et chaque CR et LF du contenu est conservé

#### Scenario: Deux clés sur une ligne JSON
- **GIVEN** une ligne JSON qui porte deux clés privées dont les retours à la ligne sont échappés en `\n`, suivie d'une ligne `"client_email"`
- **WHEN** le contenu est redigé
- **THEN** le corps des deux clés n'apparaît pas dans le texte rendu et la ligne `"client_email"` reste visible

#### Scenario: BEGIN d'un autre type sur la ligne du END
- **GIVEN** un bloc dont la ligne de fin porte `-----END RSA PRIVATE KEY-----` suivi de `-----BEGIN CERTIFICATE-----` et d'un texte
- **WHEN** le contenu est redigé
- **THEN** le bloc est remplacé par `[REDACTED]` jusqu'à la fin de cette ligne, texte compris, comme sans marqueur `BEGIN`
