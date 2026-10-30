# adapter-contract-tests Specification

## Purpose
Définit la granularité des tests de contrat des adaptateurs REST et MCP, pour qu'un contrat en échec ne masque pas les autres.

## Requirements

### Requirement: Un test par endpoint REST
`NexusResourceTest` SHALL contenir une méthode `@Test` distincte pour chaque endpoint REST public de l'adaptateur et pour chaque endpoint Quarkus exposé (`/q/health/ready`, `/q/metrics`).

#### Scenario: Endpoint en régression
- **WHEN** la réponse de `POST /api/v1/projects/{projectId}/search` ne respecte plus son contrat
- **THEN** seul le test de cet endpoint échoue et les tests des autres endpoints s'exécutent et sont rapportés

#### Scenario: Nombre de tests
- **WHEN** on lit le rapport Surefire de `NexusResourceTest`
- **THEN** il compte au moins un test par endpoint

### Requirement: Un test par outil MCP
`NexusMcpServerIntegrationTest` SHALL contenir une méthode `@Test` distincte pour chaque outil MCP exposé par le serveur.

#### Scenario: Outil en régression
- **WHEN** la réponse de l'outil `build_context` ne respecte plus son contrat
- **THEN** seul le test de cet outil échoue et les tests des autres outils sont rapportés

### Requirement: Chaque test de contrat vérifie la non-fuite
Chaque test qui lit une réponse REST ou MCP SHALL vérifier que la réponse ne contient ni chemin absolu de la machine, ni secret de la fixture, ni type du modèle interne du cœur.

#### Scenario: Secret de la fixture
- **WHEN** la fixture contient la valeur synthétique `synthetic-unquoted-secret-123456`
- **THEN** aucune réponse testée ne contient cette valeur

### Requirement: Les tests de contrat ne dépendent pas de leur ordre
Les tests de contrat REST et MCP SHALL passer quel que soit leur ordre d'exécution.

#### Scenario: Ordre inversé
- **WHEN** les méthodes de test s'exécutent dans un ordre différent de leur déclaration
- **THEN** elles passent toutes
