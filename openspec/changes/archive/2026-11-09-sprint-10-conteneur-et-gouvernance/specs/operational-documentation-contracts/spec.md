# Spec Delta

## Purpose

Définit ce que la documentation courante doit refléter du code et du `pom.xml`, et que `scripts/release/test-operational-doc-contracts.sh` vérifie à chaque build.

## ADDED Requirements

### Requirement: La documentation ne recopie pas les versions des dépendances montées par Dependabot
La documentation courante SHALL désigner les versions de Quarkus et du SDK MCP par leurs propriétés `quarkus.platform.version` et `mcp.sdk.version` du `pom.xml`, et le contrat documentaire SHALL lire ces propriétés dans le `pom.xml` sans comparer de numéro littéral.

#### Scenario: Montée de version par Dependabot
- **WHEN** une PR ne modifie que `quarkus.platform.version` ou `mcp.sdk.version` dans le `pom.xml`
- **THEN** `scripts/release/test-operational-doc-contracts.sh` reste vert

#### Scenario: Version recopiée dans la documentation
- **WHEN** un document courant contrôlé contient un littéral de la forme `Quarkus 3.x.y` ou `MCP SDK 2.x.y`
- **THEN** le contrat échoue en nommant le document

#### Scenario: Propriété absente du pom
- **WHEN** le `pom.xml` ne déclare plus l'une des deux propriétés
- **THEN** le contrat échoue
