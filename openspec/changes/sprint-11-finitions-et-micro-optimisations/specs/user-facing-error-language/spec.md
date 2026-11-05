# Spec Delta

## Purpose

Définit la langue des messages d'erreur que NEXUS remonte à l'utilisateur par la CLI, REST et MCP.

## ADDED Requirements

### Requirement: Les erreurs provoquées par l'utilisateur sont en français
Un message d'exception atteignable depuis un argument CLI, une requête REST ou MCP, un fichier ou une variable de configuration SHALL être rédigé en français ; les préconditions internes MAY rester dans la langue du fichier.

#### Scenario: Requête vide
- **WHEN** un contexte fédéré est demandé avec une requête vide
- **THEN** le message d'erreur est en français

#### Scenario: Configuration Ollama hors bornes
- **WHEN** la dimension d'embedding Ollama configurée est hors de l'intervalle 1 à 1024
- **THEN** le message d'erreur est en français et rappelle l'intervalle accepté

#### Scenario: Règle écrite
- **WHEN** un contributeur lit `.claude/rules/java-core.md`
- **THEN** la règle distingue les messages atteignables par l'utilisateur, en français, des préconditions internes
