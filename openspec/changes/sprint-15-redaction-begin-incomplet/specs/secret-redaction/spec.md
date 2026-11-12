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
- **GIVEN** un contenu dont la première ligne est `-----BEGIN CERTIFICATE-----BEGIN RSA PRIVATE KEY-----`, suivie d'un corps et de la ligne `-----END RSA PRIVATE KEY-----`
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
