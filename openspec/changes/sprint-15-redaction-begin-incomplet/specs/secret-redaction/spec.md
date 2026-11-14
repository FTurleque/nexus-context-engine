# Spec Delta

## ADDED Requirements

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

## MODIFIED Requirements

### Requirement: Un changement de politique invalide les index dérivés
Toute modification du comportement de redaction SHALL incrémenter `SensitiveContentRedactor.POLICY_VERSION` et le profil sémantique `content-vN`, et SHALL être accompagnée d'une migration qui repasse les projets indexés en `NOT_INDEXED`.

#### Scenario: Mise à jour d'une base existante
- **GIVEN** une base migrée avec la politique `secret-redaction-v9`
- **WHEN** la nouvelle version l'ouvre
- **THEN** la migration d'invalidation incrémente la génération d'index des projets, les repasse en `NOT_INDEXED` et conserve le registre des projets

#### Scenario: Index lexical d'une ancienne politique
- **GIVEN** un index Lucene dont la version de politique diffère de `secret-redaction-v10`
- **WHEN** l'indexation d'un projet vérifie la présence de l'index lexical
- **THEN** il est traité comme invalide et reconstruit
