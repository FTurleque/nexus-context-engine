# Spec Delta

## ADDED Requirements

### Requirement: Les métadonnées de contexte suivent le contrat typé de la sortie publique
Les clés de `metadata` d'un contexte simple et d'un contexte fédéré SHALL être fixées par des records de la sortie publique et comparées par le contrat de clés, comme le reste de la réponse, sur la CLI en JSON, REST et MCP. Les clés publiques et leur ordre lexicographique SHALL rester ceux d'avant le typage.

#### Scenario: Clé de métadonnée renommée
- **WHEN** une clé de `metadata` d'un contexte est renommée, retirée ou ajoutée dans le cœur sans que le record public change
- **THEN** la compilation ou un test de parité échoue

#### Scenario: Clés lues par le self-smoke
- **WHEN** un contexte simple est produit par la CLI en JSON
- **THEN** `metadata` contient `nativeSourcesDiscovered`, `gitEnabled`, `gitRepositoryAvailable`, `gitCommitsInspected`, `gitRelatedCommits`, `gitSelectedItems`, `skillsDiscovered`, `skillSelectedItems`, `skillResourcesDiscovered`, `skillsExecuted` et `reductionRatio`
- **AND** `scripts/self-smoke.ps1` réussit sans modification de ses accès à `metadata`

#### Scenario: Forme JSON inchangée
- **GIVEN** une fixture et une requête de contexte simple et de contexte fédéré
- **WHEN** les réponses sont produites avant et après le typage, sans `explain`
- **THEN** les clés de `metadata` et leur ordre sont identiques

#### Scenario: Valeurs redigées et filtrées
- **GIVEN** une métadonnée textuelle qui contient le chemin absolu de la racine du projet
- **WHEN** le contexte est produit sur un canal public
- **THEN** la valeur exposée ne contient pas ce chemin absolu
