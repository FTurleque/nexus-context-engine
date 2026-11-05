# Spec Delta

## Purpose

Définit la surveillance de sécurité active en permanence sur le dépôt public, indépendamment des PR en cours.

## ADDED Requirements

### Requirement: Les dépendances fusionnées sont surveillées en continu
Le dépôt SHALL avoir les alertes Dependabot et les mises à jour de sécurité Dependabot activées.

#### Scenario: Lecture des réglages
- **WHEN** le mainteneur lit `security_and_analysis` du dépôt avec `gh api`
- **THEN** `dependabot_security_updates` vaut `enabled` et l'API des alertes de vulnérabilité répond 204

### Requirement: Les secrets poussés sont bloqués ou signalés
Le dépôt SHALL avoir le secret scanning et la push protection activés.

#### Scenario: Lecture des réglages
- **WHEN** le mainteneur lit `security_and_analysis` du dépôt
- **THEN** `secret_scanning` et `secret_scanning_push_protection` valent `enabled`

### Requirement: Le traitement des alertes est documenté
`SECURITY.md` SHALL décrire le délai de prise en charge, le triage et la fermeture justifiée des faux positifs.

#### Scenario: Faux positif d'une fixture de redaction
- **WHEN** le secret scanning signale une valeur d'une fixture de test de redaction
- **THEN** la procédure de `SECURITY.md` indique de fermer l'alerte comme utilisée en test, avec le chemin de la fixture
