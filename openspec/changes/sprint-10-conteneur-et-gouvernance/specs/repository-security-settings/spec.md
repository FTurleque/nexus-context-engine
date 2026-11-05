# Spec Delta

## Purpose

Définit les réglages et la documentation qui permettent de signaler une vulnérabilité du dépôt public sans la divulguer.

## ADDED Requirements

### Requirement: Un canal privé de signalement est ouvert et documenté
Le dépôt SHALL avoir le signalement privé de vulnérabilités activé, et `SECURITY.md` SHALL indiquer ce canal, les versions prises en charge et le délai de première réponse, en demandant de ne pas ouvrir d'issue publique.

#### Scenario: Lecture du réglage
- **WHEN** le mainteneur lit `private-vulnerability-reporting` avec `gh api`
- **THEN** la réponse vaut `{"enabled":true}`

#### Scenario: Profil communautaire
- **WHEN** le profil communautaire du dépôt est lu
- **THEN** il référence un fichier de politique de sécurité
