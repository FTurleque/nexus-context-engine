# Spec Delta

## ADDED Requirements

### Requirement: Un vecteur de requête partagé n'est réutilisé que pour la même requête
Le contexte d'une requête SHALL rendre un vecteur de requête déjà calculé seulement pour le même fournisseur, le même modèle et le même texte de requête redacté ; un autre texte SHALL déclencher un nouveau calcul.

#### Scenario: Contexte réutilisé pour deux requêtes
- **GIVEN** un contexte de requête dans lequel la recherche sémantique a calculé le vecteur de `parseConfig`
- **WHEN** le même contexte sert une recherche sémantique de `renderError`
- **THEN** le fournisseur d'embeddings reçoit un second appel pour `renderError`
- **AND** les résultats de `renderError` ne sont pas ceux du vecteur de `parseConfig`

#### Scenario: Même requête sur plusieurs projets
- **WHEN** le même contexte sert la même requête sur 3 projets
- **THEN** le fournisseur d'embeddings reçoit exactement un appel
