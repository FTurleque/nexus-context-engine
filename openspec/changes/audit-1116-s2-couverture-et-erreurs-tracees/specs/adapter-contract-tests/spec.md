# Spec Delta

## ADDED Requirements

### Requirement: Un job planifié exécute les tests REST et MCP en ordre aléatoire
Un job CI planifié SHALL exécuter tous les tests des modules REST et MCP avec un ordre aléatoire des classes et des méthodes, SHALL publier dans son journal la graine utilisée, et SHALL échouer si un test échoue.

#### Scenario: Exécution hebdomadaire
- **WHEN** le job planifié s'exécute sur `main`
- **THEN** les tests des modules REST et MCP s'exécutent dans un ordre de classes et de méthodes tiré au hasard, et la graine figure dans le journal du job

#### Scenario: Dépendance d'ordre introduite
- **GIVEN** un test dont le résultat dépend de l'exécution préalable d'un autre test de la même classe
- **WHEN** le job tire un ordre où ce test s'exécute en premier
- **THEN** le job échoue

#### Scenario: Rejeu d'un échec
- **GIVEN** la graine publiée par un job en échec
- **WHEN** le job est relancé manuellement avec cette graine
- **THEN** les tests s'exécutent dans le même ordre
