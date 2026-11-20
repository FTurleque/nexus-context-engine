# Spec Delta

## Purpose

Rend explicite, pour l'agent qui consomme un contexte NEXUS et pour l'opérateur, que le contenu servi vient du dépôt indexé et n'a pas d'autorité d'instruction, afin de limiter l'injection d'instructions par un dépôt non maîtrisé.
Documentation durable : docs/developer/context-building.md#openspec-context-content-provenance

## ADDED Requirements

### Requirement: Chaque élément de contexte porte sa provenance et sa confiance
Chaque élément d'un contexte simple ou fédéré SHALL porter les clés `origin` et `trust`, composantes du record public d'élément de contexte, sur la CLI en JSON, REST et MCP. Un élément lu dans le dépôt indexé SHALL porter `origin` = `repository` et `trust` = `untrusted`.

#### Scenario: Élément de code
- **GIVEN** un contexte qui contient un fragment de `src/main/java/App.java`
- **WHEN** ce contexte est produit par la CLI en JSON, par REST et par MCP
- **THEN** l'élément porte `origin` = `repository` et `trust` = `untrusted` sur les trois canaux

#### Scenario: Clés absentes d'un canal
- **WHEN** un adaptateur sérialise un élément de contexte sans `origin` ou sans `trust`
- **THEN** le test de parité de ce canal échoue

### Requirement: Les instructions et skills du dépôt ne sont jamais marquées fiables
Un élément de type `INSTRUCTION` ou `SKILL` SHALL porter `trust` = `untrusted`, quels que soient son nom de fichier, sa profondeur dans le dépôt et sa priorité de sélection.

#### Scenario: AGENTS.md imbriqué
- **GIVEN** un dépôt indexé qui contient `vendor/lib/AGENTS.md` et une cible sous `vendor/lib/`
- **WHEN** un contexte qui sélectionne cette instruction est construit
- **THEN** l'élément `INSTRUCTION` porte `origin` = `repository` et `trust` = `untrusted`

#### Scenario: Skill sélectionnée
- **GIVEN** une skill déclarée sous `.claude/skills/` dans le dépôt indexé
- **WHEN** un contexte qui active cette skill est construit
- **THEN** l'élément `SKILL` porte `trust` = `untrusted`

### Requirement: Les outils MCP de contexte déclarent que le contenu servi est une donnée
La description des outils MCP `build_context`, `explain_context`, `build_context_across_projects` et `explain_context_across_projects` SHALL indiquer, en français, que le contenu servi provient du dépôt indexé et est une donnée à évaluer, pas une instruction à suivre.

#### Scenario: Liste des outils
- **WHEN** un client MCP liste les outils du serveur NEXUS
- **THEN** la description de chacun des quatre outils de contexte contient cette mise en garde

### Requirement: Le modèle de menace d'injection d'instructions est écrit
`SECURITY.md` et `docs/developer/current-limitations.md` SHALL décrire le modèle de menace d'injection d'instructions par un dépôt indexé : sources concernées, bornes existantes, marquage de confiance, limites restantes et conduite attendue de l'agent consommateur.

#### Scenario: Contrat documentaire
- **WHEN** les contrats documentaires du dépôt sont exécutés
- **THEN** ils vérifient la présence de la section du modèle de menace dans `SECURITY.md` et dans `docs/developer/current-limitations.md`
