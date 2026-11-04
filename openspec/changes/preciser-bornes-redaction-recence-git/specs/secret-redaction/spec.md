# Spec Delta

## ADDED Requirements

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
