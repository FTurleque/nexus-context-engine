# Spec Delta

## ADDED Requirements

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

## MODIFIED Requirements

### Requirement: Un changement de politique invalide les index dérivés
Toute modification du comportement de redaction SHALL incrémenter `SensitiveContentRedactor.POLICY_VERSION` et le profil sémantique `content-vN`, et SHALL être accompagnée d'une migration qui repasse les projets indexés en `NOT_INDEXED`.

#### Scenario: Mise à jour d'une base existante
- **GIVEN** une base migrée avec la politique `secret-redaction-v8`
- **WHEN** la nouvelle version l'ouvre
- **THEN** la migration d'invalidation incrémente la génération d'index des projets, les repasse en `NOT_INDEXED` et conserve le registre des projets

#### Scenario: Index lexical d'une ancienne politique
- **GIVEN** un index Lucene dont la version de politique diffère de `secret-redaction-v9`
- **WHEN** l'indexation d'un projet vérifie la présence de l'index lexical
- **THEN** il est traité comme invalide et reconstruit
