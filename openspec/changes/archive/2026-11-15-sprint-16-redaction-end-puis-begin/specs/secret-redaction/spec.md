# Spec Delta

## ADDED Requirements

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

## MODIFIED Requirements

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
