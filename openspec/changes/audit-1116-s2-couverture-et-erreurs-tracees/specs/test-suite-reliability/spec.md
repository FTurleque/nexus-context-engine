# Spec Delta

## ADDED Requirements

### Requirement: Le découpage d'un fichier en fenêtres de contexte est exécuté par la suite par défaut
Les méthodes `queryRanges`, `queryTerms` et `mergeRanges` de `ContextFragmentFactory` SHALL être exécutées par au moins un test de la suite par défaut du module `core`, avec un fichier au-dessus du seuil plein.

#### Scenario: Couverture des trois méthodes
- **WHEN** on lit les lignes `queryRanges`, `queryTerms` et `mergeRanges` du `jacoco.xml` de `core` après `./mvnw clean install`
- **THEN** chacune a au moins une ligne couverte

#### Scenario: Régression de la fusion
- **GIVEN** une modification de `mergeRanges` qui ne fusionne plus deux fenêtres contiguës
- **WHEN** la suite par défaut du core s'exécute
- **THEN** au moins un test échoue
