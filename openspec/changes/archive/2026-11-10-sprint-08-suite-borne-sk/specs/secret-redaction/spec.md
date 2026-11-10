# Spec Delta

## ADDED Requirements

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

## MODIFIED Requirements

### Requirement: Un changement de politique invalide les index dérivés
Toute modification du comportement de redaction SHALL incrémenter `SensitiveContentRedactor.POLICY_VERSION` et le profil sémantique `content-vN`, et SHALL être accompagnée d'une migration qui repasse les projets indexés en `NOT_INDEXED`.

#### Scenario: Mise à jour d'une base existante
- **GIVEN** une base migrée avec la politique `secret-redaction-v6`
- **WHEN** la nouvelle version l'ouvre
- **THEN** la migration d'invalidation incrémente la génération d'index des projets, les repasse en `NOT_INDEXED` et conserve le registre des projets

#### Scenario: Index lexical d'une ancienne politique
- **GIVEN** un index Lucene dont la version de politique diffère de `secret-redaction-v7`
- **WHEN** l'indexation d'un projet vérifie la présence de l'index lexical
- **THEN** il est traité comme invalide et reconstruit
