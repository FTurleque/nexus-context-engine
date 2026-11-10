# Spec Delta

## ADDED Requirements

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

## MODIFIED Requirements

### Requirement: Un changement de politique invalide les index dérivés
Toute modification du comportement de redaction SHALL incrémenter `SensitiveContentRedactor.POLICY_VERSION` et le profil sémantique `content-vN`, et SHALL être accompagnée d'une migration qui repasse les projets indexés en `NOT_INDEXED`.

#### Scenario: Mise à jour d'une base existante
- **GIVEN** une base migrée avec la politique `secret-redaction-v7`
- **WHEN** la nouvelle version l'ouvre
- **THEN** la migration d'invalidation incrémente la génération d'index des projets, les repasse en `NOT_INDEXED` et conserve le registre des projets

#### Scenario: Index lexical d'une ancienne politique
- **GIVEN** un index Lucene dont la version de politique diffère de `secret-redaction-v8`
- **WHEN** l'indexation d'un projet vérifie la présence de l'index lexical
- **THEN** il est traité comme invalide et reconstruit
