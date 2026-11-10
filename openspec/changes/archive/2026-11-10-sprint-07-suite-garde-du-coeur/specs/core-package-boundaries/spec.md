# Spec Delta

## ADDED Requirements

### Requirement: Le test d'architecture lit les références pleinement qualifiées du cœur
Le test d'architecture SHALL compter comme dépendance entre packages toute référence pleinement qualifiée `com.nexus.<package>.<Type>` du code de production du cœur, importée ou non, pour le graphe des cycles comme pour les règles de sens.

#### Scenario: Cycle par nom pleinement qualifié
- **GIVEN** une classe de `com.nexus.ranking` qui référence `com.nexus.search.SearchService` sans import
- **WHEN** le test d'architecture analyse ce code
- **THEN** il échoue et nomme les packages du cycle

#### Scenario: Remontée depuis project par nom pleinement qualifié
- **GIVEN** une classe de `com.nexus.project` qui référence `com.nexus.search.SearchService` sans import
- **WHEN** le test d'architecture analyse ce code
- **THEN** la règle « Le package project est une feuille » échoue en nommant la classe

#### Scenario: Graphe courant
- **WHEN** le test d'architecture analyse le code de production du cœur avec les références qualifiées
- **THEN** il ne trouve aucun cycle et aucune violation des règles de sens
